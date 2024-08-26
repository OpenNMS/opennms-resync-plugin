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

import com.google.common.net.HostAndPort;
import io.pkts.Pcap;
import io.pkts.packet.UDPPacket;
import io.pkts.protocol.Protocol;
import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

@Command(scope = "opennms-resync", name = "send-pcap-traps", description = "Send Traps from .pcap file")
@Service
public class SendPcapTraps implements Action {

    @Argument(index = 0, name = "pcap", description = "Path to .pcap file for replay", required = true)
    public File pcapFile;

    @Argument(index = 1, name = "dst", description = "Destination address and port")
    public String destination = "localhost:10162";

    @Option(name = "--throttle", description = "Delay between packets")
    public int throttle = 10;

    @Override
    public Object execute() throws Exception {
        final var destination = this.parseDestination();

        try (final InputStream in = new FileInputStream(pcapFile);
             final DatagramSocket socket = new DatagramSocket()) {
            System.out.printf("Processing packets from '%s'.%n", pcapFile);
            final AtomicLong packetCount = new AtomicLong();
            final Pcap pcap = Pcap.openStream(in);
            pcap.loop(packet -> {
                if (packet.hasProtocol(Protocol.UDP)) {
                    packetCount.getAndIncrement();

                    if (packetCount.get() % 1000 == 0) {
                        System.out.printf("Processing packet #%d.%n", packetCount.get());
                    }

                    final UDPPacket udp = (UDPPacket) packet.getPacket(Protocol.UDP);

                    final var payload = udp.getPayload().getArray();

                    final DatagramPacket pkt = new DatagramPacket(payload, payload.length, destination);
                    socket.send(pkt);

                    try {
                        if (this.throttle > 0) {
                            Thread.sleep(this.throttle);
                        }
                    } catch (final InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                return true;
            });
            System.out.printf("Done processing %d packets.%n", packetCount.get());
        }

        return null;
    }

    private InetSocketAddress parseDestination() {
        final var dst = HostAndPort.fromString(this.destination);
        return new InetSocketAddress(dst.getHost(), dst.getPort());

    }
}
