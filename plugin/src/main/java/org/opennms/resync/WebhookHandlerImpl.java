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

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

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
    public Response trigger(final TriggerRequest request) throws IOException, ExecutionException, InterruptedException {
        log.debug("trigger: {}", request);

        // TODO: Extract default mode from node meta-data


        final Future<Void> result;
        switch (request.getMode()) {
            case SET:
                result = this.triggerService.set(TriggerRequestMapper.INSTANCE.toSetRequest(request));
                break;
            case GET:
                result = this.triggerService.get(TriggerRequestMapper.INSTANCE.toGetRequest(request));
                break;
            default:
                throw new IllegalArgumentException("Unsupported mode: " + request.getMode());
        }

        if (request.isSync()) {
            result.get();
        }

        return Response.ok().build();
    }

    @Mapper(uses = TriggerService.TriggerMapper.class)
    public interface TriggerRequestMapper {
        TriggerRequestMapper INSTANCE = Mappers.getMapper(TriggerRequestMapper.class);

        @Mapping(target = "nodeCriteria", source = "node")
        @Mapping(target = "sessionId", source = "resyncId")
        TriggerService.SetRequest toSetRequest(final TriggerRequest request);

        @Mapping(target = "nodeCriteria", source = "node")
        @Mapping(target = "sessionId", source = "resyncId")
        TriggerService.GetRequest toGetRequest(final TriggerRequest request);
    }
}

