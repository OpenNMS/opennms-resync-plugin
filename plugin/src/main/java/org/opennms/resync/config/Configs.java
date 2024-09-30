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
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.osgi.service.cm.ConfigurationAdmin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;

@RequiredArgsConstructor
public class Configs {

    private final static ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public GetConfig getConfig(final String kind) throws IOException {
        final var path = Paths.get(System.getProperty("opennms.home"), "etc", "resync-get.json");

        final HashMap<String, GetConfig> configs;
        try (final var reader = Files.newBufferedReader(path)) {
            configs = OBJECT_MAPPER.readValue(reader, new TypeReference<>() {});
        }

        return configs.get(kind);
    }

}