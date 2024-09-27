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
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.integration.api.v1.dao.NodeDao;
import org.opennms.integration.api.v1.model.MetaData;
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
import org.opennms.resync.config.GetConfig;

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

@RequiredArgsConstructor
public class TriggerService {
    // TODO: Maintain a global table of locks to track which system is in progress and disallow multiple concurring re-syncs

    public final static String META_DATA_PREFIX = "resync:";

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
    public static class SetRequest {
        @NonNull
        String nodeCriteria;

        @Builder.Default
        InetAddress ipInterface = null;

        @NonNull
        String sessionId;

        @NonNull
        @Builder.Default
        Map<SnmpObjId, SnmpValue> attrs = new HashMap<>();
    }

    public Future<Void> set(final SetRequest request) throws IOException {
        final var node = this.nodeDao.getNodeByCriteria(request.getNodeCriteria());
        if (node == null) {
            throw new NoSuchElementException("No such node: " + request.nodeCriteria);
        }

        final var iface = (request.ipInterface != null
                ? node.getInterfaceByIp(request.ipInterface)
                : node.getIpInterfaces().stream().findFirst())
                .orElseThrow(() -> new NoSuchElementException("Requested interface not found on node"));

        final var agent = this.snmpAgentConfigFactory.getAgentConfig(iface.getIpAddress(), node.getLocation());
        // TODO: Error handling?

        final var result = new CompletableFuture<Void>();

        this.eventHandler.createSession(EventHandler.Source.builder()
                        .nodeId(node.getId().longValue())
                        .iface(iface.getIpAddress())
                        .build(),
                request.sessionId);
        // TODO: This excepts on duplicate session? Should we wait?

        this.eventForwarder.sendNow(new EventBuilder()
                .setTime(new Date())
                .setSource(EVENT_SOURCE)
                .setUei(UEI_RESYNC_STARTED)
                .setNodeid(node.getId())
                .setInterface(iface.getIpAddress())
                .getEvent());

        final var attrs = new HashMap<>(request.attrs);
        extractMetaDataAttrs(node.getMetaData(), attrs);
        extractMetaDataAttrs(iface.getMetaData(), attrs);

        // The following two arrays are co-indexed
        final var oids = request.attrs.entrySet().stream().map(Map.Entry::getKey).toArray(SnmpObjId[]::new);
        final var vals = request.attrs.entrySet().stream().map(Map.Entry::getValue).toArray(SnmpValue[]::new);

        final var response = this.snmpClient.set(agent, oids, vals)
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

    @Value
    @Builder
    public static class GetRequest {
        @NonNull
        String nodeCriteria;

        @Builder.Default
        InetAddress ipInterface = null;

        @NonNull
        String sessionId;

        @NonNull
        String kind;
    }

    public Future<Void> get(final GetRequest request) throws IOException {
        final var node = this.nodeDao.getNodeByCriteria(request.getNodeCriteria());
        if (node == null) {
            throw new NoSuchElementException("No such node: " + request.nodeCriteria);
        }

        final var iface = (request.ipInterface != null
                ? node.getInterfaceByIp(request.ipInterface)
                : node.getIpInterfaces().stream().findFirst())
                .orElseThrow(() -> new NoSuchElementException("Requested interface not found on node"));

        final var config = this.configs.getConfig(request.kind);
        if (config == null) {
            throw new IllegalArgumentException("Unknown kind: " + request.kind);
        }

        final var agent = this.snmpAgentConfigFactory.getAgentConfig(iface.getIpAddress(), node.getLocation());
        // TODO: Error handling?

        this.eventHandler.createSession(EventHandler.Source.builder()
                        .nodeId(node.getId().longValue())
                        .iface(iface.getIpAddress())
                        .build(),
                request.sessionId);
        // TODO: This excepts on duplicate session? Should we wait?

        return this.snmpClient.walk(agent, new AlarmTableTracker(config))
                .withDescription("resync-get")
                .execute()
                .thenAccept(tracker -> {
                    this.eventForwarder.sendNow(new EventBuilder()
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
                                .setInterface(iface.getIpAddress());

                        // Apply columns
                        for (final var key : config.getColumns().keySet()) {
                            event.addParam(key, result.get(key));
                        }

                        // Apply parameters
                        for (final var e : config.getParameters().entrySet()) {
                            event.addParam(e.getKey(), e.getValue());
                        }

                        TriggerService.this.eventForwarder.sendNow(event.getEvent());
                    }

                    this.eventForwarder.sendNow(new EventBuilder()
                            .setTime(new Date())
                            .setSource(EVENT_SOURCE)
                            .setUei(UEI_RESYNC_FINISHED)
                            .setNodeid(node.getId())
                            .setInterface(iface.getIpAddress())
                            .getEvent());
                });
    }

    private class AlarmTableTracker extends TableTracker {
        public List<Map<String, String>> results = new ArrayList<>();

        private final GetConfig config;

        public AlarmTableTracker(final GetConfig config) {
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

    private static void extractMetaDataAttrs(final List<MetaData> metaData,
                                             final HashMap<SnmpObjId, SnmpValue> attrs) {
        for (final var e : metaData) {
            if (!e.getContext().equals("requisition")) {
                continue;
            }

            if (!e.getKey().startsWith(META_DATA_PREFIX)) {
                continue;
            }

            final var key = e.getKey().substring(META_DATA_PREFIX.length());
            final var oid = SnmpObjId.get(key);

            final var val = new Snmp4JValueFactory().getOctetString(e.getValue().getBytes(StandardCharsets.UTF_8));

            attrs.put(oid, val);
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

        default SnmpValue snmpValue(final String value) {
            return new Snmp4JValueFactory().getOctetString(value.getBytes(StandardCharsets.UTF_8));
        }

        Map<SnmpObjId, SnmpValue> attrs(final Map<String, String> attrs);
    }
}
