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

import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.integration.api.v1.dao.NodeDao;
import org.opennms.integration.api.v1.model.Node;
import org.opennms.netmgt.config.api.SnmpAgentConfigFactory;
import org.opennms.netmgt.events.api.EventForwarder;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.netmgt.snmp.SnmpRowResult;
import org.opennms.netmgt.snmp.SnmpValue;
import org.opennms.netmgt.snmp.TableTracker;
import org.opennms.netmgt.snmp.proxy.LocationAwareSnmpClient;
import org.opennms.netmgt.snmp.snmp4j.Snmp4JValueFactory;
import org.opennms.resync.config.ActionConfigs;
import org.opennms.resync.config.ActionType;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.opennms.resync.constants.Events.EVENT_SOURCE;
import static org.opennms.resync.constants.Events.UEI_ACTION_RESPONSE;

@Slf4j
@RequiredArgsConstructor
public class ActionService {

    @NonNull
    private final LocationAwareSnmpClient snmpClient;

    @NonNull
    private final SnmpAgentConfigFactory snmpAgentConfigFactory;

    @NonNull
    private final EventForwarder eventForwarder;

    @NonNull
    private final NodeDao nodeDao;

    @NonNull
    private final ActionConfigs actionConfigs;

    @Value
    @Builder
    public static class Request {
        @NonNull
        String nodeCriteria;

        @Builder.Default
        InetAddress ipInterface = null;

        @NonNull
        String actionId;

        String kind;

        @NonNull
        ActionType actionType;

        @NonNull
        @Builder.Default
        Map<String, Object> parameters = new HashMap<>();

        @Builder.Default
        RequestType requestType = RequestType.SET;
    }

    public enum RequestType {
        SET,
        GET
    }

    public Future<Map<String, Object>> executeAction(final Request request) throws IOException {
        final var node = this.findNode(request.nodeCriteria);

        final var config = this.actionConfigs.getActionConfig(node.getLabel(), request.kind, request.actionType);

        switch (request.requestType) {
            case SET: return this.executeSet(request, node, config);
            case GET: return this.executeGet(request, node, config);
            default: throw new IllegalStateException("Unsupported request type: " + request.requestType);
        }
    }

    private Future<Map<String, Object>> executeSet(final Request request, final Node node, final ActionConfigs.Entry config) throws IOException {
        log.info("executeAction: SET: action={}, node={}, actionId={}", request.actionType, node.getLabel(), request.actionId);

        final var iface = (request.ipInterface != null
                ? node.getInterfaceByIp(request.ipInterface)
                : node.getIpInterfaces().stream().findFirst())
                .orElseThrow(() -> new NoSuchElementException("Requested interface not found on node"));

        final var agent = this.snmpAgentConfigFactory.getAgentConfig(iface.getIpAddress(), node.getLocation());

        final var parameters = new HashMap<String, Object>();
        parameters.putAll(config.getParameters());
        parameters.putAll(request.getParameters());

        final var result = new CompletableFuture<Map<String, Object>>();

        // Resolve all columns to attributes
        final var oids = new ArrayList<SnmpObjId>(config.getColumns().size());
        final var vals = new ArrayList<SnmpValue>(config.getColumns().size());

        for (final var e : config.getColumns().entrySet()) {
            var value = parameters.get(e.getKey());
            if (value == null) {
                throw new IllegalArgumentException("No value defined for parameter: " + e.getKey());
            }

            oids.add(e.getValue());
            vals.add(ActionMapper.INSTANCE.snmpValue(value));
        }

        final var response = this.snmpClient.set(agent, oids.toArray(SnmpObjId[]::new), vals.toArray(SnmpValue[]::new))
                .withLocation(node.getLocation())
                .execute();

        response.whenComplete((ok, ex) -> {
            if (ex != null) {
                log.error("Action SET failed: action={}, node={}, actionId={}", request.actionType, node.getLabel(), request.actionId, ex);
                result.completeExceptionally(ex);
            } else {
                log.info("Action SET completed: action={}, node={}, actionId={}", request.actionType, node.getLabel(), request.actionId);
                final Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("status", "success");
                resultMap.put("actionId", request.actionId);
                resultMap.put("actionType", request.actionType.name());
                result.complete(resultMap);
            }
        });

        return result;
    }

