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

        final Future<Void> result = this.triggerService.trigger(TriggerRequestMapper.INSTANCE.toRequest(request));

        if (request.isSync()) {
            result.get();
        }

        return Response.ok().build();
    }

    @Override
    public Response performAction(final String action, final String type, final ActionRequest request) {
        log.debug("performAction: action={}, type={}, request={}", action, type, request);

        try {
            // Validate input parameters
            if (action == null || action.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"action parameter is required\"}")
                        .build();
            }

            if (type == null || type.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"type parameter is required\"}")
                        .build();
            }

            // Validate action type
            if (!isValidAction(action)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Invalid action. Supported: ACK, UNACK, TERM, UNDOTERM\"}")
                        .build();
            }

            // Validate operation type
            if (!isValidType(type)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"Invalid type. Supported: SET, GET\"}")
                        .build();
            }

            final Future<Void> result = this.triggerService.performAction(
                    ActionRequestMapper.INSTANCE.toActionRequest(request, action, type));

            // Actions are always synchronous for now
            result.get();

            return Response.ok("{\"status\":\"success\",\"message\":\"Action performed successfully\"}")
                    .build();

        } catch (IllegalArgumentException e) {
            log.error("Invalid request for action: {}", e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (ExecutionException e) {
            log.error("Failed to execute action", e);
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity("{\"error\":\"EMS not reachable: " + cause.getMessage() + "\"}")
                        .build();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Action execution failed: " + cause.getMessage() + "\"}")
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error during action execution", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Unexpected error: " + e.getMessage() + "\"}")
                    .build();
        }
    }

    private boolean isValidAction(String action) {
        return action != null && (action.equals("ACK") || action.equals("UNACK") ||
                                 action.equals("TERM") || action.equals("UNDOTERM"));
    }

    private boolean isValidType(String type) {
        return type != null && (type.equals("SET") || type.equals("GET"));
    }

    @Mapper(uses = TriggerService.TriggerMapper.class)
    public interface TriggerRequestMapper {
        TriggerRequestMapper INSTANCE = Mappers.getMapper(TriggerRequestMapper.class);

        @Mapping(target = "nodeCriteria", source = "node")
        @Mapping(target = "sessionId", source = "resyncId")
        @Mapping(target = "sessionTimeout", source = "timeout")
        TriggerService.Request toRequest(final WebhookHandler.TriggerRequest request);
    }

    @Mapper(uses = TriggerService.TriggerMapper.class)
    public interface ActionRequestMapper {
        ActionRequestMapper INSTANCE = Mappers.getMapper(ActionRequestMapper.class);

        default TriggerService.ActionRequest toActionRequest(final WebhookHandler.ActionRequest request,
                                                           final String action, final String type) {
            return TriggerService.ActionRequest.builder()
                    .actionId(request.getActionId())
                    .nodeCriteria(request.getNode())
                    .ipInterface(TriggerService.TriggerMapper.INSTANCE.inetAddress(request.getIpInterface()))
                    .kind(request.getKind())
                    .parameters(request.getParameters())
                    .actionType(action)
                    .operationType(type)
                    .sessionTimeout(request.getTimeout() != null ?
                        TriggerService.TriggerMapper.INSTANCE.millis(request.getTimeout()) : null)
                    .build();
        }
    }
}

