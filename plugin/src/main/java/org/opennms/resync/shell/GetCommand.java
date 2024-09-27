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

package org.opennms.resync.shell;

import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.resync.TriggerService;

import java.util.Map;
import java.util.concurrent.Future;

@Command(scope = "opennms-resync", name = "get", description = "Trigger get a re-sync")
@Service
public class GetCommand extends TriggerCommand<TriggerService.GetRequest> {

    @Argument(name = "oid", required = true, index = 1)
    private String oid;

    @Override
    protected TriggerService.GetRequest request(final RequestData data) {
        return RequestMapper.INSTANCE.toGetRequest(data, this.oid);
    }

    @Override
    protected Future<Void> execute(final TriggerService.GetRequest request) throws Exception {
        return this.triggerService.get(request);
    }

    @Mapper(uses = TriggerService.TriggerMapper.class)
    public interface RequestMapper {
        RequestMapper INSTANCE = Mappers.getMapper(RequestMapper.class);

        @Mapping(target = "nodeCriteria", source = "data.node")
        @Mapping(target = "sessionId", source = "data.resyncId")
        TriggerService.GetRequest toGetRequest(final RequestData data, final String kind);
    }
}
