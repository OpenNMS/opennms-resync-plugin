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

There request for `SET` mode looks like this:
```http request
Accept: application/json
Content-Type: application/json
  
{
  "mode": "SET",
  "resyncId": "my unique ID",
  "node": "1",
  "ipInterface": "127.0.0.1",
  "attrs": {
    ".1.2.3.4.5": "all"
  }
}
```

There request for `GET` mode looks like this:
```http request
Accept: application/json
Content-Type: application/json
  
{
  "mode": "GET",
  "resyncId": "my unique ID",
  "node": "1",
  "ipInterface": "127.0.0.1",
  "kind": "example-kind"
}
```
Where the `kind`-field refers to an entry in the config file for get mode.

## Configuration
The plugin picks up the configuration of the OpenNMS Kafka Producer.

To configure which SNMP OIDs are sent on a `SET` request, the OIDs and according values can be set in multiple ways:
- By setting node level meta-data in the context `requisition` and a key starting with the prefix `resync:`. 
- By setting interface level meta-data in the context `requisition` and a key starting with the prefix `resync:`.
- By adding entries to the `attrs` property in the request.

To configure which SNMP OIDs to query on a `GET` request, there is a config file which must exist on `$OPENNMS_HOME/etc/resync-get.json`.
It has the following structure:
```json
{
  "example-kind": {
    "columns": {
      "param1": "1.3.6.0.0.1",
      "param2": "1.3.6.0.0.2",
      ...
    },
    "parameters": {
      "param3": "example value",
      ...
    }
  },
  ...
}
```
