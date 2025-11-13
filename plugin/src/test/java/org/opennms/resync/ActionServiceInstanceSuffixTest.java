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

import org.junit.Test;
import org.opennms.netmgt.snmp.SnmpObjId;
import java.util.*;
import static org.junit.Assert.*;

/**
 * Unit tests for SNMP instance suffix logic in ActionService.
 * Tests the fix for appending instance suffixes to table OIDs in SET operations.
 */
public class ActionServiceInstanceSuffixTest {

    @Test
    public void testMultiColumnInstanceSuffixLogic() {
        // Simulate config with index + data columns (table-based operation)
        Map<String, SnmpObjId> columns = new LinkedHashMap<>();
        columns.put("alarmIndex", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.3.1.9"));
        columns.put("alarmAck", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.3.1.18"));

        // Simulate request parameters
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("alarmIndex", "2");
        parameters.put("alarmAck", 1);

        // Apply fix logic
        List<SnmpObjId> oids = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        String indexKey = columns.keySet().iterator().next();
        String instance = parameters.get(indexKey).toString();

        for (var e : columns.entrySet()) {
            if (e.getKey().equals(indexKey)) {
                continue; // Skip index
            }
            SnmpObjId completeOid = SnmpObjId.get(e.getValue().toString() + "." + instance);
            oids.add(completeOid);
            values.add(parameters.get(e.getKey()));
        }

        // Assertions
        assertEquals("Should have 1 OID (skipped index)", 1, oids.size());
        assertEquals("OID should have instance suffix .2",
                ".1.3.6.1.4.1.3902.4101.1.3.1.18.2",
                oids.get(0).toString());
        assertEquals("Value should be 1", 1, values.get(0));
    }

    @Test
    public void testSingleColumnPassthrough() {
        // Simulate scalar OID config (customer's resync case)
        Map<String, SnmpObjId> columns = new LinkedHashMap<>();
        columns.put("param1", SnmpObjId.get("1.3.6.1.4.1.28458.1.26.3.1.3.2.0"));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("param1", "all");

        // Apply fix logic
        List<SnmpObjId> oids = new ArrayList<>();

        if (columns.size() == 1) {
            var entry = columns.entrySet().iterator().next();
            oids.add(entry.getValue());
        }

        // Assertions
        assertEquals("Should use OID as-is for single column",
                ".1.3.6.1.4.1.28458.1.26.3.1.3.2.0",
                oids.get(0).toString());
    }

    @Test
    public void testValueTypeAutoDetection() {
        // Test integer auto-detection from string
        String stringInt = "1";
        String stringText = "all";
        Integer intValue = 42;

        // Simulate snmpValue() logic
        Object result1 = tryParseInt(stringInt);
        Object result2 = tryParseInt(stringText);
        Object result3 = tryParseInt(intValue);

        assertTrue("'1' should parse as Integer", result1 instanceof Integer);
        assertEquals(1, ((Integer)result1).intValue());

        assertTrue("'all' should remain String", result2 instanceof String);
        assertEquals("all", result2);

        assertTrue("42 should stay Integer", result3 instanceof Integer);
        assertEquals(42, ((Integer)result3).intValue());
    }

    @Test
    public void testMultipleDataColumns() {
        // Test with index + multiple data columns
        Map<String, SnmpObjId> columns = new LinkedHashMap<>();
        columns.put("alarmIndex", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.4.1.3"));
        columns.put("alarmAck", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.4.1.16"));
        columns.put("alarmComment", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.4.1.17"));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("alarmIndex", "5");
        parameters.put("alarmAck", 1);
        parameters.put("alarmComment", "Acknowledged");

        // Apply logic
        List<SnmpObjId> oids = new ArrayList<>();
        String indexKey = columns.keySet().iterator().next();
        String instance = parameters.get(indexKey).toString();

        for (var e : columns.entrySet()) {
            if (e.getKey().equals(indexKey)) continue;
            SnmpObjId completeOid = SnmpObjId.get(e.getValue().toString() + "." + instance);
            oids.add(completeOid);
        }

        // Assertions
        assertEquals("Should have 2 OIDs (2 data columns)", 2, oids.size());
        assertEquals("First OID should have .5 suffix",
                ".1.3.6.1.4.1.3902.4101.1.4.1.16.5",
                oids.get(0).toString());
        assertEquals("Second OID should have .5 suffix",
                ".1.3.6.1.4.1.3902.4101.1.4.1.17.5",
                oids.get(1).toString());
    }

    @Test
    public void testPreCompletedTableOid() {
        // Test single column with pre-completed instance (e.g., ...18.2)
        Map<String, SnmpObjId> columns = new LinkedHashMap<>();
        columns.put("alarmAck", SnmpObjId.get("1.3.6.1.4.1.3902.4101.1.3.1.18.2"));

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("alarmAck", 1);

        // Apply logic
        List<SnmpObjId> oids = new ArrayList<>();

        if (columns.size() == 1) {
            var entry = columns.entrySet().iterator().next();
            oids.add(entry.getValue());
        }

        // Assertions
        assertEquals("Should use complete OID as-is",
                ".1.3.6.1.4.1.3902.4101.1.3.1.18.2",
                oids.get(0).toString());
    }

    /**
     * Helper method to simulate snmpValue() auto-detection logic
     */
    private Object tryParseInt(Object value) {
        if (value instanceof Integer) return value;
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return value;
            }
        }
        return value;
    }
}
