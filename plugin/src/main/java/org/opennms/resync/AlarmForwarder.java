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

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.streams.processor.api.Record;
import org.opennms.features.kafka.producer.OpennmsKafkaProducer;
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
        final Dictionary<String, Object> config = configurationAdmin.getConfiguration(OpennmsKafkaProducer.KAFKA_CLIENT_PID).getProperties();
        if (config == null) {
            log.warn("No kafka producer configuration found. Skipping alarm forwarding.");
            throw new IllegalStateException("No kafka producer configuration found.");
        }

        this.topic = Objects.toString(Objects.requireNonNullElse(config.get("alarmTopic"), "alarms"));

        final Properties producerConfig = new Properties();
        {
            final var keys = config.keys();
            while (keys.hasMoreElements()) {
                final var key = keys.nextElement();
                final var val = config.get(key);
                producerConfig.put(key, val);
            }
        }

        producerConfig.put("key.serializer", ByteArraySerializer.class.getCanonicalName());
        producerConfig.put("value.serializer", ByteArraySerializer.class.getCanonicalName());

        this.producer = runWithGivenClassLoader(() -> new KafkaProducer<>(producerConfig), AlarmForwarder.class.getClassLoader());
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

    public void postStart(final long nodeId) {
        final var message = Resync.ResyncStart.newBuilder()
                .setNodeId(nodeId)
                .build();

        final var record = new ProducerRecord<>(topic, (byte[]) null, message.toByteArray());
        record.headers().add(HEADER_RESYNC_MARK_START, new byte[0]);

        this.send(record);
    }

    public void postEnd(final long nodeId, final boolean success) {
        final var message = Resync.ResyncEnd.newBuilder()
                .setNodeId(nodeId)
                .setSuccess(success)
                .build();

        final var record = new ProducerRecord<>(topic, (byte[]) null, message.toByteArray());
        record.headers().add(HEADER_RESYNC_MARK_END, new byte[0]);

        this.send(record);
    }

    public void postAlarm(final Resync.Alarm alarm) {
        final var updatedAlarm = alarm.toBuilder()
                .setResync(true)
                .build();

        final var key = alarm.getReductionKey().getBytes(StandardCharsets.UTF_8);

        final var record = new ProducerRecord<>(topic, key, updatedAlarm.toByteArray());
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
}
