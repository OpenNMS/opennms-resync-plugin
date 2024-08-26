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
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.opennms.integration.api.v1.dao.InterfaceToNodeCache;
import org.opennms.integration.api.v1.events.EventForwarder;
import org.opennms.integration.api.v1.model.immutables.ImmutableInMemoryEvent;
import org.opennms.netmgt.config.api.SnmpAgentConfigFactory;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.netmgt.snmp.SnmpRowResult;
import org.opennms.netmgt.snmp.TableTracker;
import org.opennms.netmgt.snmp.proxy.LocationAwareSnmpClient;
import org.opennms.netmgt.snmp.snmp4j.Snmp4JValueFactory;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@RequiredArgsConstructor
public class TriggerService {
    // TODO: Maintain a global table of locks to track which system is in progress and disallow multiple concurring re-syncs

    private final static SnmpObjId OID_NBI_GET_ACTIVE_ALARMS = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.3.2.0");

    @NonNull
    private final LocationAwareSnmpClient snmpClient;

    @NonNull
    private final SnmpAgentConfigFactory snmpAgentConfigFactory;

    @NonNull
    private final EventForwarder eventForwarder;

    @NonNull
    private final InterfaceToNodeCache interfaceToNodeCache;

    public Future<Void> trigger(final Request request) {
        final var nodeId = this.interfaceToNodeCache.getFirstNodeId(request.location, request.host)
                .orElseThrow(() -> new NoSuchElementException("No such node: " + request.host + " at " + request.location));

        final var agent = this.snmpAgentConfigFactory.getAgentConfig(request.host, request.location);
        // TODO: Error handling?

        final var result = new CompletableFuture<Void>();

        switch (request.mode) {
            case GET -> {
                this.snmpClient.walk(agent, new AlarmTableTracker())
                        .execute()
                        .thenAccept(tracker -> {
                            this.eventForwarder.sendSync(ImmutableInMemoryEvent.newBuilder()
                                    .setUei(EventHandler.UEI_RESYNC_STARTED)
                                    .setNodeId(nodeId)
                                    .setInterface(request.host)
                                    .build());

                            for (final var alarm : tracker.alarms) {
                                this.eventForwarder.sendSync(ImmutableInMemoryEvent.newBuilder()
                                        .setUei(EventHandler.UEI_RESYNC_ALARM)
                                        .setNodeId(nodeId)
                                        .setInterface(request.host)
                                        .build());

                                // TODO: Add alarm data
                            }

                            this.eventForwarder.sendSync(ImmutableInMemoryEvent.newBuilder()
                                    .setUei(EventHandler.UEI_RESYNC_FINISHED)
                                    .setNodeId(nodeId)
                                    .setInterface(request.host)
                                    .build());

                            result.complete(null);
                        });
            }

            case SET -> {
                final var response = this.snmpClient.set(agent,
                                OID_NBI_GET_ACTIVE_ALARMS,
                                new Snmp4JValueFactory().getOctetString("all".getBytes(StandardCharsets.UTF_8)))
                        .withLocation(request.location)
                        .execute();

                response.whenComplete((ok, ex) -> {
                    if (ex != null) {
                        result.completeExceptionally(ex);
                    } else {
                        result.complete(null);
                    }
                });
            }
        }

        return result;
    }

    @Data
    @Builder(builderClassName = "Builder")
    public static class Request {
        @NonNull
        private final String location;

        @NonNull
        private final InetAddress host;

        @NonNull
        private final Mode mode;

        //        private Request(final Builder builder) {
//            this.location = Objects.requireNonNull(builder.location);
//            this.host = InetAddresses.forString(Objects.requireNonNull(builder.host));
//            this.mode = Objects.requireNonNull(builder.mode);
//        }
//
        public static class Builder {
//            private String location;
//            private String host;
//            private Mode mode = Mode.GET;
//
//            private Builder() {
//            }
//
//            public Builder location(final String location) {
//                this.location = location;
//                return this;
//            }
//
//            public Builder host(final String host) {
//                this.host = host;
//                return this;
//            }
//
//            public Builder mode(final Mode mode) {
//                this.mode = mode;
//                return this;
//            }
//
//            public Request build() {
//                return new Request(this);
//            }
        }
//
//        public static Builder builder() {
//            return new Builder();
//        }

        public enum Mode {
            GET, SET
        }
    }

    private static class AlarmTableTracker extends TableTracker {
        // TODO: Can we somehow auto-generate this?
        // TODO: Convert from row to alarm model using mapstruct?

        private final static SnmpObjId CURRENT_ALARM_TABLE = SnmpObjId.get(".1.3.6.1.4.1.3902.4101.1.3");
        private final static SnmpObjId CURRENT_ALARM_TABLE_ALARM_ID = SnmpObjId.get(CURRENT_ALARM_TABLE, "1");
        private final static SnmpObjId CURRENT_ALARM_TABLE_EVENT_TIME = SnmpObjId.get(CURRENT_ALARM_TABLE, "3");
        private final static SnmpObjId CURRENT_ALARM_TABLE_EVENT_TYPE = SnmpObjId.get(CURRENT_ALARM_TABLE, "4");
        private final static SnmpObjId CURRENT_ALARM_TABLE_PROBLEM_CAUSE = SnmpObjId.get(CURRENT_ALARM_TABLE, "5");

        private final static SnmpObjId[] CURRENT_ALARM_TABLE_ELEMENTS = new SnmpObjId[]{
                CURRENT_ALARM_TABLE_ALARM_ID,
                CURRENT_ALARM_TABLE_EVENT_TIME,
                CURRENT_ALARM_TABLE_EVENT_TYPE,
                CURRENT_ALARM_TABLE_PROBLEM_CAUSE,
        };

        public List<org.opennms.resync.proto.Resync.Alarm> alarms = new ArrayList<>();

        public AlarmTableTracker() {
            super(CURRENT_ALARM_TABLE_ELEMENTS);
        }

        @Override
        public void rowCompleted(SnmpRowResult row) {
            super.rowCompleted(row);

            this.alarms.add(org.opennms.resync.proto.Resync.Alarm.newBuilder()
                    .setId(row.getValue(CURRENT_ALARM_TABLE_ALARM_ID).toLong())
                    // TODO: Convert more properties
                    .build());
        }
    }
}
