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
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.opennms.netmgt.events.api.EventForwarder;
import org.opennms.netmgt.events.api.EventListener;
import org.opennms.netmgt.events.api.EventSubscriptionService;
import org.opennms.netmgt.events.api.model.IEvent;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.resync.proto.Resync;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import static org.opennms.resync.constants.Events.EVENT_SOURCE;
import static org.opennms.resync.constants.Events.UEI_RESYNC_ALARM;
import static org.opennms.resync.constants.Events.UEI_RESYNC_FINISHED;
import static org.opennms.resync.constants.Events.UEI_RESYNC_STARTED;
import static org.opennms.resync.constants.Events.UEI_RESYNC_TIMEOUT;

@RequiredArgsConstructor
@Slf4j
public class EventHandler implements EventListener {

    private static final Timer TIMER = new Timer();

    private static final List<String> UEIS = List.of(
            UEI_RESYNC_STARTED,
            UEI_RESYNC_FINISHED,
            UEI_RESYNC_TIMEOUT,
            UEI_RESYNC_ALARM
    );

    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(10);

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
    public synchronized void onEvent(final IEvent event) {
        final var source = new Source(event.getNodeid(), event.getInterfaceAddress());

        // Dispatch event based on UEI
        switch (event.getUei()) {
            case UEI_RESYNC_STARTED -> this.onStarted(source, event);
            case UEI_RESYNC_FINISHED -> this.onFinished(source, event);
            case UEI_RESYNC_TIMEOUT -> this.onTimeout(source, event);
            case UEI_RESYNC_ALARM -> this.onAlarm(source, event);
            default -> log.warn("Unknown UEI: {}", event.getUei());
        }
    }

    private synchronized void onStarted(final Source source, final IEvent event) {
        if (this.sessions.containsKey(source)) {
            log.warn("onStart: duplicate session: {}", source);
            return;
        }

        this.sessions.put(source, new Session());

        log.info("resync session {}: started", source);

        this.alarmForwarder.postStart(source.nodeId);
    }

    private synchronized void onFinished(final Source source, final IEvent event) {
        if (!this.sessions.containsKey(source)) {
            log.warn("onFinished: unknown session: {}", source);
        }

        final var session = this.sessions.remove(source);

        log.info("resync session {}: completed", source);

        this.alarmForwarder.postEnd(source.nodeId, true);
    }

    private synchronized void onTimeout(final Source source, final IEvent event) {
        if (!this.sessions.containsKey(source)) {
            log.warn("onTimeout: unknown session: {}", source);
            return;
        }

        final var session = this.sessions.remove(source);

        log.warn("resync session {}: timeout", source);

        this.alarmForwarder.postEnd(source.nodeId, false);
    }

    private synchronized void onAlarm(final Source source, final IEvent event) {
        if (!this.sessions.containsKey(source)) {
            log.info("onAlarm: unknown session - starting new one: {}", source);
            this.sessions.put(source, new Session());
            return;
        }

        final var session = this.sessions.get(source);
        session.lastEvent = Instant.now();

        log.info("resync session {}: alarm - {}", source, event);

        final var alarm = Resync.Alarm.newBuilder();
        alarm.setUei(event.getUei());
        alarm.setNodeCriteria(Resync.NodeCriteria.newBuilder()
                        .setId(event.getNodeid())
                // TODO: Lookup node to provide more node info
        );
        alarm.setIpAddress(event.getInterface());
        // TODO: alarm.setServiceName(event.getService());
        // TODO: alarm.setReductionKey()
        // TODO: alarm.setSeverity(event.getSeverity())
        alarm.setFirstEventTime(event.getTime().getTime());
        alarm.setDescription(event.getDescr() != null ? event.getDescr() : "");
        alarm.setLogMessage(event.getLogmsg() != null && event.getLogmsg().getContent() != null ? event.getLogmsg().getContent() : "");
        alarm.setLastEventTime(event.getTime().getTime());
        // alarm.setIfIndex()

        this.alarmForwarder.postAlarm(alarm.build());
    }

    @Value
    private static class Source {
        Long nodeId;
        InetAddress iface;
    }

    @Data
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
                            EventHandler.this.eventForwarder.sendNow(
                                    new EventBuilder()
                                            .setTime(new Date())
                                            .setSource(EVENT_SOURCE)
                                            .setUei(UEI_RESYNC_TIMEOUT)
                                            .setNodeid(session.getKey().getNodeId())
                                            .setInterface(session.getKey().getIface())
                                            .getEvent());

                            EventHandler.this.sessions.remove(session.getKey());
                        }
                    }
                }
            }
        };
    }
}
