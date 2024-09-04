# OpenNMS OpenNMS Resync Plugin Plugin

Build and install the plugin into your local Maven repository using:

```
mvn clean install
```


From the OpenNMS Karaf shell:
```
feature:repo-add mvn:org.opennms.plugins.resync/karaf-features/1.0.0-SNAPSHOT/xml
feature:install opennms-plugins-resync
```


```
cp assembly/kar/target/opennms-resync-plugin.kar /opt/opennms/deploy/
feature:install opennms-plugins-resync
```

```
bundle:watch *
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
    "host": "127.0.0.1",
    "location": "Default",
    "mode": "SET"
  }
  ```

## Configuration
The plugin picks up the configuration of the OpenNMS Kafka Producer.
There is no further configuration required.
