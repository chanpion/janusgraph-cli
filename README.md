# JanusGraph CLI

交互式命令行工具，用于管理 JanusGraph 图数据库（HBase 后端），支持索引管理、Schema 管理、Gremlin 查询、数据导入导出、备份恢复等功能。

> 完整端到端使用示例见 **[docs/USAGE.md](docs/USAGE.md)**。

## 快速开始

### 前置条件

- JDK 8+
- Maven 3.6+（仅构建需要）
- JanusGraph/HBase 集群

### 构建

```bash
# 构建 fat JAR
mvn clean package

# 或使用构建部署脚本（自动检测 JDK 8）
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

> 索引命名约定：普通索引 `idx_<key>`，唯一索引 `uni_<key>`（如 `idx_age`、`uni_name`）。

### Schema（Schema 管理）

| 命令 | 说明 |
|---|---|
| `schema list` | 列出所有 PropertyKey、VertexLabel、EdgeLabel |
| `schema show <name>` | 查看 Schema 元素详情 |
| `schema create key <name> <type> [cardinality]` | 创建 PropertyKey（type: String/Integer/Long/Float/Double/Boolean/Date；cardinality: SINGLE/LIST/SET，默认 SINGLE） |
| `schema create vertex <name>` | 创建 VertexLabel |
| `schema create edge <name> [multiplicity]` | 创建 EdgeLabel（multiplicity: MULTI/SIMPLE，默认 MULTI） |
| `schema drop <kind> <name>` | 删除 Schema 元素（kind: key/vertex/edge） |
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

### Script（脚本执行）

| 命令 | 说明 |
|---|---|
| `script run <file>` | 执行脚本文件 |
| `script list` | 列出可用脚本 |

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
exit = quit
```

## 示例脚本

项目提供 `scripts/example.script`，演示所有命令的完整用法。通过 `script run example.script` 执行，`#` 开头的行为注释（执行时忽略）：

```bash
# ==================== Schema 管理 ====================
# 列出当前所有 Schema 元素（属性键、顶点标签、边标签）
schema list

# 创建属性键（支持类型: String, Integer, Long, Float, Double, Boolean, Date；基数默认 SINGLE）
schema create key uid String
schema create key name String
schema create key age Integer
schema create key email String
schema create key weight Double
schema create key active Boolean

# 查看属性键详情
schema show name
schema show age

# 创建顶点标签
schema create vertex person
schema create vertex company

# 创建边标签（多重性默认 MULTI）
schema create edge knows
schema create edge works_at

# 查看边标签详情
schema show knows

# Schema 统计
schema stats

# ==================== 索引管理 ====================
# 列出所有索引
index list

# 创建复合索引（类型: String/Integer/Long/Float/Double/Boolean/Date, 元素: vertex/edge）
# 格式: index create <名称> <属性键> <类型> [vertex|edge] [-u]
# 顶点 uid 唯一索引；边 uid 只能普通索引（JanusGraph 唯一索引仅支持顶点）
index create uni_uid uid String vertex -u
index create idx_uid uid String edge
index create uni_name name String vertex -u
index create idx_age age Integer vertex
index create idx_weight weight Double edge

# 查看索引状态
index status uni_uid
index status idx_age

# 查看索引详情
index describe uni_uid

# JSON 格式列出索引
index list --json

# 禁用 / 启用索引
index disable idx_age
index status idx_age
index enable idx_age

# ==================== 数据查询 ====================
# 统计顶点和边数量
query V().count()
query E().count()
batch count

# 查询顶点
query V().limit(10)
query V().has('name', 'test').limit(10)

# 查询边
query E().limit(5)

# 采样数据
batch sample 5

# ==================== 数据导入导出 ====================
# 导出数据（CSV 格式）
batch export export.csv

# 导出数据（JSON 格式）
batch export export.json --json

# 导入数据
# batch import data.csv --csv
# batch import data.json --json

# ==================== 备份管理 ====================
# 创建备份
backup create demo-backup

# 列出所有备份
backup list

# 恢复备份
# backup restore demo-backup.json

# 删除备份
# backup delete demo-backup.json

# ==================== 配置管理 ====================
# 查看当前配置（敏感信息已脱敏）
config show

# ==================== 重新索引 ====================
# 对已有数据重新构建索引（大数据量时耗时较长）
# index reindex uni_name

# ==================== 删除操作（危险，已注释） ====================
# 删除索引
# index drop idx_age -f

# 删除属性键
# schema drop key weight
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
JAVA_HOME=/path/to/jdk-8 ./start-cli.sh
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
com.chenpp.janus
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
| JanusGraph | 0.6.4 |
| HBase Client | 2.2.7 |
| Cassandra 后端 | janusgraph-cql 0.6.4 |
| DataStax Java Driver | 4.13.0 |
| TinkerPop / Gremlin | 3.5.3 |
| Hadoop | 3.2.2 |
| JLine | 3.12.1 |
| Fastjson | 1.2.83 |
| SLF4J | 1.7.36 |
| Commons Configuration | 1.10 |
| Java | 8 |
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

> **注意**：项目使用 JDK 8 编译运行。如果系统默认 JDK 版本低于 8，通过 `JAVA_HOME` 指定 JDK 8 路径。
