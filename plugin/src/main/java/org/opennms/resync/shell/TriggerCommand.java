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

import com.google.common.net.InetAddresses;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.opennms.resync.TriggerService;

import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public abstract class TriggerCommand<R> implements Action {
    @Reference
    protected TriggerService triggerService;

    @Argument(name = "node", required = true)
    private String node;

    @Option(name = "interface")
    private String ipInterface = null;

    @Option(name = "resync-id")
    private String resyncId;

    public final Object execute() throws Exception {
        final var ipInterface = this.ipInterface != null
                ? InetAddresses.forString(this.ipInterface)
                : null;

        final var request = this.request(RequestData.builder()
                .resyncId(this.resyncId != null
                        ? this.resyncId
                        : UUID.randomUUID().toString())
                .node(this.node)
                .ipInterface(ipInterface)
                .build());

        final var result = this.execute(request);

        while (!result.isDone()) {
            try {
                result.get(1, TimeUnit.SECONDS);

            } catch (TimeoutException e) {
                System.out.print(".");

            } catch (final InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        System.out.println("Done");

        return null;
    }

    protected abstract R request(final RequestData data);

    protected abstract Future<Void> execute(final R request) throws Exception;

    @Value
    @Builder
    protected static class RequestData {
        @NonNull
        String resyncId;

        @NonNull
        String node;

        InetAddress ipInterface;
    }
}
