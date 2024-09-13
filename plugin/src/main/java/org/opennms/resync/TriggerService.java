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

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.opennms.resync.constants.Events.EVENT_SOURCE;
import static org.opennms.resync.constants.Events.UEI_RESYNC_ALARM;
import static org.opennms.resync.constants.Events.UEI_RESYNC_FINISHED;
import static org.opennms.resync.constants.Events.UEI_RESYNC_STARTED;
import static org.opennms.resync.constants.MIB.OID_CURRENT_ALARM_TABLE_ALARM_ID;
import static org.opennms.resync.constants.MIB.OID_CURRENT_ALARM_TABLE_EVENT_TIME;
import static org.opennms.resync.constants.MIB.OID_CURRENT_ALARM_TABLE_EVENT_TYPE;
import static org.opennms.resync.constants.MIB.OID_CURRENT_ALARM_TABLE_PROBLEM_CAUSE;

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

    public Future<Void> trigger(final Request request) {
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

        switch (request.mode) {
            case GET: {
                this.snmpClient.walk(agent, new AlarmTableTracker())
                        .execute()
                        .thenAccept(tracker -> {
                            this.eventForwarder.sendNow(new EventBuilder()
                                    .setTime(new Date())
                                    .setSource(EVENT_SOURCE)
                                    .setUei(UEI_RESYNC_STARTED)
                                    .setNodeid(node.getId())
                                    .setInterface(iface.getIpAddress())
                                    .getEvent());

                            for (final var alarm : tracker.alarms) {
                                this.eventForwarder.sendNow(new EventBuilder()
                                        .setTime(new Date())
                                        .setSource(EVENT_SOURCE)
                                        .setUei(UEI_RESYNC_ALARM)
                                        .setNodeid(node.getId())
                                        .setInterface(iface.getIpAddress())
                                        .getEvent());

                                // TODO: Add alarm data
                            }

                            this.eventForwarder.sendNow(new EventBuilder()
                                    .setTime(new Date())
                                    .setSource(EVENT_SOURCE)
                                    .setUei(UEI_RESYNC_FINISHED)
                                    .setNodeid(node.getId())
                                    .setInterface(iface.getIpAddress())
                                    .getEvent());

                            result.complete(null);
                        });

                break;
            }

            case SET: {
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

                break;
            }
        }

        return result;
    }

    @Value
    @Builder
    public static class Request {
        @NonNull
        String nodeCriteria;

        @Builder.Default
        InetAddress ipInterface = null;

        @NonNull
        Mode mode;

        @NonNull
        @Builder.Default
        Map<SnmpObjId, SnmpValue> attrs = new HashMap<>();
    }

    public enum Mode {
        GET, SET
    }

    private static class AlarmTableTracker extends TableTracker {
        // TODO: Convert from row to alarm model using mapstruct?

        private final static SnmpObjId[] CURRENT_ALARM_TABLE_ELEMENTS = new SnmpObjId[]{
                OID_CURRENT_ALARM_TABLE_ALARM_ID,
                OID_CURRENT_ALARM_TABLE_EVENT_TIME,
                OID_CURRENT_ALARM_TABLE_EVENT_TYPE,
                OID_CURRENT_ALARM_TABLE_PROBLEM_CAUSE,
        };

        public List<org.opennms.resync.proto.Resync.Alarm> alarms = new ArrayList<>();

        public AlarmTableTracker() {
            super(CURRENT_ALARM_TABLE_ELEMENTS);
        }

        @Override
        public void rowCompleted(SnmpRowResult row) {
            super.rowCompleted(row);

            this.alarms.add(org.opennms.resync.proto.Resync.Alarm.newBuilder()
                    .setId(row.getValue(OID_CURRENT_ALARM_TABLE_ALARM_ID).toLong())
                    // TODO: Convert more properties
                    .build());
        }
    }

    private static void extractMetaDataAttrs(final List<MetaData> metaData,
                                             final HashMap<SnmpObjId, SnmpValue> attrs) {
        for (final var e: metaData) {
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
}