    private Future<Map<String, Object>> executeGet(final Request request, final Node node, final ActionConfigs.Entry config) throws IOException {
        log.info("executeAction: GET: action={}, node={}, actionId={}", request.actionType, node.getLabel(), request.actionId);

        final var iface = (request.ipInterface != null
                ? node.getInterfaceByIp(request.ipInterface)
                : node.getIpInterfaces().stream().findFirst())
                .orElseThrow(() -> new NoSuchElementException("Requested interface not found on node"));

        final var agent = this.snmpAgentConfigFactory.getAgentConfig(iface.getIpAddress(), node.getLocation());

        final var parameters = new HashMap<String, Object>();
        parameters.putAll(config.getParameters());
        parameters.putAll(request.getParameters());

        return this.snmpClient.walk(agent, new ActionTableTracker(config))
                .withDescription("action-get")
                .withLocation(node.getLocation())
                .execute()
                .thenApply(tracker -> {
                    log.info("Action GET walk completed: action={}, node={}, actionId={}, rows={}",
                            request.actionType, node.getLabel(), request.actionId, tracker.results.size());

                    // Generate event for each result row
                    for (final var result : tracker.results) {
                        final var event = new EventBuilder()
                                .setTime(new Date())
                                .setSource(EVENT_SOURCE)
                                .setUei(UEI_ACTION_RESPONSE)
                                .setNodeid(node.getId())
                                .setInterface(iface.getIpAddress())
                                .setService(config.getKind());

                        event.addParam("actionId", request.actionId);
                        event.addParam("actionType", request.actionType.name());

                        // Apply columns from SNMP walk
                        for (final var key : config.getColumns().keySet()) {
                            event.addParam(key, result.get(key));
                        }

                        // Apply parameters
                        parameters.forEach((k, v) -> event.addParam(k, v.toString()));

                        this.eventForwarder.sendNowSync(event.getEvent());
                    }

                    final Map<String, Object> resultMap = new HashMap<>();
                    resultMap.put("status", "success");
                    resultMap.put("actionId", request.actionId);
                    resultMap.put("actionType", request.actionType.name());
                    resultMap.put("rowCount", tracker.results.size());
                    return resultMap;
                });
    }

    private Node findNode(final String nodeCriteria) {
        Node node;

        node = this.nodeDao.getNodeByLabel(nodeCriteria);
        if (node != null) {
            return node;
        }

        node = this.nodeDao.getNodeByCriteria(nodeCriteria);
        if (node != null) {
            return node;
        }

        throw new NoSuchElementException("No such node: " + nodeCriteria);
    }

    private class ActionTableTracker extends TableTracker {
        public List<Map<String, String>> results = new ArrayList<>();

        private final ActionConfigs.Entry config;

        public ActionTableTracker(final ActionConfigs.Entry config) {
            super(config.getColumns().values().toArray(SnmpObjId[]::new));

            this.config = config;
        }

        @Override
        public void rowCompleted(final SnmpRowResult row) {
            super.rowCompleted(row);

            final var result = this.config.getColumns()
                    .entrySet().stream()
                    .map(e -> {
                        final var value = row.getValue(e.getValue());
                        if (value == null) {
                            return null;
                        }

                        return new AbstractMap.SimpleImmutableEntry<>(e.getKey(), value.toDisplayString());
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            this.results.add(result);
        }
    }

    @Mapper
    public interface ActionMapper {
        ActionMapper INSTANCE = Mappers.getMapper(ActionMapper.class);

        default InetAddress inetAddress(final String ipAddress) {
            return InetAddressUtils.addr(ipAddress);
        }

        default SnmpObjId snmpObjId(final String oid) {
            return SnmpObjId.get(oid);
        }

        default SnmpValue snmpValue(final Object value) {
            if (value instanceof String) {
                return new Snmp4JValueFactory().getOctetString(((String) value).getBytes(StandardCharsets.UTF_8));
            } else if (value instanceof Integer) {
                return new Snmp4JValueFactory().getInt32(((Integer) value));
            } else {
                throw new IllegalArgumentException("Unsupported SNMP value type: " + value.getClass());
            }
        }
    }
}