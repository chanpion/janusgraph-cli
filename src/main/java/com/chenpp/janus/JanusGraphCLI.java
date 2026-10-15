package com.chenpp.janus;

import com.chenpp.janus.commands.BackupCommand;
import com.chenpp.janus.commands.BatchCommand;
import com.chenpp.janus.commands.Command;
import com.chenpp.janus.commands.GraphCommand;
import com.chenpp.janus.commands.IndexCommand;
import com.chenpp.janus.commands.QueryCommand;
import com.chenpp.janus.commands.SchemaCommand;
import com.chenpp.janus.commands.ScriptCommand;
import com.chenpp.janus.core.GraphManager;
import org.apache.hadoop.security.UserGroupInformation;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.security.PrivilegedExceptionAction;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author April.Chen
 */
public class JanusGraphCLI {

    private static final Logger log = LoggerFactory.getLogger(JanusGraphCLI.class);
    private static final String HISTORY_FILE = System.getProperty("user.home") + "/.janusgraph_history";

    private final GraphManager graphManager;
    private final Map<String, Command> commands;
    private volatile boolean running = true;

    public JanusGraphCLI(GraphManager graphManager) {
        this.graphManager = graphManager;
        this.commands = initializeCommands();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭...");
            graphManager.close();
        }));
    }

    private Map<String, Command> initializeCommands() {
        Map<String, Command> cmds = new LinkedHashMap<>();

        IndexCommand indexCmd = new IndexCommand(graphManager);
        cmds.put("index", indexCmd);
        cmds.put("idx", indexCmd);

        SchemaCommand schemaCmd = new SchemaCommand(graphManager);
        cmds.put("schema", schemaCmd);
        cmds.put("sc", schemaCmd);

        QueryCommand queryCmd = new QueryCommand(graphManager);
        cmds.put("query", queryCmd);
        cmds.put("q", queryCmd);
        cmds.put("gremlin", queryCmd);
        cmds.put("g", queryCmd);

        BatchCommand batchCmd = new BatchCommand(graphManager);
        cmds.put("batch", batchCmd);

        BackupCommand backupCmd = new BackupCommand(graphManager);
        cmds.put("backup", backupCmd);

        ScriptCommand scriptCmd = new ScriptCommand(graphManager, this);
        cmds.put("script", scriptCmd);

        GraphCommand graphCmd = new GraphCommand(graphManager);
        cmds.put("graph", graphCmd);

        // 内置命令
        cmds.put("help", new HelpCommand(commands));
        cmds.put("exit", new ExitCommand());
        cmds.put("quit", new ExitCommand());
        cmds.put("config", new ConfigCommand(graphManager));

        return cmds;
    }

    private String prompt() {
        String graphName = graphManager.getCurrentGraphName();
        if (graphName == null || graphName.isEmpty()) {
            graphName = "janus";
        }
        return graphName + "> ";
    }

    public void start() throws Exception {
        System.out.println("╔═══════════════════════════════════════════════╗");
        System.out.println("║          JanusGraph 命令行工具 v1.0           ║");
        System.out.println("╚═══════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("输入 'help' 查看可用命令，'exit' 退出");
        System.out.println("已连接至: " + graphManager.getConfigPath());
        if (graphManager.isKerberosEnabled()) {
            System.out.println("Kerberos: 已启用 (" + graphManager.getLoginUser().getUserName() + ")");
        } else {
            System.out.println("Kerberos: 已禁用");
        }
        long vertexCount = graphManager.testConnection();
        System.out.println(vertexCount >= 0
                ? "连接测试: 正常 (顶点数 = " + vertexCount + ")"
                : "连接测试: 警告 (见日志; 继续运行)");
        System.out.println();

        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new org.jline.reader.impl.history.DefaultHistory())
                .variable(LineReader.HISTORY_FILE, new File(HISTORY_FILE).toPath())
                .build();

        while (running) {
            try {
                String line = reader.readLine(prompt());
                if (line == null) {
                    break;
                }

                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // 处理多行命令（以 \ 结尾）
                while (line.endsWith("\\")) {
                    line = line.substring(0, line.length() - 1);
                    String next = reader.readLine(prompt() + "  ");
                    if (next == null) {
                        break;
                    }
                    line += next.trim();
                }

                executeCommand(line);

            } catch (org.jline.reader.EndOfFileException e) {
                break;
            } catch (Exception e) {
                System.err.println("错误: " + e.getMessage());
            }
        }

        terminal.close();
        System.out.println("再见！");
    }

    public void executeCommand(String input) {
        if (input == null || input.trim().isEmpty()) {
            return;
        }

        String[] parts = input.split("\\s+", 2);
        String cmdName = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        // 尝试匹配子命令
        boolean found = false;
        for (Map.Entry<String, Command> entry : commands.entrySet()) {
            if (input.startsWith(entry.getKey() + " ")) {
                cmdName = entry.getKey();
                args = input.substring(entry.getKey().length()).trim();
                found = true;
                break;
            }
        }

        // 如果没有匹配到命令，尝试作为Gremlin查询执行
        if (!found && !commands.containsKey(cmdName)) {
            Command queryCmd = commands.get("query");
            if (queryCmd != null) {
                try {
                    queryCmd.execute(input);
                } catch (Exception e) {
                    System.err.println("查询执行失败: " + e.getMessage());
                }
                return;
            }
        }

        Command cmd = commands.get(cmdName);
        if (cmd == null) {
            System.err.println("未知命令: " + cmdName);
            System.out.println("输入 'help' 查看可用命令");
            return;
        }

        try {
            cmd.execute(args);
        } catch (Exception e) {
            System.err.println("命令执行失败: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("原因: " + e.getCause().getMessage());
            }
        }
    }

    class HelpCommand implements Command {
        private final Map<String, Command> commands;

        HelpCommand(Map<String, Command> commands) {
            this.commands = commands;
        }

        @Override
        public void execute(String args) {
            System.out.println("\n=== 可用命令 ===");
            System.out.println();
            System.out.println("索引操作:");
            System.out.println("  index list                    - 列出所有索引");
            System.out.println("  index create <name> <key> <type> [-u] - 创建复合索引");
            System.out.println("  index drop <name> [-f]       - 删除索引");
            System.out.println("  index status <name>          - 查看索引状态");
            System.out.println("  index enable <name>          - 启用索引");
            System.out.println("  index disable <name>         - 禁用索引");
            System.out.println("  index reindex <name>         - 对已有数据重建索引");
            System.out.println("  index describe <name>        - 查看索引详情");
            System.out.println("  index repair <name>          - 修复索引问题");
            System.out.println();
            System.out.println("Schema 操作:");
            System.out.println("  schema list                  - 列出所有属性键与标签");
            System.out.println("  schema show <name>           - 查看 Schema 详情");
            System.out.println("  schema create key <name> <type>    - 创建属性键");
            System.out.println("  schema create vertex <name>        - 创建顶点标签");
            System.out.println("  schema create edge <name>          - 创建边标签");
            System.out.println("  schema drop key|vertex|edge <name> - 删除 Schema 元素");
            System.out.println("  schema stats                 - 查看 Schema 统计");
            System.out.println();
            System.out.println("查询操作:");
            System.out.println("  query <gremlin>              - 执行 Gremlin 查询");
            System.out.println("  q <gremlin>                  - query 的别名");
            System.out.println("  g <gremlin>                  - query 的别名");
            System.out.println();
            System.out.println("批量操作:");
            System.out.println("  batch import <file> [--csv|--json] - 导入数据");
            System.out.println("  batch export <file> [--csv|--json] - 导出数据");
            System.out.println("  batch delete-all [--force]   - 删除所有数据");
            System.out.println("  batch count                  - 统计顶点与边数量");
            System.out.println("  batch sample <n>             - 采样数据");
            System.out.println();
            System.out.println("备份操作:");
            System.out.println("  backup create [name]         - 创建备份");
            System.out.println("  backup restore <file>        - 从备份恢复");
            System.out.println("  backup list                  - 列出备份");
            System.out.println("  backup delete <file>         - 删除备份");
            System.out.println();
            System.out.println("脚本操作:");
            System.out.println("  script run <file>            - 执行脚本文件");
            System.out.println("  script list                  - 列出可用脚本");
            System.out.println();
            System.out.println("图操作:");
            System.out.println("  graph create <name>          - 创建并切换图");
            System.out.println("  graph current                - 查看当前图名");
            System.out.println("  graph list                   - 列出可用图");
            System.out.println();
            System.out.println("配置操作:");
            System.out.println("  config show                  - 查看当前配置");
            System.out.println("  config reload                - 重新加载配置");
            System.out.println();
            System.out.println("快捷方式:");
            System.out.println("  idx = index,  sc = schema,  q = query");
            System.out.println("  g = gremlin");
            System.out.println();
        }
    }

    class ExitCommand implements Command {
        @Override
        public void execute(String args) {
            running = false;
        }
    }

    class ConfigCommand implements Command {
        private final GraphManager graphManager;

        ConfigCommand(GraphManager graphManager) {
            this.graphManager = graphManager;
        }

        @Override
        public void execute(String args) {
            if (args.trim().equals("show")) {
                graphManager.printConfig();
            } else if (args.trim().equals("reload")) {
                graphManager.reloadConfig();
                System.out.println("配置已重新加载");
            } else {
                System.out.println("用法: config show|reload");
            }
        }
    }

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] :
                "janusgraph-hbase.properties";

        try {
            GraphManager graphManager = new GraphManager(configPath);
            JanusGraphCLI cli = new JanusGraphCLI(graphManager);

            UserGroupInformation login = graphManager.getLoginUser();
            if (login != null) {
                login.doAs((PrivilegedExceptionAction<Void>) () -> {
                    cli.start();
                    return null;
                });
            } else {
                cli.start();
            }
        } catch (Exception e) {
            System.err.println("启动 JanusGraph CLI 失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}