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
import java.util.LinkedHashMap;
import java.util.Map;

@RequiredArgsConstructor
public class ActionConfigs {

    private final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public Entry getActionConfig(final String node, String kind, final ActionType actionType) throws IOException {
        final var path = Paths.get(System.getProperty("opennms.home"), "etc", "action.json");

        final ActionsConfig config;
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

        final String finalKind = kind;
        final var actionConfig = kindConfig.getActions().stream()
                .filter(a -> a.getAction() == actionType)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No config found for action: " + actionType + " in kind: " + finalKind));

        final var columns = new LinkedHashMap<String, SnmpObjId>();
        actionConfig.getColumns().forEach((key, oid) -> columns.put(key, SnmpObjId.get(oid)));

        return Entry.builder()
                .kind(kind)
                .actionType(actionType)
                .columns(columns)
                .parameters(actionConfig.getParameters())
                .build();
    }

    @Value
    @Builder
    public static class Entry {
        String kind;

        @NonNull
        ActionType actionType;

        @NonNull
        @Builder.Default
        Map<String, SnmpObjId> columns = new LinkedHashMap<>();

        @NonNull
        @Builder.Default
        Map<String, Object> parameters = new LinkedHashMap<>();
    }
}