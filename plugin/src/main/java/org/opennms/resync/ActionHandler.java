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

import lombok.Builder;
import lombok.Data;
import org.opennms.resync.config.ActionType;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.InetAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Path("/actions")
public interface ActionHandler {

    @GET
    @Path("/ping")
    @Produces(MediaType.TEXT_PLAIN)
    Response ping();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Response executeAction(@QueryParam("action") String action,
                          @QueryParam("type") String type,
                          ActionRequest request) throws Exception;

    @Data
    @Builder
    class ActionRequest {
        String actionId;
        String node;
        String ipInterface;
        String kind;
        @Builder.Default
        Map<String, Object> parameters = new HashMap<>();
    }

    @Data
    @Builder
    class ActionResponse {
        String status;
        String message;
        String actionId;
        String actionType;
        Integer rowCount;
        @Builder.Default
        Map<String, Object> data = new HashMap<>();
    }
}