# JanusGraph CLI

交互式命令行工具，用于管理 JanusGraph 图数据库（HBase 后端），支持索引管理、Schema 管理、Gremlin 查询、数据导入导出、备份恢复等功能。

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.6+（仅构建需要）
- JanusGraph/HBase 集群

### 构建

```bash
# 构建 fat JAR
mvn clean package

# 或使用构建部署脚本（自动检测 JDK 17）
./build-deploy.sh
```

### 启动

```bash
# 默认配置
./start-cli.sh

# 指定配置文件
./start-cli.sh /path/to/config.properties

# 开启远程调试端口 5005
DEBUG=true ./start-cli.sh

# 直接使用 java -jar
java -jar target/janusgraph-cli-1.0.0.jar janusgraph-hbase.properties
```

### 示例

```
$ ./start-cli.sh

╔═══════════════════════════════════════════════╗
║     JanusGraph Command Line Tool v1.0        ║
╚═══════════════════════════════════════════════╝

Type 'help' for available commands, 'exit' to quit

janus> schema list
janus> query V().count()
janus> index list
janus> system info
janus> exit
```

## 命令参考

### Index（索引管理）

| 命令 | 说明 |
|---|---|
| `index list [--json]` | 列出所有索引 |
| `index create <name> <key> <type> [-u]` | 创建复合索引（type: vertex/edge, -u: 唯一索引） |
| `index drop <name> [-f]` | 删除索引 |
| `index status <name>` | 查看索引状态 |
| `index enable <name>` | 启用索引 |
| `index disable <name>` | 禁用索引 |
| `index reindex <name>` | 对已有数据重建索引 |
| `index describe <name>` | 查看索引详情 |
| `index repair <name>` | 修复索引状态 |

### Schema（Schema 管理）

| 命令 | 说明 |
|---|---|
| `schema list` | 列出所有 PropertyKey、VertexLabel、EdgeLabel |
| `schema show <name>` | 查看 Schema 元素详情 |
| `schema create-key <name> <type>` | 创建 PropertyKey（type: String/Integer/Long/Double/Boolean） |
| `schema create-label <name> <type>` | 创建 Label（type: vertex/edge）|
| `schema drop-key <name>` | 删除 PropertyKey |
| `schema stats` | 查看 Schema 统计信息 |

### Query（Gremlin 查询）

| 命令 | 说明 |
|---|---|
| `query <gremlin>` | 执行 Gremlin 查询 |
| `q <gremlin>` | 同上，简写 |
| `g <gremlin>` | 同上，简写 |

未识别的命令前缀会自动作为 Gremlin 查询执行。

### Batch（批量操作）

| 命令 | 说明 |
|---|---|
| `batch import <file> [--csv\|--json]` | 导入数据 |
| `batch export <file> [--csv\|--json]` | 导出数据 |
| `batch delete-all [--force]` | 删除所有数据 |
| `batch count` | 统计顶点和边数量 |
| `batch sample <n>` | 采样查看数据 |

### Backup（备份恢复）

| 命令 | 说明 |
|---|---|
| `backup create [name]` | 创建 JSON 格式备份 |
| `backup restore <file>` | 从备份恢复 |
| `backup list` | 列出备份文件 |
| `backup delete <file>` | 删除备份文件 |

### Monitor（监控）

| 命令 | 说明 |
|---|---|
| `monitor start [interval]` | 启动周期性监控（默认间隔 5s） |
| `monitor stop` | 停止监控 |
| `monitor status` | 查看监控状态 |
| `monitor query <gremlin>` | 监控查询执行 |

### Script（脚本执行）

| 命令 | 说明 |
|---|---|
| `script run <file>` | 执行脚本文件 |
| `script list` | 列出可用脚本 |

### System（系统命令）

| 命令 | 说明 |
|---|---|
| `system info` | 显示系统信息 |
| `system metrics` | 显示 JVM 指标 |
| `system threads` | 显示线程信息 |
| `system gc` | 强制执行 GC |
| `system clear` | 清屏 |
| `system time` | 显示当前时间 |

### Config（配置管理）

| 命令 | 说明 |
|---|---|
| `config show` | 查看当前配置（敏感信息脱敏） |
| `config reload` | 重新加载配置 |

### 命令别名

```
idx  = index
sc   = schema
q    = query / gremlin
g    = query / gremlin
sys  = system
exit = quit
```

## 配置

### 配置文件

`janusgraph-hbase.properties` 包含以下配置项：

