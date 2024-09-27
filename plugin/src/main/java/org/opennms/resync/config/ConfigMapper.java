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

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.opennms.resync.TriggerService;

import java.util.Map;

@Mapper(uses = TriggerService.TriggerMapper.class)
public interface ConfigMapper {
//    ConfigMapper INSTANCE = Mappers.getMapper(ConfigMapper.class);
//
//    GetConfig mapGetConfig(final Map<String, String> properties);
//
//    Map<String, GetConfig> mapGetConfigs(final Map<String, Map<String, String>> properties);
}
