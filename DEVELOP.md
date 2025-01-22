# Install from source
```
feature:repo-add mvn:org.opennms.plugins.resync/karaf-features/1.0.0-SNAPSHOT/xml
feature:install opennms-plugins-resync
bundle:watch *
```

# API Interactions
This plugin direcly interacts with the OpenNMS core API to execute SNMP requests (mainly `LocationAwareSnmpClient`).
To make this work, the plugin binds to every OpenNMS version.
Changes to the used API will result in runtime errors while using the plugin.
If theses APIs will ever change, the plugin has to be adopted.
