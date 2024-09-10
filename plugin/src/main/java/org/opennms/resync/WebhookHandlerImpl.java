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

import com.google.common.net.InetAddresses;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.core.Response;
import java.net.InetAddress;
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

        final var result = this.triggerService.trigger(TriggerService.Request.builder()
                .nodeCriteria(request.getNode())
                .mode(request.getMode())
                .build());

        if (request.isSync()) {
            result.get();
        }

        return Response.ok().build();
    }
}

