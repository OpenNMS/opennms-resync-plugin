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

import com.google.common.net.InetAddresses;
import org.opennms.netmgt.config.api.SnmpAgentConfigFactory;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.netmgt.snmp.SnmpRowResult;
import org.opennms.netmgt.snmp.TableTracker;
import org.opennms.netmgt.snmp.proxy.LocationAwareSnmpClient;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class TriggerService {
    // TODO: Maintain a global table of locks to track which system is in progress and disallow multiple concurring re-syncs

    private final LocationAwareSnmpClient snmpClient;
    private final SnmpAgentConfigFactory snmpAgentConfigFactory;

    private final StateForwarder stateForwarder;

    public TriggerService(final LocationAwareSnmpClient snmpClient,
                          final SnmpAgentConfigFactory snmpAgentConfigFactory,
                          final StateForwarder stateForwarder) {
        this.snmpClient = Objects.requireNonNull(snmpClient);
        this.snmpAgentConfigFactory = Objects.requireNonNull(snmpAgentConfigFactory);
        this.stateForwarder = Objects.requireNonNull(stateForwarder);
    }

    public Future<Void> trigger(final Request request) {
        final var agent = this.snmpAgentConfigFactory.getAgentConfig(request.host, request.location);
        // TODO: Error handling?

        final var result = new CompletableFuture<Void>();

        switch (request.mode) {
            case GET -> {

                // TODO: Take a walk on the wild side
                this.snmpClient.walk(agent, new AlarmTableTracker())
                        .execute()
                        .thenAccept(tracker -> {
                            this.stateForwarder.postStart();

                            for (final var alarm : tracker.alarms) {
                                this.stateForwarder.postAlarm(alarm);
                            }

                            this.stateForwarder.postStop();

                            result.complete(null);
                        });
            }

            case SET -> {
                // TODO: Oh boy - send a set command
                // TODO: Register a timer for timeout handling
                // TODO: Do something with the incoming events here or have a separate event handler for the tracking?
            }
        }

        return result;
    }

    public static class Request {
        public final String location;
        public final InetAddress host;
        public final Mode mode;

        private Request(final Builder builder) {
            this.location = Objects.requireNonNull(builder.location);
            this.host = InetAddresses.forString(Objects.requireNonNull(builder.host));
            this.mode = Objects.requireNonNull(builder.mode);
        }

        public static class Builder {
            private String location;
            private String host;
            private Mode mode = Mode.GET;

            private Builder() {
            }

            public Builder location(final String location) {
                this.location = location;
                return this;
            }

            public Builder host(final String host) {
                this.host = host;
                return this;
            }

            public Builder mode(final Mode mode) {
                this.mode = mode;
                return this;
            }

            public Request build() {
                return new Request(this);
            }
        }

        public static Builder builder() {
            return new Builder();
        }

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

        private final static SnmpObjId[] CURRENT_ALARM_TABLE_ELEMENTS = new SnmpObjId[] {
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
