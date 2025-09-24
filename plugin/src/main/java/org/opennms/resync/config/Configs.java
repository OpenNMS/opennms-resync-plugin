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

package org.opennms.resync.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.opennms.netmgt.snmp.SnmpObjId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
public class Configs {

    private final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public Entry getConfig(final String node, String kind) throws IOException {
        final var path = Paths.get(System.getProperty("opennms.home"), "etc", "resync.json");

        final Config config;
        try (final var reader = Files.newBufferedReader(path)) {
            config = OBJECT_MAPPER.readValue(reader, new TypeReference<>() {});
        }

        if (kind == null) {
            final NodeConfig nodeConfig = config.getNodes().get(node);
            if (nodeConfig == null) {
                throw new IllegalArgumentException("No config found for node: " + node);
            }

            kind = nodeConfig.getKind();
        }

        final var kindConfig = config.getKinds().get(kind);
        if (kindConfig == null) {
            throw new IllegalArgumentException("No config found for kind: " + kind);
        }

        return Entry.builder()
                .kind(kind)
                .mode(kindConfig.getMode())
                .columns(kindConfig.getColumns())
                .parameters(kindConfig.getParameters())
                .timeout(kindConfig.getTimeout() != null
                        ? Duration.ofMillis(kindConfig.getTimeout())
                        : null)
                .build();
    }

    public Entry getActionConfig(final String node, String kind, final String actionType) throws IOException {
        final var path = Paths.get(System.getProperty("opennms.home"), "etc", "resync.json");

        final Config config;
        try (final var reader = Files.newBufferedReader(path)) {
            config = OBJECT_MAPPER.readValue(reader, new TypeReference<>() {});
        }

        if (kind == null) {
            final NodeConfig nodeConfig = config.getNodes().get(node);
            if (nodeConfig == null) {
                throw new IllegalArgumentException("No config found for node: " + node);
            }

            kind = nodeConfig.getKind();
        }

        final var kindConfig = config.getKinds().get(kind);
        if (kindConfig == null) {
            throw new IllegalArgumentException("No config found for kind: " + kind);
        }

        // For action configurations, we need to find the specific action within the kind
        // For now, return the same config but in the future we might have action-specific configs
        return Entry.builder()
                .kind(kind)
                .mode(KindConfig.Mode.SET) // Actions are typically SET operations by default
                .columns(kindConfig.getColumns())
                .parameters(kindConfig.getParameters())
                .timeout(kindConfig.getTimeout() != null
                        ? Duration.ofMillis(kindConfig.getTimeout())
                        : null)
                .build();
    }

    @Value
    @Builder
    public static class Entry {
        String kind;

        @NonNull
        KindConfig.Mode mode;

        @NonNull
        @Builder.Default
        Map<String, SnmpObjId> columns = new LinkedHashMap<>();

        @NonNull
        @Builder.Default
        Map<String, Object> parameters = new LinkedHashMap<>();

        Duration timeout;
    }
}
