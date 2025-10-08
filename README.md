# OpenNMS Resync Plugin

This plugin provides two main features:

1. **Resync Operations** - Synchronize alarm states between OpenNMS and external systems
2. **Alarm Actions** - Execute alarm management operations (ACK/UNACK/TERM/UNDOTERM) on network devices via SNMP

## Installation

Build and install the plugin into your local Maven repository using:

```bash
mvn clean install
```

Install the .kar file:
```bash
cp assembly/kar/target/opennms-resync-plugin.kar /opt/opennms/deploy/
```

From the OpenNMS Karaf shell:
```bash
feature:install opennms-plugins-resync
```

## Features

### Resync Operations

Once installed, the plugin makes the following Karaf shell commands available:
* `opennms-resync:set`
* `opennms-resync:get`

REST endpoints:
* `http://localhost:8980/opennms/rest/resync/ping` - Check if the plugin is installed
* `http://localhost:8980/opennms/rest/resync/trigger` - Trigger a resync operation

### Alarm Actions

REST endpoints:
* `http://localhost:8980/opennms/rest/actions/ping` - Health check
* `http://localhost:8980/opennms/rest/actions` - Execute alarm actions (ACK/UNACK/TERM/UNDOTERM)

**See [ALARM_ACTIONS_README.md](ALARM_ACTIONS_README.md) for detailed alarm actions documentation.**

## Resync Configuration

The `trigger`-endpoint provides two modes which can be selected by the `mode` field: `SET` or `GET`.

There request for a resync looks like this:
```http request
Accept: application/json
Content-Type: application/json
  
{
  "resyncId": "my unique ID",
  "node": "1",   # Can be a node ID, a foreignSource:foreignId or a node label
  "ipInterface": "127.0.0.1",  # Optional, will use primary interface if omitted
  "kind": "my-device-type",  # Optional, will look up from config if omitted
  "parameters": {  # Fill in missing parameters or overwrite existing
    "param1": "all",
    "param2": "all"
  }
}
```

## Configuration
The plugin picks up the configuration of the OpenNMS Kafka Producer.

There is a config file which must exist on `$OPENNMS_HOME/etc/resync.json`.
It has the following structure:
```json
{
  "nodes": {
    "my.node.label": {
      "kind": "example-kind"
    }
  },
  "kinds": {
    "example-kind": {
      "mode": "GET",  # or "SET"
      "columns": {
        "param1": "1.3.6.0.0.1",
        "param2": "1.3.6.0.0.2"
      },
      "parameters": {
        "param2": "example value"
      }
    }
  }
}
```

### Reduction key mapping
The configured event should **not** be configured to have a reduction key set.
If a reduction key is required on the produced alarms, a special parameter in the event definition could be used.
The value of this parameter is set as reduction key in the resulting alarm.

```
<events xmlns="http://xmlns.opennms.org/xsd/eventconf">
  <event>
    <parameter name="resync-reduction-key" value="%uei%:%snmphost%:%nodeid%:%parm[#2]%" expand="true"/>
  </event>
</events>
```

## Debugging
The plugin creates log messages about session creation and every processed event.
These log messages can be found in karaf.log and are marked with bundle ID `org.opennms.plugins.resync.plugin`.

After triggering, the first log message to expect is the session creation.
Followed by a message for each received alarm associated with this session.
Finally, the log will show eiter a successful session termination or a session timeout.
