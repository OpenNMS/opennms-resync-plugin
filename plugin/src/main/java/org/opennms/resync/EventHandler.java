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

import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.opennms.integration.api.v1.events.EventForwarder;
import org.opennms.integration.api.v1.events.EventListener;
import org.opennms.integration.api.v1.events.EventSubscriptionService;
import org.opennms.integration.api.v1.model.InMemoryEvent;
import org.opennms.integration.api.v1.model.immutables.ImmutableInMemoryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class EventHandler implements EventListener {

    private static final Logger LOG = LoggerFactory.getLogger(EventHandler.class);

    private static final Timer TIMER = new Timer();

    public static final String UEI_RESYNC_STARTED = "uei.opennms.org/resync/started";
    public static final String UEI_RESYNC_FINISHED = "uei.opennms.org/resync/finished";
    public static final String UEI_RESYNC_TIMEOUT = "uei.opennms.org/resync/timeout";
    public static final String UEI_RESYNC_ALARM = "uei.opennms.org/resync/alarm";

    private static final List<String> UEIS = List.of(
            UEI_RESYNC_STARTED,
            UEI_RESYNC_FINISHED,
            UEI_RESYNC_TIMEOUT,
            UEI_RESYNC_ALARM
    );

    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(30);

    @NonNull
    private final EventSubscriptionService eventSubscriptionService;

    @NonNull
    private final EventForwarder eventForwarder;

    @NonNull
    private final AlarmForwarder alarmForwarder;

    private TimerTask timer;

    private final Map<Source, Session> sessions = new ConcurrentHashMap<>();

    public void start() {
        assert this.timer == null;
        TIMER.scheduleAtFixedRate(this.timer = this.timer(), 0, 1000);

        this.eventSubscriptionService.addEventListener(this, UEIS);
    }

    public void stop() {
        this.eventSubscriptionService.removeEventListener(this, UEIS);

        assert this.timer != null;
        this.timer.cancel();
    }

    @Override
    public String getName() {
        return "resync-event-handler";
    }

    @Override
    public int getNumThreads() {
        return 10;
    }

    @Override
    public synchronized void onEvent(final InMemoryEvent event) {
        final var source = new Source(event.getNodeId(), event.getInterface());

        // Dispatch event based on UEI
        switch (event.getUei()) {
            case UEI_RESYNC_STARTED -> this.onStarted(source, event);
            case UEI_RESYNC_FINISHED -> this.onFinished(source, event);
            case UEI_RESYNC_TIMEOUT -> this.onTimeout(source, event);
            case UEI_RESYNC_ALARM -> this.onAlarm(source, event);
            default -> LOG.warn("Unknown UEI: {}", event.getUei());
        }
    }

    private synchronized void onStarted(final Source source, final InMemoryEvent event) {
        if (this.sessions.containsKey(source)) {
            LOG.warn("Duplicate session: {}", source);
            return;
        }

        this.sessions.put(source, new Session());
        this.

        LOG.info("Resync session started: {}", source);
    }

    private synchronized void onFinished(final Source source, final InMemoryEvent event) {
        if (!this.sessions.containsKey(source)) {
            LOG.warn("Unknown session: {}", source);
            return;
        }

        final var session = this.sessions.remove(source);

        LOG.info("Resync session completed: {}", source);
    }

    private synchronized void onTimeout(final Source source, final InMemoryEvent event) {
        if (!this.sessions.containsKey(source)) {
            LOG.warn("Unknown session: {}", source);
            return;
        }

        final var session = this.sessions.remove(source);

        LOG.warn("Resync session timeout: {}", source);
    }

    private synchronized void onAlarm(final Source source, final InMemoryEvent event) {
        if (!this.sessions.containsKey(source)) {
            LOG.warn("Unknown session: {}", source);
            return;
        }

        final var session = this.sessions.get(source);
        session.lastEvent = Instant.now();
    }

    @Data
    private static class Source {
        private final Integer nodeId;
        private final InetAddress iface;
    }

    private static class Session {
        private Instant lastEvent = Instant.now();
    }

    private TimerTask timer() {
        return new TimerTask() {
            @Override
            public void run() {
                synchronized (EventHandler.this) {
                    final var timeout = Instant.now().minus(SESSION_TIMEOUT);

                    for (final var session : EventHandler.this.sessions.entrySet()) {
                        if (session.getValue().lastEvent.isBefore(timeout)) {
                            final var event = ImmutableInMemoryEvent.newBuilder()
                                    .setUei(UEI_RESYNC_TIMEOUT)
                                    .setNodeId(session.getKey().getNodeId())
                                    .setInterface(session.getKey().getIface())
                                    .build();
                            EventHandler.this.eventForwarder.sendAsync(event);
                        }
                    }
                }
            }
        };
    }
}
