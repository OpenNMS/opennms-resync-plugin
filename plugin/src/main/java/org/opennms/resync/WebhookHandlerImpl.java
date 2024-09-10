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

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.snmp.SnmpObjId;
import org.opennms.netmgt.snmp.SnmpValue;
import org.opennms.netmgt.snmp.snmp4j.Snmp4JValueFactory;

import javax.ws.rs.core.Response;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

@Slf4j
@RequiredArgsConstructor
public class WebhookHandlerImpl implements WebhookHandler {

    @NonNull
    private final TriggerService triggerService;

    @Override
    public Response ping() {
        return Response.ok("pong").build();
    }

    @Override
    public Response trigger(final TriggerRequest request) throws ExecutionException, InterruptedException {
        log.debug("trigger: {}", request);

        // TODO: Extract default mode from node meta-data

        final var result = this.triggerService.trigger(TriggerRequestMapper.INSTANCE.toServiceRequest(request));

        if (request.isSync()) {
            result.get();
        }

        return Response.ok().build();
    }

    @Mapper()
    public interface TriggerRequestMapper {
        TriggerRequestMapper INSTANCE = Mappers.getMapper(TriggerRequestMapper.class);

        default InetAddress inetAddress(final String ipAddress) {
            return InetAddressUtils.addr(ipAddress);
        }

        default SnmpObjId snmpObjId(final String oid) {
            return SnmpObjId.get(oid);
        }

        default SnmpValue snmpValue(final String value) {
            return new Snmp4JValueFactory().getOctetString(value.getBytes(StandardCharsets.UTF_8));
        }

        @Mapping(target = "nodeCriteria", source = "node")
        TriggerService.Request toServiceRequest(final TriggerRequest request);
    }
}

