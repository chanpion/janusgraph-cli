# JanusGraph CLI 完整使用示例

本文档从零开始，端到端演示 JanusGraph CLI 的完整用法：启动 → 建 Schema → 建索引 → 写入数据 → 查询 → 批量导入导出 → 备份 → 图管理 → 清理。

> **说明**
> - CLI 连接的是**真实的 JanusGraph + HBase** 集群，以下命令需在可达该集群的环境执行。
> - 命令提示符形如 `janus_demo> `，其中 `janus_demo` 是当前图（即配置里的 `storage.hbase.table`）；切换图后提示符随之变化。
> - 文中的「预期输出」为示例，具体 id、耗时、日志行会因环境而异。
> - 配套样例数据见 [`examples/`](examples/)（`data.json` / `vertices.csv` / `edges.csv`）。

---

## 0. 前置条件

- JDK 8+、Maven 3.6+（仅构建需要）
- 可达的 JanusGraph/HBase 集群，以及一份可用的配置文件（含 `storage.hostname`、`storage.hbase.table`、Kerberos 相关项）

```bash
# 在与源码同目录，构建 fat JAR
mvn clean package
# 产出：target/janusgraph-cli-1.0.0.jar
```

## 1. 启动

```bash
# 默认配置：从 classpath 发现 janusgraph-hbase.properties
./start-cli.sh

# 指定配置文件
./start-cli.sh src/main/resources/janusgraph-10.properties

# 其他方式
java -jar target/janusgraph-cli-1.0.0.jar <config-path>
JAVA_OPTS="-Xmx4g -Xms2g" ./start-cli.sh
```

启动后会打印横幅、连接信息、Kerberos 状态与一次连通性测试（顶点数）：

```
╔═══════════════════════════════════════════════╗
║          JanusGraph 命令行工具 v1.0           ║
╚═══════════════════════════════════════════════╝

输入 'help' 查看可用命令，'exit' 退出
已连接至: src/main/resources/janusgraph-10.properties
Kerberos: 已启用 (hbase-cluster60@yuntu.com)
连接测试: 正常 (顶点数 = 4)

janus_demo>
```

> 输入 `help` 查看全部命令，`exit`/`quit` 退出。

## 2. 连接与配置确认

```text
janus_demo> graph current
当前图: janus_demo
当前库: default
连接状态: true

janus_demo> config show
=== 当前配置 ===
  storage.backend = hbase
  storage.hostname = zk1,zk2,zk3
  storage.hbase.table = janus_demo
  janusapp.kerberos.keytab = ***          # 敏感项已脱敏
  janusapp.kerberos.principal = ***
  ...
```

```text
janus_demo> config reload        # 修改配置文件后热加载并重连
配置已重新加载
```

## 3. Schema 管理

```text
# 查看现有 Schema
janus_demo> schema list
=== Schema 概览 ===
属性键: []
顶点标签: []
边标签: []
合计: 0

# 创建属性键（类型: String/Integer/Long/Float/Double/Boolean/Date；基数可选，默认 SINGLE）
janus_demo> schema create key uid String
✓ 属性键 'uid' 创建成功
janus_demo> schema create key name String
✓ 属性键 'name' 创建成功
janus_demo> schema create key age Integer
✓ 属性键 'age' 创建成功
janus_demo> schema create key weight Double
✓ 属性键 'weight' 创建成功
janus_demo> schema create key tags String LIST
✓ 属性键 'tags' 创建成功

# 创建顶点/边标签（边多重性 MULTI|SIMPLE，默认 MULTI）
janus_demo> schema create vertex person
✓ 顶点标签 'person' 创建成功
janus_demo> schema create vertex company
✓ 顶点标签 'company' 创建成功
janus_demo> schema create edge knows
✓ 边标签 'knows' 创建成功
janus_demo> schema create edge works_at
✓ 边标签 'works_at' 创建成功

# 查看详情 / 统计
janus_demo> schema show name
=== 属性键: name ===
  数据类型: String
  基数: SINGLE

janus_demo> schema stats
=== Schema 统计 ===
属性键: 5
顶点标签: 2
边标签: 2
合计: 9

数据统计:
  顶点数: 0
  边数: 0
```

> 说明：数据写入（第 5、7 节）会自动为缺失的属性键/标签建 schema（`allow-upgrade=true` 时），本节的显式创建是可选的，便于先固化类型。

## 4. 索引管理

