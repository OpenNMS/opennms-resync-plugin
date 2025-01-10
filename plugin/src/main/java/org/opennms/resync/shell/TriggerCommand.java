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
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.opennms.resync.TriggerService;

import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TriggerCommand implements Action {
    @Reference
    protected TriggerService triggerService;

    @Argument(name = "node", required = true)
    @Getter
    private String node;

    @Option(name = "interface")
    @Getter
    private String ipInterface = null;

    @Option(name = "resync-id")
    @Getter
    private String resyncId;

    @Argument(name = "kind")
    @Getter
    private String kind;

    @Argument(name = "timeout")
    @Getter
    private String timeout;

    @Argument(name = "params", required = true, index = 1)
    @Getter
    private Map<String, Object> parameters;

    public final Object execute() throws Exception {
        final var ipInterface = this.ipInterface != null
                ? InetAddresses.forString(this.ipInterface)
                : null;

        final var request = TriggerCommand.RequestMapper.INSTANCE.toRequest(this);
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

    @Mapper(uses = TriggerService.TriggerMapper.class)
    public interface RequestMapper {
        TriggerCommand.RequestMapper INSTANCE = Mappers.getMapper(TriggerCommand.RequestMapper.class);

        @Mapping(target = "nodeCriteria", source = "node")
        @Mapping(target = "sessionId", source = "resyncId")
        TriggerService.Request toRequest(final TriggerCommand command);
    }
}
