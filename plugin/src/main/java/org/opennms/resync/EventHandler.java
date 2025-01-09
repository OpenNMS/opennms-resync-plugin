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

import com.google.common.collect.Maps;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.opennms.netmgt.events.api.EventForwarder;
import org.opennms.netmgt.events.api.EventListener;
import org.opennms.netmgt.events.api.EventSubscriptionService;
import org.opennms.netmgt.events.api.model.IAlarmData;
import org.opennms.netmgt.events.api.model.IEvent;
import org.opennms.netmgt.events.api.model.IParm;
import org.opennms.netmgt.events.api.model.IValue;
import org.opennms.netmgt.model.events.EventBuilder;
import org.opennms.resync.proto.Resync;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Function;

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

//    public final Map<Source, Session> sessions = new ConcurrentHashMap<>();

    public void start() {
        assert this.timer == null;
        TIMER.scheduleAtFixedRate(this.timer = this.timer(), 0, 1000);

        this.eventSubscriptionService.addEventListener(this, UEIS);
    }

    public void stop() {
        this.eventSubscriptionService.removeEventListener(this);

        assert this.timer != null;
        this.timer.cancel();
    }

    @Override
    public String getName() {
        return "resync-event-handler";
    }

    public final ConcurrentMap<Source, Session> sessions = new ConcurrentHashMap<>();

    public void createSession(final Source source, final String sessionId, final HashMap<String, Object> parameters) {
        if (this.sessions.putIfAbsent(source, Session.builder()
                .sessionId(sessionId)
                .parameters(Maps.transformValues(parameters, Object::toString))
                .build()) != null) {
            throw new IllegalStateException("session already exists for source: " + source);
        }

        log.info("resync session: {} - created (id = {}, handler = {})", source, sessionId, System.identityHashCode(this));
    }

    @Override
    public synchronized void onEvent(final IEvent event) {
        final var source = new Source(event.getNodeid(), event.getInterfaceAddress());

        // Dispatch event based on UEI
        switch (event.getUei()) {
            case UEI_RESYNC_STARTED:
                this.onStarted(source, event);
                break;
            case UEI_RESYNC_FINISHED:
                this.onFinished(source, event);
                break;
            case UEI_RESYNC_TIMEOUT:
                this.onTimeout(source, event);
                break;
            case UEI_RESYNC_ALARM:
                this.onAlarm(source, event);
                break;
            default:
                log.warn("Unknown UEI: {}", event.getUei());
        }
    }

    private synchronized void onStarted(final Source source, final IEvent event) {

        if (!this.sessions.containsKey(source)) {

            log.warn("onStart: unknown session: {} (handler = {})", source, System.identityHashCode(this));
            return;
        }

        final var session = this.sessions.get(source);

        log.info("resyc session {}: started (id = {}, handler = {})", source, session.sessionId, System.identityHashCode(this));

        this.alarmForwarder.postStart(session.sessionId, source.nodeId, session.parameters);
    }

    private synchronized void onFinished(final Source source, final IEvent event) {
        if (!this.sessions.containsKey(source)) {
            log.warn("onFinished: unknown session: {} (handler = {})", source, System.identityHashCode(this));
        }

        // TODO: Keep sessions there to get out status?
        final var session = this.sessions.remove(source);

        log.info("resync session {}: completed (id = {}, handler = {})", source, session.sessionId, System.identityHashCode(this));

        this.alarmForwarder.postEnd(session.sessionId, source.nodeId, session.parameters, true);
    }

    private synchronized void onTimeout(final Source source, final IEvent event) {
        if (!this.sessions.containsKey(source)) {
            log.warn("onTimeout: unknown session: {} (handler = {})", source, System.identityHashCode(this));
            return;
        }

        // TODO: Keep sessions there to get out status?
        final var session = this.sessions.remove(source);

        log.warn("resync session {}: timeout (id = {}, handler = {})", source, session.sessionId, System.identityHashCode(this));

        this.alarmForwarder.postEnd(session.sessionId, source.nodeId, session.parameters, false);
    }

    private synchronized void onAlarm(final Source source, final IEvent event) {
        if (!this.sessions.containsKey(source)) {
            log.info("onAlarm: unknown session - ignoring event: {} (handler = {})", source, System.identityHashCode(this));
            return;
        }

        final var session = this.sessions.get(source);
        session.lastEvent = Instant.now();

        log.info("resync session {}: alarm - {} (id = {}, handler = {})", source, event, session.sessionId, System.identityHashCode(this));

        final var alarm = Resync.Alarm.newBuilder();
        alarm.setUei(event.getUei());
        alarm.setCount(1);

        alarm.setNodeCriteria(Resync.NodeCriteria.newBuilder()
                        .setId(event.getNodeid())
                // TODO: Lookup node to provide more node info
        );

        applyNotNull(event.getInterface(), alarm::setIpAddress);
        applyNotNull(event.getDescr(), alarm::setDescription);
        applyNotNull(event.getLogmsg().getContent(), alarm::setLogMessage);
        applyNotNull(event.getTime(), alarm::setFirstEventTime, Date::getTime);
        applyNotNull(event.getTime(), alarm::setLastEventTime, Date::getTime);
        applyNotNull(event.getIfIndex(), alarm::setIfIndex);
        applyNotNull(event.getOperinstruct(), alarm::setOperatorInstructions);
        applyNotNull(event.getService(), alarm::setServiceName);
        applyNotNull(event.getSeverity(), alarm::setSeverity, s -> Resync.Severity.valueOf(s.toUpperCase()));
        applyNotNull(event.getAlarmData(), alarm::setReductionKey, IAlarmData::getReductionKey);
        applyNotNull(event.getAlarmData(), alarm::setClearKey, IAlarmData::getClearKey);

        Optional.ofNullable(event.getParm("resync-reduction-key"))
                .map(IParm::getValue)
                .map(IValue::getContent)
                .ifPresent(alarm::setReductionKey);

        final var alarEvent = Resync.Event.newBuilder();
        applyNotNull(event.getUei(), alarEvent::setUei);
        applyNotNull(event.getTime(), alarEvent::setTime, Date::getTime);
        applyNotNull(event.getSource(), alarEvent::setSource);
        applyNotNull(event.getCreationTime(), alarEvent::setCreateTime, Date::getTime);
        applyNotNull(event.getDescr(), alarEvent::setDescription);
        applyNotNull(event.getLogmsg().getContent(), alarEvent::setLogMessage);
        applyNotNull(event.getSeverity(), alarEvent::setSeverity, s -> Resync.Severity.valueOf(s.toUpperCase()));

        event.getParmCollection().stream()
                .map(param -> Resync.EventParameter.newBuilder()
                        .setName(param.getParmName())
                        .setType(param.getValue().getType())
                        .setValue(param.getValue().getContent()))
                .forEach(alarEvent::addParameter);

        session.parameters.forEach((key, value) -> {
            alarEvent.addParameter(Resync.EventParameter.newBuilder()
                    .setName(key)
                    .setType("string")
                    .setValue(value));
        });

        alarm.setLastEvent(alarEvent);

        this.alarmForwarder.postAlarm(session.sessionId, alarm.build());
    }

    @Value
    @Builder
    public static class Source {
        Long nodeId;
        InetAddress iface;
    }

    @Data
    @Builder
    private static class Session {
        @NonNull
        private String sessionId;

        @Builder.Default
        private Instant lastEvent = Instant.now();

        @NonNull
        private Map<String, String> parameters;
    }

    private TimerTask timer() {
        return new TimerTask() {
            @Override
            public void run() {
                synchronized (EventHandler.this) {
                    final var timeout = Instant.now().minus(SESSION_TIMEOUT);

                    for (final var session : EventHandler.this.sessions.entrySet()) {
                        if (session.getValue().lastEvent.isBefore(timeout)) {
                            log.info("resync session {}: timeout - send event", session.getKey());

                            EventHandler.this.eventForwarder.sendNow(new EventBuilder()
                                    .setTime(new Date())
                                    .setSource(EVENT_SOURCE)
                                    .setUei(UEI_RESYNC_TIMEOUT)
                                    .setNodeid(session.getKey().getNodeId())
                                    .setInterface(session.getKey().getIface())
                                    .getEvent());
                        }
                    }
                }
            }
        };
    }

    private static <T> void applyNotNull(final T input, final Consumer<T> consumer) {
        if (input == null) {
            return;
        }

        consumer.accept(input);
    }

    private static <T, R> void applyNotNull(final T input, final Consumer<R> consumer, final Function<T, R> map) {
        if (input == null) {
            return;
        }

        final R output = map.apply(input);
        if (output == null) {
            return;
        }

        consumer.accept(output);
    }
}