JanusGraph 只有配了外部索引后端（ES/Solr）才能用 Mixed 索引；本 CLI 建的是**复合索引（Composite）**，仅支持等值匹配。

> 索引命名约定：普通索引 `idx_<key>`，唯一索引 `uni_<key>`（如 `idx_age`、`uni_name`）。
>
> ⚠️ JanusGraph 的**唯一索引只能建在顶点上**。同一属性在边上只能用普通索引（命名用 `idx_<key>`，与顶点的 `uni_<key>` 天然不冲突），如 `uni_uid`(vertex) 与 `idx_uid`(edge)。

```text
# 创建复合索引：<名称> <属性键> <类型> [vertex|edge] [-u 唯一]
# 顶点 uid：唯一索引（全图 uid 唯一）
janus_demo> index create uni_uid uid String vertex -u
✓ 索引 'uni_uid' 创建成功
# 边 uid：只能建普通索引（JanusGraph 不允许在边上建唯一索引）
janus_demo> index create idx_uid uid String edge
✓ 索引 'idx_uid' 创建成功
janus_demo> index create uni_name name String vertex -u
✓ 索引 'uni_name' 创建成功
janus_demo> index create idx_age age Integer vertex
✓ 索引 'idx_age' 创建成功
janus_demo> index create idx_weight weight Double edge
✓ 索引 'idx_weight' 创建成功

# 查看
janus_demo> index list
+------------+---------+-----------+------------------+----------+------+
| 名称       | 状态    | 类型      | 元素             | 属性键   | 唯一 |
+------------+---------+-----------+------------------+----------+------+
| uni_uid    | ENABLED | Composite | JanusGraphVertex | [uid]    | 是   |
| idx_uid    | ENABLED | Composite | JanusGraphEdge   | [uid]    | 否   |
| uni_name   | ENABLED | Composite | JanusGraphVertex | [name]   | 是   |
| idx_age    | ENABLED | Composite | JanusGraphVertex | [age]    | 否   |
| idx_weight | ENABLED | Composite | JanusGraphEdge   | [weight] | 否   |
+------------+---------+-----------+------------------+----------+------+

janus_demo> index status uni_uid
索引 'uni_uid' 状态: ENABLED

janus_demo> index describe uni_uid
=== 索引: uni_uid ===
  类型: Composite
  元素: JanusGraphVertex
  唯一: true
  状态: ENABLED
  属性键:
    - uid (String)

janus_demo> index list --json          # JSON 输出

# 禁用/启用/重建
janus_demo> index disable idx_age
✓ 索引 'idx_age' 禁用成功
janus_demo> index enable idx_age
✓ 索引 'idx_age' 启用成功
janus_demo> index reindex idx_age        # 大数据量时耗时较长，会要求确认
```

> 索引创建内部会走 `REGISTER → REINDEX → ENABLED`，并等待至 `ENABLED`（默认最多 300s）。**若超时会如实报 `✗ ...失败`**，可用 `index status <name>` 复查（此时定义已提交、REINDEX 可能仍在进行）。

## 5. 写入数据（Gremlin，自动提交）

CLI 内置 Gremlin 执行（TinkerPop `GremlinGroovyScriptEngine`），**读写皆可**；写语句执行后**自动 commit**。

```text
# 新增顶点
janus_demo> g.addV('person').property('uid','person-1').property('name','marko').property('age',29)
结果 (耗时 55ms):
  结果数: 1
  [0] 顶点: id=4240, label=person uid=person-1 name=marko age=29

janus_demo> g.addV('person').property('uid','person-2').property('name','vadas').property('age',27)
janus_demo> g.addV('person').property('uid','person-3').property('name','josh').property('age',32)
janus_demo> g.addV('company').property('uid','company-1').property('name','acme')

# 新增边：先用 as('a') 标记起点，再由起点 addE 到当前顶点
janus_demo> g.V().has('name','marko').as('a').V().has('name','vadas').addE('knows').from('a').property('uid','knows-1').property('weight',0.5)
janus_demo> g.V().has('name','marko').as('a').V().has('name','josh').addE('knows').from('a').property('uid','knows-2').property('weight',1.0)
janus_demo> g.V().has('name','marko').as('a').V().has('name','acme').addE('works_at').from('a').property('uid','worksat-1')

# 更新 / 删除
janus_demo> g.V().has('name','marko').property('age',30)
janus_demo> g.V().has('name','vadas').drop()
```