```properties
# HBase 连接
storage.backend=hbase
storage.hostname=zk-host1,zk-host2,zk-host3
storage.hbase.table=janusgraph

# Kerberos 认证
storage.hbase.ext.hbase.security.authentication=kerberos
storage.hbase.ext.hadoop.security.authentication=kerberos

# 缓存优化
storage.cache.db-cache=true
storage.cache.db-cache-size=0.5
```

`start-cli.sh` 会自动在同目录下查找以下文件：
- `janusgraph-hbase.properties` — HBase 连接配置（必需）
- `jaas.conf` — Kerberos JAAS 配置（可选）
- `krb5.conf` — Kerberos 域配置（可选）
- `hbase.headless.keytab` — Kerberos 密钥文件（可选）

### 启动参数

可通过环境变量控制 JVM 参数：

```bash
# 设置 JVM 堆大小（默认 -Xmx2g -Xms1g）
JAVA_OPTS="-Xmx4g -Xms2g" ./start-cli.sh

# 指定 JDK 路径
JAVA_HOME=/path/to/jdk-17 ./start-cli.sh
```

## 部署

### 构建部署包

```bash
./build-deploy.sh
```

生成产物：
```
target/janusgraph-cli-1.0.0/
├── start-cli.sh
├── janusgraph-cli-1.0.0.jar    # fat JAR（148MB，含所有依赖）
├── janusgraph-hbase.properties
├── jaas.conf
├── krb5.conf
└── hbase.headless.keytab
```

### 部署到服务器

```bash
# 复制部署包到服务器
scp target/janusgraph-cli-1.0.0.tar.gz user@server:/path/to/

# 解压
tar xzf janusgraph-cli-1.0.0.tar.gz
cd janusgraph-cli-1.0.0

# 修改配置（按需调整）
# vim jaas.conf              # 修改 principal
# vim janusgraph-hbase.properties  # 修改 HBase 地址

# 启动
./start-cli.sh
```

### Kerberos 认证说明

部署时确保以下文件配置正确：

1. `jaas.conf`：
```
Client {
  com.sun.security.auth.module.Krb5LoginModule required
  useKeyTab=true
  keyTab="hbase.headless.keytab"   # keytab 文件，与脚本同目录
  storeKey=true
  useTicketCache=false             # 强制使用 keytab
  doNotPrompt=true
  refreshKrb5Config=true
  principal="hbase-user@REALM";    # 修改为实际 principal
};
```

2. `hbase.headless.keytab` — 由运维提供，从 HBase 集群导出
3. `krb5.conf` — Kerberos 域配置，通常已存在于 `/etc/krb5.conf`

## 项目架构

### 包结构

```
com.example.janus
├── JanusGraphCLI.java          # 入口、REPL 循环、命令注册
├── core/
│   ├── GraphManager.java       # JanusGraph 连接生命周期
│   └── IndexManager.java       # 索引管理
├── commands/
│   ├── Command.java            # 命令接口
│   ├── IndexCommand.java       # 索引操作
│   ├── SchemaCommand.java      # Schema 操作
│   ├── QueryCommand.java       # Gremlin 查询
│   ├── BatchCommand.java       # 批量导入导出
│   ├── BackupCommand.java      # 备份恢复
│   ├── MonitorCommand.java     # 监控
│   ├── SystemCommand.java      # JVM 系统命令
│   └── ScriptCommand.java      # 脚本执行
└── utils/
    ├── TableFormatter.java     # ASCII 表格渲染
    └── JsonFormatter.java      # JSON 格式化
```

### 设计模式

- **命令模式**：每个操作实现 `Command` 接口
- **工厂模式**：`JanusGraphFactory` 管理图实例创建
- **单例管理**：`GraphManager` 统一管理连接生命周期，支持自动重连

### 技术栈

| 组件 | 版本 |
|---|---|
| JanusGraph | 1.0.1 |
| HBase Client | 2.4.18 |
| TinkerPop / Gremlin | 3.6.4 |
| Hadoop | 3.3.6 |
| JLine | 3.26.3 |
| Fastjson2 | 2.0.53 |
| Java | 17 |
| Maven | 3.6+ |

## 开发

```bash
# 编译
mvn compile

# 打包（不含测试）
mvn clean package -DskipTests

# 运行本地开发环境
./start-cli.sh src/main/resources/janusgraph-hbase.properties
```

> **注意**：项目使用 JDK 17 编译运行。如果系统默认 JDK 版本低于 17，通过 `JAVA_HOME` 指定 JDK 17 路径。
