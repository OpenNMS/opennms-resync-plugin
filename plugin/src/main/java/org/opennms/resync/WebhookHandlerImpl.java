/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2024 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2024 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/
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