> - 可按 `q`/`query`/`g` 任一前缀，也可省略前缀（`V().count()` 会被规整为 `g.V().count()`）。
> - Groovy 语法可用，例如 `new Date()`、`0.9f`（Float）、`1L`（Long）。

## 6. 查询数据

```text
# 计数（全表扫描；会打印 "use indexes" WARN，属正常现象）
janus_demo> g.V().count()
结果 (耗时 19ms):
  3
janus_demo> g.E().count()
结果 (耗时 5ms):
  2

# 走索引的等值查询（命中复合索引，不再 WARN）
janus_demo> g.V().has('name','marko')
结果 (耗时 8ms):
  结果数: 1
  [0] 顶点: id=4240, label=person uid=person-1 name=marko age=30
# 唯一索引 uid 上的等值查询（同样命中索引）
janus_demo> g.V().has('uid','person-1')
结果 (耗时 6ms):
  结果数: 1
  [0] 顶点: id=4240, label=person uid=person-1 name=marko age=30
janus_demo> g.V().has('age',29).count()
结果 (耗时 4ms):
  0

# 是否命中索引
janus_demo> g.V().has('name','marko').profile()
结果 (耗时 12ms):
  [Traversal Metrics
   Step                                                               Count  Traversers ...
   JanusGraphStep([],[name.eq(marko)])                                    1          1 ...
  ...

# 遍历 / 采样
janus_demo> g.V().limit(10)
janus_demo> g.V().valueMap()
结果 (耗时 15ms):
  结果数: 3
  [0] {name=[marko], age=[30]}
  ...
janus_demo> g.E().valueMap()
```

> **关于 WARN**：`g.V()` / `count()` 没有任何索引谓词，必然全表扫描，JanusGraph 会打印 `Query requires iterating over all vertices`，这是性能提示而非错误。要消除它，请用带索引谓词的查询（如 `has('name','marko')`）。

## 7. 批量导入 / 导出

配套样例数据见目录 `docs/examples/`（`data.json` / `vertices.csv` / `edges.csv`）。

> 本节按**干净图**演示导入（与前几节的 Gremlin 写入相互独立）；重复导入会追加数据。

### 7.1 JSON 导入（推荐，可一次导入顶点+边，自动建 schema）

`examples/data.json` 结构：

```json
{
  "vertices": [
    { "id": "v1", "label": "person", "properties": { "uid": "person-1", "name": "marko", "age": 29 } },
    { "id": "v4", "label": "company", "properties": { "uid": "company-1", "name": "acme" } }
  ],
  "edges": [
    { "from": "v1", "to": "v4", "label": "works_at", "properties": { "uid": "worksat-1" } }
  ]
}
```

```text
janus_demo> batch import docs/examples/data.json
正在从 docs/examples/data.json 导入数据 (JSON)...
导入完成: 4 顶点, 3 边 (耗时 120ms)
```

> `id` 是**文件内逻辑 id**（非 JanusGraph 顶点 id），仅用于边的 `from`/`to` 关联；端点缺失的边会被跳过并提示 `已跳过边: 缺少端点`。

### 7.2 CSV 导入

`examples/vertices.csv`（表头即属性名，每行一个 `vertex` 标签顶点）：

```csv
uid,name,age,active
csv-1,marko,29,true
csv-2,vadas,27,false
csv-3,josh,32,true
```

`examples/edges.csv`（表头以 `from` 开头即视为**边文件**；端点按顶点 `name` 属性查找，需顶点已存在）：

```csv
from,to,weight,uid
marko,vadas,0.5,csv-e1
marko,josh,1.0,csv-e2
```

```text
janus_demo> batch import docs/examples/vertices.csv
导入完成: 3 顶点, 0 边 (耗时 30ms)
janus_demo> batch import docs/examples/edges.csv
导入完成: 0 顶点, 2 边 (耗时 15ms)
```

> CSV 限制：顶点标签固定为 `vertex`；类型按列名启发式推断（`age`/`*count`→Integer、`weight`/`*score*`→Double、`active`/`is*`→Boolean，其余 String）；边标签固定为 `edge`。
>
> 顶点 `uid` 是**唯一索引**，7.1 与 7.2 用了不同的 `uid` 值（`person-*` / `csv-*`），可在同一张图上先后演示而不会撞唯一约束。

### 7.3 统计 / 采样 / 导出

