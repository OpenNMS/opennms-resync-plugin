/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */

package org.opennms.resync;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.opennms.integration.api.v1.dao.NodeDao;
import org.opennms.integration.api.v1.model.IpInterface;
import org.opennms.integration.api.v1.model.Node;
import org.opennms.netmgt.config.api.SnmpAgentConfigFactory;
import org.opennms.netmgt.events.api.EventForwarder;
import org.opennms.netmgt.snmp.SnmpAgentConfig;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.netmgt.snmp.SnmpValue;
import org.opennms.netmgt.snmp.proxy.LocationAwareSnmpClient;
import org.opennms.netmgt.snmp.proxy.SNMPRequestBuilder;
import org.opennms.resync.config.ActionConfigs;
import org.opennms.resync.config.ActionType;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for SNMP instance suffix logic in ActionService.
 * Tests the actual service implementation with mocked dependencies.
 */
public class ActionServiceInstanceSuffixTest {

    @Mock
    private LocationAwareSnmpClient snmpClient;

    @Mock
    private SnmpAgentConfigFactory snmpAgentConfigFactory;

    @Mock
    private EventForwarder eventForwarder;

    @Mock
    private NodeDao nodeDao;

    @Mock
    private ActionConfigs actionConfigs;

    @Mock
    private Node node;

    @Mock
    private IpInterface ipInterface;

    private ActionService actionService;

    private SnmpAgentConfig agentConfig;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        actionService = new ActionService(snmpClient, snmpAgentConfigFactory, eventForwarder, nodeDao, actionConfigs);

