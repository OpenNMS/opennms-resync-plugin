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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

public class WebhookHandlerImpl implements WebhookHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WebhookHandlerImpl.class);

    private final TriggerService triggerService;

    public WebhookHandlerImpl(final TriggerService triggerService) {
        this.triggerService = Objects.requireNonNull(triggerService);
    }

    @Override
    public Response ping() {
        return Response.ok("pong").build();
    }

    @Override
    public Response trigger(final String location,
                            final String host,
                            final TriggerRequest request) throws ExecutionException, InterruptedException {
        // TODO: Extract default mode from node meta-data

        final var result = this.triggerService.trigger(TriggerService.Request.builder()
                .location(location)
                .host(host)
                .mode(request.mode())
                .build());

        if (request.sync()) {
            result.get();
        }

        return Response.ok().build();
    }
}