```text
janus_demo> batch count
顶点数: 4
边数: 3

janus_demo> batch sample 3
采样顶点:
  [person] 4240 name=marko age=29
  ...
采样边:
  [knows] 4240 -> 4256

# 导出（JSON 可再次 batch import 往返）
janus_demo> batch export out.json --json
已导出至 out.json (耗时 40ms)

# 导出（CSV：无表头、带 id/label，仅便于查看，不可回导）
janus_demo> batch export out.csv
已导出至 out.csv (耗时 20ms)
```

## 8. 备份

```text
janus_demo> backup create demo
正在创建备份: demo.json
✓ 备份已创建: /path/to/backups/demo.json
  顶点数: 4

janus_demo> backup list
+-----------+--------+---------------------+----------------------------+
| 名称      | 大小   | 日期                | 路径                       |
+-----------+--------+---------------------+----------------------------+
| demo.json | 1.2 KB | 2026-09-30 10:00:00 | /path/to/backups/demo.json |
+-----------+--------+---------------------+----------------------------+

janus_demo> backup delete demo.json
✓ 备份已删除: demo.json
```

> ⚠️ **限制**：`backup create` 只导出**顶点**（不含边），文件写到 `backups/<name>.json`；**`backup restore` 目前是占位实现**（会提示 `! 恢复功能尚未实现，此为占位提示`），不能用于恢复数据。需要恢复请用第 7 节的 `batch import` + `batch export` JSON。

## 9. 图管理

```text
janus_demo> graph current
当前图: janus_demo
当前库: default
连接状态: true

# 列出可用图：仅列当前库（storage.hbase.table 的命名空间）内、按列族结构识别出的 JanusGraph 表
janus_demo> graph list

=== 可用图 (库: default) ===
  janus_demo (*)
  janusgraph
  (*) = 当前图

# 列出「开放实例」；索引卡在 INSTALLED 常见于存在陈旧实例（此前非正常退出的进程/会话）
janus_demo> graph instances

=== 打开的 JanusGraph 实例 ===
  3a7f0c9e-...-20260930 (current)
  19b2d1aa-...-20260929

  '(current)' 为当前实例，勿关；其余若来自已退出的进程（旧主机/旧会话），用 'graph close-instance <id>'(id 不含 '(current)') 关闭后，索引即可推进到 REGISTERED。

# 强制关闭陈旧实例
janus_demo> graph close-instance 19b2d1aa-...-20260929
✓ 实例 '19b2d1aa-...-20260929' 已强制关闭

# 切换图（会改变后续操作的落库表）；图名为裸名，自动限定到当前库
janus_demo> graph use janusgraph
正在从 'janus_demo' 切换到 'janusgraph'...
已切换到图 'janusgraph' (顶点数: 0)
janusgraph>
```

切换失败（例如目标图使用了本地 classpath 缺失的自定义组件）会**自动回滚并说明原因**：

```text
janus_demo> graph use duigong_2405
正在从 'janus_demo' 切换到 'duigong_2405'...
切换到图 'duigong_2405' 失败: Could not find implementation class: cn.tongdun.yuntu.haina.client.YuntuIDPlacementStrategy
已回退到图 'janus_demo'
```

> **作用域**：图列表与切换限定在**当前库**——`storage.hbase.table` 形如 `ns:table` 时 `ns` 即当前库（无 `:` 则为 `default`）。`graph list` 只列当前库内的图；`graph use` / `graph create` 只接受**裸表名**并自动限定到当前库，含 `:` 的名字（如 `nsB:graphB`）会被拒绝。仅 HBase 后端支持。
> 若目标图依赖自定义类（如自定义 `ids.placement`），需把对应 jar 加入 `start-cli.sh` 的 classpath 才能打开。
> `graph create <name>` 可新建并切换（会先确认）。

## 10. 脚本化

把命令序列写入 `.script` 文件，逐行执行（每行一条命令；需确认的命令请加 `-f`/`--force` 或保持注释）。

```text
janus_demo> script list

=== 可用脚本 ===
  example.script (5903 字节)

janus_demo> script run example.script
正在运行脚本: example.script
[1] schema list
...
✓ 脚本执行完成 (耗时 2100ms)
```

## 11. 清理（危险操作）

以下操作不可逆，正式环境请谨慎：

