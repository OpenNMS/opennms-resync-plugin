package org.opennms.resync;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.config.api.SnmpAgentConfigFactory;
import org.opennms.netmgt.snmp.proxy.LocationAwareSnmpClient;

import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class TriggerService {
    // TODO: Maintain a global table of locks to track which system is in progress and disallow multiple concurring re-syncs

    private final LocationAwareSnmpClient snmpClient;
    private final SnmpAgentConfigFactory snmpAgentConfigFactory;

    public TriggerService(final LocationAwareSnmpClient snmpClient, SnmpAgentConfigFactory snmpAgentConfigFactory) {
        this.snmpClient = Objects.requireNonNull(snmpClient);
        this.snmpAgentConfigFactory = Objects.requireNonNull(snmpAgentConfigFactory);
    }

    public Future<Void> trigger(final Request request) {
        final var agent = this.snmpAgentConfigFactory.getAgentConfig(request.host, request.location);
        // TODO: Error handling?

        final var result = new CompletableFuture<Void>();

        switch (request.mode) {
            case GET -> {
                this.postStart();

                // TODO: Take a walk on the wild side
                // this.snmpClient.walk(agent, ...)

                this.postStop();
            }

            case SET -> {
                // TODO: Oh boy - send a set command
                // TODO: Register a timer for timeout handling
                // TODO: Do something with the incoming events here?
            }
        }

        return result;
    }

    private void postStart() {

    }

    private void postStop() {

    }

    private void postStatus() {

    }

    public static class Request {
        public final String location;
        public final InetAddress host;
        public final Mode mode;

        private Request(final Builder builder) {
            this.location = Objects.requireNonNull(builder.location);
            this.host = InetAddressUtils.addr(Objects.requireNonNull(builder.host));
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
}