        agentConfig = new SnmpAgentConfig();
        agentConfig.setAddress(InetAddress.getLoopbackAddress());
    }

    /**
     * Helper method to setup SNMP client mock with builder pattern
     */
    @SuppressWarnings("unchecked")
    private void setupSnmpSetMock() {
        // Create a mock builder that returns itself for withLocation() and CompletableFuture for execute()
        SNMPRequestBuilder<SnmpValue> mockBuilder = mock(SNMPRequestBuilder.class, (Answer) invocation -> {
            String methodName = invocation.getMethod().getName();
            if ("withLocation".equals(methodName)) {
                return invocation.getMock(); // Return self for fluent pattern
            } else if ("execute".equals(methodName)) {
                return CompletableFuture.completedFuture(null);
            }
            return null;
        });

        when(snmpClient.set(any(), any(SnmpObjId[].class), any(SnmpValue[].class)))
                .thenReturn(mockBuilder);
    }

    @Test
    public void testExecuteSet_MultiColumn_AppendsInstanceSuffix() throws Exception {
        // Setup: Multi-column config (index + data column)
        LinkedHashMap<String, SnmpObjId> columns = new LinkedHashMap<>();
        columns.put("alarmIndex", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.3.1.9"));
        columns.put("alarmAck", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.3.1.18"));

        ActionConfigs.Entry config = ActionConfigs.Entry.builder()
                .kind("test1")
                .actionType(ActionType.ACK)
                .columns(columns)
                .parameters(new HashMap<>())
                .build();

        // Mock dependencies
        when(nodeDao.getNodeByLabel("test-node")).thenReturn(node);
        when(node.getLabel()).thenReturn("test-node");
        when(node.getId()).thenReturn(1);
        when(node.getLocation()).thenReturn("Default");
        when(node.getIpInterfaces()).thenReturn(Collections.singletonList(ipInterface));
        when(ipInterface.getIpAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(snmpAgentConfigFactory.getAgentConfig(any(InetAddress.class), eq("Default"))).thenReturn(agentConfig);
        when(actionConfigs.getActionConfig("test-node", null, ActionType.ACK)).thenReturn(config);

        // Mock SNMP client response
        setupSnmpSetMock();

        // Execute
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("alarmIndex", "2");
        parameters.put("alarmAck", 1);

        ActionService.Request request = ActionService.Request.builder()
                .nodeCriteria("test-node")
                .actionId("test-123")
                .actionType(ActionType.ACK)
                .parameters(parameters)
                .build();

        actionService.executeAction(request).get();

        // Verify: SNMP SET called with OID with instance suffix .2
        ArgumentCaptor<SnmpObjId[]> oidCaptor = ArgumentCaptor.forClass(SnmpObjId[].class);
        ArgumentCaptor<SnmpValue[]> valueCaptor = ArgumentCaptor.forClass(SnmpValue[].class);

        verify(snmpClient).set(any(), oidCaptor.capture(), valueCaptor.capture());

        SnmpObjId[] capturedOids = oidCaptor.getValue();
        assertEquals("Should have 1 OID (skipped index)", 1, capturedOids.length);
        assertEquals("OID should have instance suffix .2",
                ".1.3.6.1.4.1.3902.4101.1.3.1.18.2",
                capturedOids[0].toString());

        SnmpValue[] capturedValues = valueCaptor.getValue();
        assertEquals("Should have 1 value", 1, capturedValues.length);
        assertEquals("Value should be 1", 1, capturedValues[0].toInt());
    }

    @Test
    public void testExecuteSet_SingleColumn_PassthroughOid() throws Exception {
        // Setup: Single column config (scalar or pre-completed OID)
        LinkedHashMap<String, SnmpObjId> columns = new LinkedHashMap<>();
        columns.put("param1", SnmpObjId.get("1.3.6.1.4.1.28458.1.26.3.1.3.2.0"));

        ActionConfigs.Entry config = ActionConfigs.Entry.builder()
                .kind("custom")
                .actionType(ActionType.ACK)
                .columns(columns)
                .parameters(new HashMap<>())
                .build();

        // Mock dependencies
        when(nodeDao.getNodeByLabel("test-node")).thenReturn(node);
        when(node.getLabel()).thenReturn("test-node");
        when(node.getId()).thenReturn(1);
        when(node.getLocation()).thenReturn("Default");
        when(node.getIpInterfaces()).thenReturn(Collections.singletonList(ipInterface));
        when(ipInterface.getIpAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(snmpAgentConfigFactory.getAgentConfig(any(InetAddress.class), eq("Default"))).thenReturn(agentConfig);
        when(actionConfigs.getActionConfig("test-node", null, ActionType.ACK)).thenReturn(config);

        // Mock SNMP client response
        setupSnmpSetMock();

        // Execute
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("param1", "all");

        ActionService.Request request = ActionService.Request.builder()
                .nodeCriteria("test-node")
                .actionId("test-456")
                .actionType(ActionType.ACK)
                .parameters(parameters)
                .build();

        actionService.executeAction(request).get();

        // Verify: SNMP SET called with OID as-is (no suffix)
        ArgumentCaptor<SnmpObjId[]> oidCaptor = ArgumentCaptor.forClass(SnmpObjId[].class);

        verify(snmpClient).set(any(), oidCaptor.capture(), any(SnmpValue[].class));

        SnmpObjId[] capturedOids = oidCaptor.getValue();
        assertEquals("Should have 1 OID", 1, capturedOids.length);
        assertEquals("Should use OID as-is for single column",
                ".1.3.6.1.4.1.28458.1.26.3.1.3.2.0",
                capturedOids[0].toString());
    }

    @Test
    public void testExecuteSet_MultipleDataColumns() throws Exception {
        // Setup: Multi-column config with multiple data columns
        LinkedHashMap<String, SnmpObjId> columns = new LinkedHashMap<>();
        columns.put("alarmIndex", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.4.1.3"));
        columns.put("alarmAck", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.4.1.16"));
        columns.put("alarmComment", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.4.1.17"));

        ActionConfigs.Entry config = ActionConfigs.Entry.builder()
                .kind("test1")
                .actionType(ActionType.ACK)
                .columns(columns)
                .parameters(new HashMap<>())
                .build();

        // Mock dependencies
        when(nodeDao.getNodeByLabel("test-node")).thenReturn(node);
        when(node.getLabel()).thenReturn("test-node");
        when(node.getId()).thenReturn(1);
        when(node.getLocation()).thenReturn("Default");
        when(node.getIpInterfaces()).thenReturn(Collections.singletonList(ipInterface));
        when(ipInterface.getIpAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(snmpAgentConfigFactory.getAgentConfig(any(InetAddress.class), eq("Default"))).thenReturn(agentConfig);
        when(actionConfigs.getActionConfig("test-node", null, ActionType.ACK)).thenReturn(config);

        // Mock SNMP client response
        setupSnmpSetMock();

        // Execute
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("alarmIndex", "5");
        parameters.put("alarmAck", 1);
        parameters.put("alarmComment", "Acknowledged");

        ActionService.Request request = ActionService.Request.builder()
                .nodeCriteria("test-node")
                .actionId("test-789")
                .actionType(ActionType.ACK)
                .parameters(parameters)
                .build();

        actionService.executeAction(request).get();

        // Verify: SNMP SET called with 2 OIDs (both data columns), both with .5 suffix
        ArgumentCaptor<SnmpObjId[]> oidCaptor = ArgumentCaptor.forClass(SnmpObjId[].class);

        verify(snmpClient).set(any(), oidCaptor.capture(), any(SnmpValue[].class));

        SnmpObjId[] capturedOids = oidCaptor.getValue();
        assertEquals("Should have 2 OIDs (2 data columns)", 2, capturedOids.length);
        assertEquals("First OID should have .5 suffix",
                ".1.3.6.1.4.1.3902.4101.1.4.1.16.5",
                capturedOids[0].toString());
        assertEquals("Second OID should have .5 suffix",
                ".1.3.6.1.4.1.3902.4101.1.4.1.17.5",
                capturedOids[1].toString());
    }

    @Test
    public void testExecuteSet_PreCompletedTableOid() throws Exception {
        // Setup: Single column with pre-completed instance (e.g., ...18.2)
        LinkedHashMap<String, SnmpObjId> columns = new LinkedHashMap<>();
        columns.put("alarmAck", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.3.1.18.2"));

        ActionConfigs.Entry config = ActionConfigs.Entry.builder()
                .kind("test1")
                .actionType(ActionType.ACK)
                .columns(columns)
                .parameters(new HashMap<>())
                .build();

        // Mock dependencies
        when(nodeDao.getNodeByLabel("test-node")).thenReturn(node);
        when(node.getLabel()).thenReturn("test-node");
        when(node.getId()).thenReturn(1);
        when(node.getLocation()).thenReturn("Default");
        when(node.getIpInterfaces()).thenReturn(Collections.singletonList(ipInterface));
        when(ipInterface.getIpAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(snmpAgentConfigFactory.getAgentConfig(any(InetAddress.class), eq("Default"))).thenReturn(agentConfig);
        when(actionConfigs.getActionConfig("test-node", null, ActionType.ACK)).thenReturn(config);

        // Mock SNMP client response
        setupSnmpSetMock();

        // Execute
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("alarmAck", 1);

        ActionService.Request request = ActionService.Request.builder()
                .nodeCriteria("test-node")
                .actionId("test-complete")
                .actionType(ActionType.ACK)
                .parameters(parameters)
                .build();

        actionService.executeAction(request).get();

        // Verify: SNMP SET called with complete OID as-is
        ArgumentCaptor<SnmpObjId[]> oidCaptor = ArgumentCaptor.forClass(SnmpObjId[].class);

        verify(snmpClient).set(any(), oidCaptor.capture(), any(SnmpValue[].class));

        SnmpObjId[] capturedOids = oidCaptor.getValue();
        assertEquals("Should use complete OID as-is",
                ".1.3.6.1.4.1.3902.4101.1.3.1.18.2",
                capturedOids[0].toString());
    }

    @Test
    public void testExecuteSet_StringIntegerParsing() throws Exception {
        // Setup: Test that string "1" is parsed as integer
        LinkedHashMap<String, SnmpObjId> columns = new LinkedHashMap<>();
        columns.put("status", SnmpObjId.get("1.3.6.1.4.1.100.1.0"));

        ActionConfigs.Entry config = ActionConfigs.Entry.builder()
                .kind("test")
                .actionType(ActionType.ACK)
                .columns(columns)
                .parameters(new HashMap<>())
                .build();

        // Mock dependencies
        when(nodeDao.getNodeByLabel("test-node")).thenReturn(node);
        when(node.getLabel()).thenReturn("test-node");
        when(node.getId()).thenReturn(1);
        when(node.getLocation()).thenReturn("Default");
        when(node.getIpInterfaces()).thenReturn(Collections.singletonList(ipInterface));
        when(ipInterface.getIpAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(snmpAgentConfigFactory.getAgentConfig(any(InetAddress.class), eq("Default"))).thenReturn(agentConfig);
        when(actionConfigs.getActionConfig("test-node", null, ActionType.ACK)).thenReturn(config);

        // Mock SNMP client response
        setupSnmpSetMock();

        // Execute with string that looks like integer
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("status", "1");

        ActionService.Request request = ActionService.Request.builder()
                .nodeCriteria("test-node")
                .actionId("test-parse")
                .actionType(ActionType.ACK)
                .parameters(parameters)
                .build();

        actionService.executeAction(request).get();

        // Verify: String "1" was parsed as integer
        ArgumentCaptor<SnmpValue[]> valueCaptor = ArgumentCaptor.forClass(SnmpValue[].class);

        verify(snmpClient).set(any(), any(SnmpObjId[].class), valueCaptor.capture());

        SnmpValue[] capturedValues = valueCaptor.getValue();
        assertEquals("String '1' should be parsed as integer", 1, capturedValues[0].toInt());
    }

    @Test
    public void testExecuteSet_MissingRequiredParameter() throws Exception {
        // Setup: Multi-column config
        LinkedHashMap<String, SnmpObjId> columns = new LinkedHashMap<>();
        columns.put("alarmIndex", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.3.1.9"));
        columns.put("alarmAck", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.3.1.18"));

        ActionConfigs.Entry config = ActionConfigs.Entry.builder()
                .kind("test1")
                .actionType(ActionType.ACK)
                .columns(columns)
                .parameters(new HashMap<>())
                .build();

        // Mock dependencies
        when(nodeDao.getNodeByLabel("test-node")).thenReturn(node);
        when(node.getLabel()).thenReturn("test-node");
        when(node.getId()).thenReturn(1);
        when(node.getLocation()).thenReturn("Default");
        when(node.getIpInterfaces()).thenReturn(Collections.singletonList(ipInterface));
        when(ipInterface.getIpAddress()).thenReturn(InetAddress.getLoopbackAddress());
        when(snmpAgentConfigFactory.getAgentConfig(any(InetAddress.class), eq("Default"))).thenReturn(agentConfig);
        when(actionConfigs.getActionConfig("test-node", null, ActionType.ACK)).thenReturn(config);

        // Execute with missing alarmAck parameter
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("alarmIndex", "2");
        // Missing alarmAck!

        ActionService.Request request = ActionService.Request.builder()
                .nodeCriteria("test-node")
                .actionId("test-missing")
                .actionType(ActionType.ACK)
                .parameters(parameters)
                .build();

        // Should throw IllegalArgumentException directly (not wrapped in ExecutionException)
        try {
            actionService.executeAction(request);
            fail("Expected IllegalArgumentException for missing parameter");
        } catch (IllegalArgumentException e) {
            assertTrue("Error message should mention missing parameter",
                    e.getMessage().contains("No value defined for parameter: alarmAck"));
        }
    }

    @Test
    public void testExecuteSet_NodeNotFound() throws Exception {
        // Mock: Node not found
        when(nodeDao.getNodeByLabel("nonexistent")).thenReturn(null);
        when(nodeDao.getNodeByCriteria("nonexistent")).thenReturn(null);

        ActionService.Request request = ActionService.Request.builder()
                .nodeCriteria("nonexistent")
                .actionId("test-notfound")
                .actionType(ActionType.ACK)
                .parameters(new HashMap<>())
                .build();

        // Should throw NoSuchElementException directly (not wrapped in ExecutionException)
        try {
            actionService.executeAction(request);
            fail("Expected NoSuchElementException for missing node");
        } catch (NoSuchElementException e) {
            assertTrue("Error message should mention node not found",
                    e.getMessage().contains("No such node"));
        }
    }
}
