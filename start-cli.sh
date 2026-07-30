#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$SCRIPT_DIR"

JAVA_CMD="java"
if [ -n "$JAVA_HOME" ]; then
    JAVA_CMD="${JAVA_HOME}/bin/java"
fi

JAVA_OPTS="${JAVA_OPTS:--Xmx2g -Xms1g}"
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"
JAVA_OPTS="$JAVA_OPTS -Djava.awt.headless=true"

# Kerberos
if [ -f "$APP_HOME/jaas.conf" ]; then
    JAVA_OPTS="$JAVA_OPTS -Djava.security.auth.login.config=$APP_HOME/jaas.conf"
    JAVA_OPTS="$JAVA_OPTS -Djavax.security.auth.useSubjectCredsOnly=false"
fi
if [ -f "$APP_HOME/krb5.conf" ]; then
    JAVA_OPTS="$JAVA_OPTS -Djava.security.krb5.conf=$APP_HOME/krb5.conf"
fi
JAVA_OPTS="$JAVA_OPTS -Dzookeeper.sasl.client=false"

# 调试
if [ "$DEBUG" = "true" ]; then
    JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"
fi

CONFIG_PATH="${1:-$APP_HOME/janusgraph-hbase.properties}"
if [ ! -f "$CONFIG_PATH" ]; then
    echo "ERROR: Configuration file not found: $CONFIG_PATH"
    exit 1
fi

CLASSPATH="$APP_HOME/janusgraph-cli-1.0.0.jar"
if [ ! -f "$CLASSPATH" ]; then
    echo "ERROR: JAR not found: $CLASSPATH"
    echo "  Copy janusgraph-cli-1.0.0.jar to this directory, or run 'mvn clean package' first."
    exit 1
fi
if [ -d "/etc/hbase/conf" ]; then
    CLASSPATH="$CLASSPATH:/etc/hbase/conf"
fi
if [ -d "/etc/hadoop/conf" ]; then
    CLASSPATH="$CLASSPATH:/etc/hadoop/conf"
fi

echo "Starting JanusGraph CLI..."
echo "Config: $CONFIG_PATH"
echo ""

exec $JAVA_CMD $JAVA_OPTS -cp "$CLASSPATH" \
    com.example.janus.JanusGraphCLI "$CONFIG_PATH"