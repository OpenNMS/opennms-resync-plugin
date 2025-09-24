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
import lombok.NonNull;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;

@Path("resync")
public interface WebhookHandler {

    @GET
    @Path("/ping")
    Response ping();

    @POST
    @Path("/trigger")
    @Produces({MediaType.APPLICATION_JSON})
    @Consumes({MediaType.APPLICATION_JSON})
    Response trigger(TriggerRequest request) throws Exception;

    @POST
    @Path("/actions")
    @Produces({MediaType.APPLICATION_JSON})
    @Consumes({MediaType.APPLICATION_JSON})
    Response performAction(@QueryParam("action") String action,
                          @QueryParam("type") String type,
                          ActionRequest request) throws Exception;

    @Value
    @Builder
    @Jacksonized
    class TriggerRequest {
        @NonNull
        String resyncId;

        @NonNull
        String node;

        @Builder.Default
        String ipInterface = null;

        @Builder.Default
        String kind = null;

        @NonNull
        @Builder.Default
        Map<String, Object> parameters = new HashMap<>();

        @Builder.Default
        boolean sync = false;

        Long timeout;

    }

    @Value
    @Builder
    @Jacksonized
    class ActionRequest {
        @NonNull
        String actionId;

        @NonNull
        String node;

        @Builder.Default
        String ipInterface = null;

        @Builder.Default
        String kind = null;

        @NonNull
        @Builder.Default
        Map<String, Object> parameters = new HashMap<>();

        Long timeout;
    }
}
