# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build the shaded JAR
mvn clean package

# Run the CLI (requires JanusGraph/HBase backend)
./start-cli.sh

# With custom config
./start-cli.sh /path/to/config.properties

# With debug port 5005
DEBUG=true ./start-cli.sh
```

The `start-cli.sh` script sets classpath, Kerberos opts, and JAVA_OPTS (default `-Xmx2g -Xms1g`). The shaded JAR at `target/janusgraph-cli-1.0.0.jar` can also be run directly via `java -jar`.

## Architecture

Interactive CLI for JanusGraph backed by HBase. Uses a **Command pattern** — every operation is a class implementing the `Command` interface.

### Package layout

```
com.chenpp.janus
├── JanusGraphCLI.java          # Entry point, REPL loop, command registry, built-in commands (help/exit/config)
├── commands/
│   ├── Command.java            # Single-method interface: void execute(String args)
│   ├── IndexCommand.java       # index create/list/drop/enable/disable/reindex/describe/repair
│   ├── SchemaCommand.java      # schema list/show/create(key|vertex|edge)/drop(key|vertex|edge)/stats
│   ├── QueryCommand.java       # Gremlin execution via GremlinGroovyScriptEngine (read & write, auto-commit)
│   ├── BatchCommand.java       # import/export CSV/JSON, count, sample, delete-all
│   ├── BackupCommand.java      # create/restore/list/delete JSON backups
│   └── ScriptCommand.java      # batch execute commands from .script files
├── core/
│   ├── GraphManager.java       # JanusGraph connection lifecycle, config loading, auto-reconnect
│   └── IndexManager.java       # Index lifecycle: create, wait for status, enable/disable, reindex, drop
└── utils/
    ├── TableFormatter.java     # ASCII table renderer (header separator, column alignment)
    └── JsonFormatter.java      # Fastjson pretty-print wrapper
```

### Key design decisions

- **Command aliases**: Registered with both full and short names (index/idx, schema/sc, query/q, gremlin/g). Fallback: unknown input is tried as a Gremlin query.
- **REPL**: JLine with history file at `~/.janusgraph_history`, multi-line continuation (`\`), Ctrl+D support.
- **GraphManager**: Loads config from `.properties` file, opens `JanusGraph` via `JanusGraphFactory.build().set(config).open()`. `getGraph()` auto-reconnects if closed. Config print masks sensitive keys (password, secret, keytab, principal).
- **IndexManager**: All index mutations go through `JanusGraphManagement`. The `createCompositeIndex` method waits for REGISTERED status, then runs REINDEX. `dropIndexSafely` goes DISABLED→REMOVE_INDEX with a 1s sleep between steps.
- **QueryCommand**: Executes arbitrary Gremlin via TinkerPop `GremlinGroovyScriptEngine` with `g` and `graph` bound; materializes lazily-evaluated traversals, and auto-commits writes (rolls back on error). Bare `V()`/`E()` and `count()` are normalized to the `g.`-prefixed form.
- **Config**: Kerberos, HBase connection, server/region principal all in `janusgraph-hbase.properties`. JAAS and krb5 conf loaded via start script system properties.

### Dependencies

- JanusGraph 0.6.4 with HBase backend
- JLine 3.12.1 for REPL
- Fastjson 1.2.83 for JSON
- SLF4J + slf4j-log4j12 (log4j 1.x), configured via `log4j.properties`
- commons-configuration 1.10

### Important constraints

- Java 8 target, maven-shade-plugin builds a fat JAR (excludes `META-INF/*.SF`, `*.DSA`, `*.RSA`)
- When connecting to HBase with Kerberos, the start script adds `/etc/hbase/conf` and `/etc/hadoop/conf` to classpath
- ZooKeeper SASL is explicitly disabled via `-Dzookeeper.sasl.client=false` in start script
- No tests currently exist in the project
