package com.chenpp.janus;

import com.chenpp.janus.commands.BackupCommand;
import com.chenpp.janus.commands.BatchCommand;
import com.chenpp.janus.commands.Command;
import com.chenpp.janus.commands.GraphCommand;
import com.chenpp.janus.commands.IndexCommand;
import com.chenpp.janus.commands.MonitorCommand;
import com.chenpp.janus.commands.QueryCommand;
import com.chenpp.janus.commands.SchemaCommand;
import com.chenpp.janus.commands.ScriptCommand;
import com.chenpp.janus.commands.SystemCommand;
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
    private static final String PROMPT = "janus> ";
    private static final String HISTORY_FILE = System.getProperty("user.home") + "/.janusgraph_history";

    private final GraphManager graphManager;
    private final Map<String, Command> commands;
    private volatile boolean running = true;

    public JanusGraphCLI(GraphManager graphManager) {
        this.graphManager = graphManager;
        this.commands = initializeCommands();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            graphManager.close();
        }));
    }

    private Map<String, Command> initializeCommands() {
        Map<String, Command> cmds = new LinkedHashMap<>();

        // 索引命令
        IndexCommand indexCmd = new IndexCommand(graphManager);
        cmds.put("index", indexCmd);
        cmds.put("idx", indexCmd);

        // Schema命令
        SchemaCommand schemaCmd = new SchemaCommand(graphManager);
        cmds.put("schema", schemaCmd);
        cmds.put("sc", schemaCmd);

        // 查询命令
        QueryCommand queryCmd = new QueryCommand(graphManager);
        cmds.put("query", queryCmd);
        cmds.put("q", queryCmd);
        cmds.put("gremlin", queryCmd);
        cmds.put("g", queryCmd);

        // 系统命令
        SystemCommand systemCmd = new SystemCommand(graphManager);
        cmds.put("system", systemCmd);
        cmds.put("sys", systemCmd);

        // 批量命令
        BatchCommand batchCmd = new BatchCommand(graphManager);
        cmds.put("batch", batchCmd);

        // 备份命令
        BackupCommand backupCmd = new BackupCommand(graphManager);
        cmds.put("backup", backupCmd);

        // 监控命令
        MonitorCommand monitorCmd = new MonitorCommand(graphManager);
        cmds.put("monitor", monitorCmd);

        // 脚本命令
        ScriptCommand scriptCmd = new ScriptCommand(graphManager, this);
        cmds.put("script", scriptCmd);

        // 图管理命令
        GraphCommand graphCmd = new GraphCommand(graphManager);
        cmds.put("graph", graphCmd);

        // 内置命令
        cmds.put("help", new HelpCommand(commands));
        cmds.put("exit", new ExitCommand());
        cmds.put("quit", new ExitCommand());
        cmds.put("config", new ConfigCommand(graphManager));

        return cmds;
    }

    public void start() throws Exception {
        System.out.println("╔═══════════════════════════════════════════════╗");
        System.out.println("║     JanusGraph Command Line Tool v1.0        ║");
        System.out.println("╚═══════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Type 'help' for available commands, 'exit' to quit");
        System.out.println("Connected to: " + graphManager.getConfigPath());
        if (graphManager.isKerberosEnabled()) {
            System.out.println("Kerberos: enabled (" + graphManager.getLoginUser().getUserName() + ")");
        } else {
            System.out.println("Kerberos: disabled");
        }
        long vertexCount = graphManager.testConnection();
        System.out.println(vertexCount >= 0
                ? "Connection test: OK (vertex count = " + vertexCount + ")"
                : "Connection test: WARNING (see logs; continuing)");
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
                String line = reader.readLine(PROMPT);
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
                    String next = reader.readLine(PROMPT + "  ");
                    if (next == null) {
                        break;
                    }
                    line += next.trim();
                }

                executeCommand(line);

            } catch (org.jline.reader.EndOfFileException e) {
                break;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }

        terminal.close();
        System.out.println("Goodbye!");
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
                    System.err.println("Query execution failed: " + e.getMessage());
                }
                return;
            }
        }

        Command cmd = commands.get(cmdName);
        if (cmd == null) {
            System.err.println("Unknown command: " + cmdName);
            System.out.println("Type 'help' for available commands");
            return;
        }

        try {
            cmd.execute(args);
        } catch (Exception e) {
            System.err.println("Command execution failed: " + e.getMessage());
            if (e.getCause() != null) {
                System.err.println("Cause: " + e.getCause().getMessage());
            }
        }
    }

    // ========== 内置命令 ==========

    class HelpCommand implements Command {
        private final Map<String, Command> commands;

        HelpCommand(Map<String, Command> commands) {
            this.commands = commands;
        }

        @Override
        public void execute(String args) {
            System.out.println("\n=== Available Commands ===");
            System.out.println();
            System.out.println("INDEX OPERATIONS:");
            System.out.println("  index list                    - List all indexes");
            System.out.println("  index create <name> <key> <type> [-u] - Create composite index");
            System.out.println("  index drop <name> [-f]       - Drop index");
            System.out.println("  index status <name>          - Show index status");
            System.out.println("  index enable <name>          - Enable index");
            System.out.println("  index disable <name>         - Disable index");
            System.out.println("  index reindex <name>         - Reindex existing data");
            System.out.println("  index describe <name>        - Describe index details");
            System.out.println("  index repair <name>          - Repair index issues");
            System.out.println();
            System.out.println("SCHEMA OPERATIONS:");
            System.out.println("  schema list                  - List all property keys and labels");
            System.out.println("  schema show <name>           - Show schema details");
            System.out.println("  schema create-key <name> <type> - Create property key");
            System.out.println("  schema create-label <name> <type> - Create label");
            System.out.println("  schema drop-key <name>       - Drop property key");
            System.out.println("  schema stats                 - Show schema statistics");
            System.out.println();
            System.out.println("QUERY OPERATIONS:");
            System.out.println("  query <gremlin>              - Execute Gremlin query");
            System.out.println("  q <gremlin>                  - Alias for query");
            System.out.println("  g <gremlin>                  - Alias for query");
            System.out.println();
            System.out.println("BATCH OPERATIONS:");
            System.out.println("  batch import <file> [--csv|--json] - Import data");
            System.out.println("  batch export <file> [--csv|--json] - Export data");
            System.out.println("  batch delete-all [--force]   - Delete all data");
            System.out.println("  batch count                  - Count vertices and edges");
            System.out.println("  batch sample <n>             - Sample data");
            System.out.println();
            System.out.println("BACKUP OPERATIONS:");
            System.out.println("  backup create [name]         - Create a backup");
            System.out.println("  backup restore <file>        - Restore from backup");
            System.out.println("  backup list                  - List backups");
            System.out.println("  backup delete <file>         - Delete backup");
            System.out.println();
            System.out.println("MONITOR OPERATIONS:");
            System.out.println("  monitor start [interval]     - Start monitoring");
            System.out.println("  monitor stop                 - Stop monitoring");
            System.out.println("  monitor status               - Show current status");
            System.out.println("  monitor query <gremlin>      - Monitor query execution");
            System.out.println();
            System.out.println("SCRIPT OPERATIONS:");
            System.out.println("  script run <file>            - Execute script file");
            System.out.println("  script list                  - List available scripts");
            System.out.println();
            System.out.println("GRAPH OPERATIONS:");
            System.out.println("  graph create <name>          - Create and switch to a new graph");
            System.out.println("  graph current                - Show current graph name");
            System.out.println("  graph list                   - List available graphs");
            System.out.println();
            System.out.println("SYSTEM OPERATIONS:");
            System.out.println("  system info                  - Show system information");
            System.out.println("  system metrics               - Show JVM metrics");
            System.out.println("  system threads               - Show thread information");
            System.out.println("  system gc                    - Force garbage collection");
            System.out.println("  system clear                 - Clear console");
            System.out.println("  system time                  - Show current time");
            System.out.println();
            System.out.println("CONFIG OPERATIONS:");
            System.out.println("  config show                  - Show current configuration");
            System.out.println("  config reload                - Reload configuration");
            System.out.println();
            System.out.println("Shortcuts:");
            System.out.println("  idx = index,  sc = schema,  q = query");
            System.out.println("  g = gremlin,  sys = system");
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
                System.out.println("Configuration reloaded");
            } else {
                System.out.println("Usage: config show|reload");
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
            System.err.println("Failed to start JanusGraph CLI: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}