```text
# 删除索引（-f 跳过确认）
janus_demo> index drop idx_age -f
✓ 索引 'idx_age' 删除成功

# 删除 Schema 元素（key/vertex/edge，需 y 确认）
janus_demo> schema drop key weight
删除 Schema key 'weight' 可能影响已有数据，是否继续? (y/N): y
✓ Schema key 'weight' 删除成功
janus_demo> schema drop vertex company
janus_demo> schema drop edge works_at

# 清空所有数据（必须 --force）
janus_demo> batch delete-all --force
所有数据已删除。
```

---

## 附录 A. 命令别名

```
idx  = index
sc   = schema
q    = query / gremlin
g    = query / gremlin
exit = quit
```

## 附录 B. 常见问题

| 现象 | 说明 / 处理 |
| --- | --- |
| `g.V()` / `count()` 打印 `use indexes` WARN | 全表扫描的性能提示，非错误；改用带索引谓词的查询（`has('name',x)`）即可消除 |
| `g.V().has('uid').count()` 返回 `1`？ | 不是计数=1。`count()` 返回**结果列表里那一个值**（如 `0`），CLI 会把单元素标量直接打印为值本身 |
| `index create` 报 `✗ Failed` | 多为等待 `ENABLED` 超时（默认 300s）。定义已提交，用 `index status <name>` 复查；可 `index enable <name>` / `index reindex <name>` 继续推进，或 `index drop <name> -f` 重来 |
| 日志出现 `PermanentLockingException: Local lock contention` | JanusGraph 同进程内事务争锁（常见于索引任务提交与另一事务并发）。等锁过期（`storage.lock.expiry-time`，默认 300s）后重试，或 `index drop <name> -f` 后重建；并确认表含 `edgestore_lock_` 等锁列族 |
| 索引卡在 `INSTALLED`（`REGISTER_INDEX` 不收敛） | 通常是存在**陈旧实例**：推进到 `REGISTERED` 需要集群所有实例确认，幽灵实例会一直卡住。执行 `graph instances` 查看，用 `graph close-instance <id>` 关闭非 `(current)` 的残留实例，再 `index reindex <name>`。注意 `ENABLE_INDEX`/`REINDEX`/`REMOVE_INDEX` 均不适用于 `INSTALLED`，必须先 REGISTER |
| `index drop` 无反应 | 未加 `-f` 时会提示 `确认删除索引 '<name>'? (y/N)` |
| `index drop` 执行较慢 | 删除索引分两步：先异步删除索引数据（等待其 scan job 完成，最多 300s），再删除索引定义。JanusGraph 0.6.x 的 `REMOVE_INDEX` **只删数据、不删定义**，CLI 额外补做了定义删除，故不会残留 `DISABLED` 索引 |
| `graph use` 失败并回滚 | 目标图可能依赖本地缺失的类（自定义 `ids.placement` 等），把对应 jar 加入 classpath |
| `graph list` 为空 | 当前库（`storage.hbase.table` 的命名空间）内没有可识别的 JanusGraph 表（按列族判断）；当前配置图始终会显示 |
| 给边的 `uid` 建唯一索引报错 | JanusGraph **唯一索引只能建在顶点上**（`Unique indexes can only be created on vertices`）；边的同名字段请用普通索引（如 `idx_uid`） |
| 配置从哪来 | 默认从 classpath 发现 `janusgraph-hbase.properties`，也可在启动时显式传路径 |

---

## 附录 C. 使用 Cassandra (CQL) 后端

CLI 通过 `storage.backend` 自动识别后端，除「图管理」（见下）外，Schema / 索引 / 查询 / 批量 / 备份命令用法完全一致。

用模板配置启动（模板在 `src/main/resources/janusgraph-cassandra.properties`）：

```bash
./start-cli.sh janusgraph-cassandra.properties
```

关键配置项：

```properties
storage.backend=cql
storage.hostname=127.0.0.1        # Cassandra 接触点（多个用逗号分隔）
storage.port=9042
storage.cql.keyspace=janusgraph   # 相当于 HBase 的表名
storage.cql.local-datacenter=datacenter1
storage.username=cassandra        # 用户名密码认证
storage.password=cassandra        # config show 中自动显示为 ***
```

说明：

- **当前图名**取自 `storage.cql.keyspace`，提示符与 `graph current` 显示该 keyspace。
- `graph list` / `graph use` / `graph create` **仅 HBase 支持**（且只在 `storage.hbase.table` 所在命名空间内生效），在 Cassandra 下会提示不支持并返回；`graph current` / `graph instances` / `graph close-instance` 仍可用。
- Cassandra 后端不支持 Kerberos，启动横幅会显示 `Kerberos: 已禁用`。


