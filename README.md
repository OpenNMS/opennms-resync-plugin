# OpenNMS OpenNMS Resync Plugin Plugin

Build and install the plugin into your local Maven repository using:

```
mvn clean install
```

Install the .kar file
```
cp assembly/kar/target/opennms-resync-plugin.kar /opt/opennms/deploy/
```

From the OpenNMS Karaf shell:
```
feature:install opennms-plugins-resync
```


Once installed, the plugin makes the following Karaf shell commands available:
* opennms-resync:set
* opennms-resync:get

You can also access the REST endpoint mounted by the plugin:
* `http://localhost:8980/opennms/rest/resync/ping`: Check if the plugin is installed
* `http://localhost:8980/opennms/rest/resync/trigger`: To trigger a resync operation

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

