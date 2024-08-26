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
import org.opennms.integration.api.v1.dao.NodeDao;
import org.opennms.resync.TriggerService;

import javax.ws.rs.core.Response;
import java.net.InetAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Command(scope = "opennms-resync", name = "trigger", description = "Trigger a re-sync")
@Service
public class TriggerCommand implements Action {

    @Reference
    private TriggerService triggerService;

    @Reference
    private NodeDao nodeDao;

    @Argument(name = "host", required = true)
    private String host;

    @Option(name = "location")
    private String location;

    @Option(name = "mode")
    private TriggerService.Request.Mode mode;

    @Override
    public Object execute() {
        final var hostAddress = InetAddresses.forString(this.host);

        final var request = TriggerService.Request.builder()
                .location(this.location != null ? this.location : this.nodeDao.getDefaultLocationName())
                .host(hostAddress)
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
