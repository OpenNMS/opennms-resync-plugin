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
import org.opennms.resync.config.Configs;
import org.opennms.resync.config.KindConfig;

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
import static org.opennms.resync.constants.Events.UEI_RESYNC_ALARM;
import static org.opennms.resync.constants.Events.UEI_RESYNC_FINISHED;
import static org.opennms.resync.constants.Events.UEI_RESYNC_STARTED;

@Slf4j
@RequiredArgsConstructor
public class TriggerService {
    // TODO: Maintain a global table of locks to track which system is in progress and disallow multiple concurring re-syncs

    @NonNull
    private final LocationAwareSnmpClient snmpClient;

    @NonNull
    private final SnmpAgentConfigFactory snmpAgentConfigFactory;

    @NonNull
    private final EventForwarder eventForwarder;

    @NonNull
    private final NodeDao nodeDao;

    @NonNull
    private final EventHandler eventHandler;

    @NonNull
    private final Configs configs;

    @Value
    @Builder
    public static class Request {
        @NonNull
        String nodeCriteria;

        @Builder.Default
        InetAddress ipInterface = null;

        @NonNull
        String sessionId;

        String kind;

        @NonNull
        @Builder.Default
        Map<String, Object> parameters = new HashMap<>();
    }

    public Future<Void> trigger(final Request request) throws IOException {
        final var node = this.findNode(request.nodeCriteria);

        final var config = this.configs.getConfig(node.getLabel(), request.kind);

        switch (config.getValue().getMode()) {
            case SET: return this.set(request, config);
            case GET: return this.get(request, config);
            default: throw new IllegalStateException("Unsupported mode: " + config.getValue().getMode());
        }
    }

    private Future<Void> set(final Request request, final Map.Entry<String, KindConfig> config) throws IOException {
        log.info("trigger: set: {}", request);

        final var node = this.findNode(request.nodeCriteria);

        final var iface = (request.ipInterface != null
                ? node.getInterfaceByIp(request.ipInterface)
                : node.getIpInterfaces().stream().findFirst())
                .orElseThrow(() -> new NoSuchElementException("Requested interface not found on node"));

        final var agent = this.snmpAgentConfigFactory.getAgentConfig(iface.getIpAddress(), node.getLocation());
        // TODO: Error handling?

        final var parameters = new HashMap<String, Object>();
        parameters.putAll(config.getValue().getParameters());
        parameters.putAll(request.getParameters());

        this.eventHandler.createSession(EventHandler.Source.builder()
                        .nodeId(node.getId().longValue())
                        .iface(iface.getIpAddress())
                        .build(),
                request.sessionId,
                parameters);
        // TODO: This excepts on duplicate session? Should we wait?

        this.eventForwarder.sendNowSync(new EventBuilder()
                .setTime(new Date())
                .setSource(EVENT_SOURCE)
                .setUei(UEI_RESYNC_STARTED)
                .setNodeid(node.getId())
                .setInterface(iface.getIpAddress())
                .getEvent());

        final var result = new CompletableFuture<Void>();

        // Resolve all columns to attributes
        // The following two arrays are co-indexed
        final var oids = new ArrayList<SnmpObjId>(config.getValue().getColumns().size());
        final var vals = new ArrayList<SnmpValue>(config.getValue().getColumns().size());

        for (final var e : config.getValue().getColumns().entrySet()) {
            var value = parameters.get(e.getKey());
            if (value == null) {
                throw new IllegalArgumentException("No value defined for parameter: " + e.getKey());
            }

            oids.add(e.getValue());
            vals.add(TriggerMapper.INSTANCE.snmpValue(value));
        }

        final var response = this.snmpClient.set(agent, oids.toArray(SnmpObjId[]::new), vals.toArray(SnmpValue[]::new))
                .withLocation(node.getLocation())
                .execute();

        response.whenComplete((ok, ex) -> {
            if (ex != null) {
                result.completeExceptionally(ex);
            } else {
                result.complete(null);
            }
        });

        return result;
    }

    private Future<Void> get(final Request request, final Map.Entry<String, KindConfig> config) throws IOException {
        log.info("trigger: get: {}", request);

        final var node = this.findNode(request.nodeCriteria);

        final var iface = (request.ipInterface != null
                ? node.getInterfaceByIp(request.ipInterface)
                : node.getIpInterfaces().stream().findFirst())
                .orElseThrow(() -> new NoSuchElementException("Requested interface not found on node"));

        final var agent = this.snmpAgentConfigFactory.getAgentConfig(iface.getIpAddress(), node.getLocation());
        // TODO: Error handling?

        final var parameters = new HashMap<String, Object>();
        parameters.putAll(config.getValue().getParameters());
        parameters.putAll(request.getParameters());


        // TODO: This excepts on duplicate session? Should we wait?

        return this.snmpClient.walk(agent, new AlarmTableTracker(config.getValue()))
                .withDescription("resync-get")
                .execute()
                .thenAccept(tracker -> {
                    // TODO: need better to create the session in get scenario
                    this.eventHandler.createSession(EventHandler.Source.builder()
                                    .nodeId(node.getId().longValue())
                                    .iface(iface.getIpAddress())
                                    .build(),
                            request.sessionId,
                            parameters);
                    // TODO: Is another way to check if we check the first result is received ? and then start session
                    this.eventForwarder.sendNowSync(new EventBuilder()
                            .setTime(new Date())
                            .setSource(EVENT_SOURCE)
                            .setUei(UEI_RESYNC_STARTED)
                            .setNodeid(node.getId())
                            .setInterface(iface.getIpAddress())
                            .getEvent());

                    // Figure out which columns exist
                    for (final var result : tracker.results) {
                        final var event = new EventBuilder()
                                .setTime(new Date())
                                .setSource(EVENT_SOURCE)
                                .setUei(UEI_RESYNC_ALARM)
                                .setNodeid(node.getId())
                                .setInterface(iface.getIpAddress())
                                .setService(config.getKey());

                        // Apply columns
                        for (final var key : config.getValue().getColumns().keySet()) {
                            event.addParam(key, result.get(key));
                        }

                        // Apply parameters
                        parameters.forEach((k, v) -> event.addParam(k, v.toString()));

                        TriggerService.this.eventForwarder.sendNowSync(event.getEvent());
                    }

                    this.eventForwarder.sendNowSync(new EventBuilder()
                            .setTime(new Date())
                            .setSource(EVENT_SOURCE)
                            .setUei(UEI_RESYNC_FINISHED)
                            .setNodeid(node.getId())
                            .setInterface(iface.getIpAddress())
                            .getEvent());
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

    private class AlarmTableTracker extends TableTracker {
        public List<Map<String, String>> results = new ArrayList<>();

        private final KindConfig config;

        public AlarmTableTracker(final KindConfig config) {
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
    public interface TriggerMapper {
        TriggerMapper INSTANCE = Mappers.getMapper(TriggerMapper.class);

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
