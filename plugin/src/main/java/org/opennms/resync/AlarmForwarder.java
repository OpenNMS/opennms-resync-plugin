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

import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.opennms.resync.proto.Resync;
import org.osgi.service.cm.ConfigurationAdmin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Dictionary;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

@Slf4j
public class AlarmForwarder {

    private final static String HEADER_RESYNC_MARK_START = "x-opennms-resync-start";
    private final static String HEADER_RESYNC_MARK_END = "x-opennms-resync-end";
    private final static String HEADER_RESYNC_MARK_ALARM = "x-opennms-resync-alarm";

    private final String topic;

    private final KafkaProducer<byte[], byte[]> producer;

    public AlarmForwarder(final ConfigurationAdmin configurationAdmin) throws IOException {
        final Dictionary<String, Object> clientConfig = configurationAdmin.getConfiguration("org.opennms.features.kafka.producer.client").getProperties();
        if (clientConfig == null) {
            log.warn("No kafka producer client configuration found.");
            throw new IllegalStateException("No kafka producer client configuration found.");
        }

        final Dictionary<String, Object> producerConfig = configurationAdmin.getConfiguration("org.opennms.features.kafka.producer").getProperties();
        if (producerConfig == null) {
            log.warn("No kafka producer configuration found.");
            throw new IllegalStateException("No kafka producer configuration found.");
        }

        this.topic = Objects.toString(Objects.requireNonNullElse(producerConfig.get("alarmTopic"), "alarms"));

        final Properties producer = new Properties();
        {
            final var keys = clientConfig.keys();
            while (keys.hasMoreElements()) {
                final var key = keys.nextElement();
                final var val = clientConfig.get(key);
                producer.put(key, val);
            }
        }

        producer.put("key.serializer", ByteArraySerializer.class.getCanonicalName());
        producer.put("value.serializer", ByteArraySerializer.class.getCanonicalName());

        this.producer = runWithGivenClassLoader(() -> new KafkaProducer<>(producer), AlarmForwarder.class.getClassLoader());
    }

    private void send(final ProducerRecord<byte[], byte[]> record) {
        try {
            this.producer.send(record, (metadata, ex) -> {
                if (ex != null) {
                    log.error("Failed to send record", ex);
                } else {
                    log.debug("Sent record");
                }
            }).get();
        } catch (final ExecutionException e) {
            log.error("Failed to send record", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void postStart(final String sessionId, final long nodeId) {
        final var message = Resync.ResyncStart.newBuilder()
                .setNodeId(nodeId)
                .setResyncId(sessionId)
                .build();

        log.debug("post: start: {}", msgToJson(message));

        final var record = new ProducerRecord<>(this.topic, (byte[]) null, message.toByteArray());
        record.headers().add(HEADER_RESYNC_MARK_START, new byte[0]);

        this.send(record);
    }

    public void postEnd(final String sessionId, final long nodeId, final boolean success) {
        final var message = Resync.ResyncEnd.newBuilder()
                .setNodeId(nodeId)
                .setSuccess(success)
                .setResyncId(sessionId)
                .build();

        log.debug("post: end: {}", msgToJson(message));

        final var record = new ProducerRecord<>(this.topic, (byte[]) null, message.toByteArray());
        record.headers().add(HEADER_RESYNC_MARK_END, new byte[0]);

        this.send(record);
    }

    public void postAlarm(final String sessionId, final Resync.Alarm alarm) {
        final var updatedAlarm = alarm.toBuilder()
                .setResyncId(sessionId)
                .build();

        log.debug("post: alarm: {}", msgToJson(updatedAlarm));

        final var key = alarm.getReductionKey().getBytes(StandardCharsets.UTF_8);

        final var record = new ProducerRecord<>(this.topic, key, updatedAlarm.toByteArray());
        record.headers().add(HEADER_RESYNC_MARK_ALARM, new byte[0]);

        this.send(record);
    }

    private static <T> T runWithGivenClassLoader(final Supplier<T> supplier, ClassLoader classLoader) {
        Objects.requireNonNull(supplier);
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(classLoader);
            return supplier.get();
        } finally {
            Thread.currentThread().setContextClassLoader(contextClassLoader);
        }
    }

    @SneakyThrows
    private static String msgToJson(final MessageOrBuilder message) {
        return JsonFormat.printer().print(message);
    }
}
