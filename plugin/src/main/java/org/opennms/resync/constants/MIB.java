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

package org.opennms.resync.constants;

import org.opennms.netmgt.snmp.SnmpObjId;

public class MIB {
    // TODO: Can we somehow auto-generate this?

    public final static SnmpObjId OID_NBI_GET_ACTIVE_ALARMS = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.3.2.0");

    public static final SnmpObjId OID_NIB_SEQUENCE_ID = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.2.1.3.9");
    public static final SnmpObjId OID_NIB_ALARM_ID = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.5");
    public static final SnmpObjId OID_NIB_ALARM_TYPE = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.7");
    public static final SnmpObjId OID_NIB_OBJECT_INSTANCE = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.2.1.6.5");
    public static final SnmpObjId OID_NIB_EVENT_TIME = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.2.1.6.3");
    public static final SnmpObjId OID_NIB_ALARM_TIME = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.6");
    public static final SnmpObjId OID_NIB_PROBABLE_CAUSE = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.14");
    public static final SnmpObjId OID_NIB_SPECIFIC_PROBLEM = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.16");
    public static final SnmpObjId OID_NIB_PERCEIVED_SEVERITY = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.13");
    public static final SnmpObjId OID_NIB_PROPOSED_REPAIR_ACTION = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.15");
    public static final SnmpObjId OID_NIB_ADDITIONAL_TEXT = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.4");
    public static final SnmpObjId OID_NIB_ACK_STATE = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.1");
    public static final SnmpObjId OID_NIB_ACK_SYSTEM_ID = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.17");
    public static final SnmpObjId OID_NIB_ACK_TIME = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.2");
    public static final SnmpObjId OID_NIB_ACK_USER = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.3");
    public static final SnmpObjId OID_NIB_CLEAR_SYSTEM_ID = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.18");
    public static final SnmpObjId OID_NIB_CLEAR_TIME = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.8");
    public static final SnmpObjId OID_NIB_CLEAR_USER = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.9");
    public static final SnmpObjId OID_NIB_OPTIONAL_INFORMATION = SnmpObjId.get(".1.3.6.1.4.1.28458.1.26.3.1.1.19");

    public final static SnmpObjId OID_CURRENT_ALARM_TABLE = SnmpObjId.get(".1.3.6.1.4.1.3902.4101.1.3");
    public final static SnmpObjId OID_CURRENT_ALARM_TABLE_ALARM_ID = SnmpObjId.get(OID_CURRENT_ALARM_TABLE, "1");
    public final static SnmpObjId OID_CURRENT_ALARM_TABLE_EVENT_TIME = SnmpObjId.get(OID_CURRENT_ALARM_TABLE, "3");
    public final static SnmpObjId OID_CURRENT_ALARM_TABLE_EVENT_TYPE = SnmpObjId.get(OID_CURRENT_ALARM_TABLE, "4");
    public final static SnmpObjId OID_CURRENT_ALARM_TABLE_PROBLEM_CAUSE = SnmpObjId.get(OID_CURRENT_ALARM_TABLE, "5");
}
