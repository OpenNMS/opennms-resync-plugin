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
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.opennms.resync.TriggerService;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Command(scope = "opennms-resync", name = "trigger", description = "Trigger a re-sync")
@Service
public class TriggerCommand implements Action {

    @Reference
    private TriggerService triggerService;

    @Argument(name = "node", required = true)
    private String node;

    @Option(name = "resync-id")
    private String resyncId;

    @Option(name = "interface")
    private String ipInterface = null;

    @Option(name = "mode")
    private TriggerService.Mode mode = TriggerService.Mode.SET;

    @Override
    public Object execute() {
        final var ipInterface = this.ipInterface != null
                ? InetAddresses.forString(this.ipInterface)
                : null;

        final var request = TriggerService.Request.builder()
                .nodeCriteria(this.node)
                .ipInterface(ipInterface)
                .sessionId(this.resyncId != null
                        ? this.resyncId
                        : UUID.randomUUID().toString())
                .mode(this.mode)
                .build();

        final var result = this.triggerService.trigger(request);

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
}
