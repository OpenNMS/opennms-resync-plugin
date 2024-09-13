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
* opennms-resync:trigger

You can also access the REST endpoint mounted by the plugin:
* `http://localhost:8980/opennms/rest/resync/ping`: Check if the plugin is installed
* `http://localhost:8980/opennms/rest/resync/trigger`:
  ```http request
  Accept: application/json
  Content-Type: application/json
  
  {
    "node": "1",
    "ipInterface": "127.0.0.1",
    "mode": "SET",
    "attrs": {
      ".1.2.3.4.5": "all"
    }
  }
  ```

## Configuration
The plugin picks up the configuration of the OpenNMS Kafka Producer.

To configure which SNMP OIDs are sent on a request, the OIDs and according values can be set in multiple ways:
- By setting node level meta-data in the context `requisition` and a key starting with the prefix `resync:`. 
- By setting interface level meta-data in the context `requisition` and a key starting with the prefix `resync:`.
- By adding entries to the `attrs` property in the request.


