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
import org.opennms.resync.config.ActionType;

import javax.ws.rs.core.Response;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Slf4j
@RequiredArgsConstructor
public class ActionHandlerImpl implements ActionHandler {

    @NonNull
    private final ActionService actionService;

    @Override
    public Response ping() {
        return Response.ok("pong").build();
    }

    @Override
    public Response executeAction(String action, String type, ActionRequest request) throws Exception {
        log.info("executeAction: action={}, type={}, request={}", action, type, request);

        // Validate action parameter
        if (action == null || action.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ActionResponse.builder()
                            .status("error")
                            .message("Missing required query parameter: action")
                            .build())
                    .build();
        }

        // Parse action type
        final ActionType actionType;
        try {
            actionType = ActionType.valueOf(action.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ActionResponse.builder()
                            .status("error")
                            .message("Invalid action type: " + action + ". Must be one of: ACK, UNACK, TERM, UNDOTERM")
                            .build())
                    .build();
        }

        // Parse request type (default to SET)
        final ActionService.RequestType requestType;
        if (type == null || type.trim().isEmpty()) {
            requestType = ActionService.RequestType.SET;
        } else {
            try {
                requestType = ActionService.RequestType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ActionResponse.builder()
                                .status("error")
                                .message("Invalid request type: " + type + ". Must be one of: SET, GET")
                                .build())
                        .build();
            }
        }

        // Validate request body
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ActionResponse.builder()
                            .status("error")
                            .message("Request body is required")
                            .build())
                    .build();
        }

        if (request.getActionId() == null || request.getActionId().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ActionResponse.builder()
                            .status("error")
                            .message("Missing required field: actionId")
                            .build())
                    .build();
        }

        if (request.getNode() == null || request.getNode().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ActionResponse.builder()
                            .status("error")
                            .message("Missing required field: node")
                            .build())
                    .build();
        }

        try {
            final ActionService.Request serviceRequest = ActionRequestMapper.INSTANCE.toServiceRequest(request, actionType, requestType);
            final Future<Map<String, Object>> resultFuture = this.actionService.executeAction(serviceRequest);

            // Wait for the result
            final Map<String, Object> result = resultFuture.get();

            final ActionResponse response = ActionResponse.builder()
                    .status((String) result.get("status"))
                    .actionId((String) result.get("actionId"))
                    .actionType((String) result.get("actionType"))
                    .rowCount((Integer) result.get("rowCount"))
                    .message("Action executed successfully")
                    .data(result)
                    .build();

            return Response.ok(response).build();

        } catch (ExecutionException e) {
            log.error("Action execution failed: action={}, actionId={}", action, request.getActionId(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ActionResponse.builder()
                            .status("error")
                            .message("Action execution failed: " + e.getCause().getMessage())
                            .actionId(request.getActionId())
                            .actionType(actionType.name())
                            .build())
                    .build();
        } catch (IllegalArgumentException e) {
            log.error("Invalid configuration: action={}, actionId={}", action, request.getActionId(), e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ActionResponse.builder()
                            .status("error")
                            .message(e.getMessage())
                            .actionId(request.getActionId())
                            .actionType(actionType.name())
                            .build())
                    .build();
        } catch (Exception e) {
            log.error("Unexpected error: action={}, actionId={}", action, request.getActionId(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ActionResponse.builder()
                            .status("error")
                            .message("Unexpected error: " + e.getMessage())
                            .actionId(request.getActionId())
                            .actionType(actionType.name())
                            .build())
                    .build();
        }
    }

    @Mapper(uses = ActionService.ActionMapper.class)
    public interface ActionRequestMapper {
        ActionRequestMapper INSTANCE = Mappers.getMapper(ActionRequestMapper.class);

        @Mapping(target = "nodeCriteria", source = "request.node")
        @Mapping(target = "ipInterface", source = "request.ipInterface")
        @Mapping(target = "actionId", source = "request.actionId")
        @Mapping(target = "kind", source = "request.kind")
        @Mapping(target = "parameters", source = "request.parameters")
        @Mapping(target = "actionType", source = "actionType")
        @Mapping(target = "requestType", source = "requestType")
        ActionService.Request toServiceRequest(ActionHandler.ActionRequest request, ActionType actionType, ActionService.RequestType requestType);
    }
}