package com.chenpp.janus.commands;

import com.chenpp.janus.core.GraphManager;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Admin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author April.Chen
 */
public class GraphCommand implements Command {

    private final GraphManager graphManager;

    // JanusGraph 各存储的列族名（长名 + short-cf-names 的短名）；表包含其中至少 3 个即视为图
    private static final Set<String> JANUSGRAPH_CF_NAMES = new HashSet<>(Arrays.asList(
            "edgestore", "e", "edgestore_lock_", "f",
            "graphindex", "g", "graphindex_lock_", "h",
            "janusgraph_ids", "i",
            "system_properties", "s", "system_properties_lock_", "t",
            "txlog", "l", "systemlog", "m"));

    public GraphCommand(GraphManager graphManager) {
        this.graphManager = graphManager;
    }

    @Override
    public void execute(String args) throws Exception {
        if (args.isEmpty()) {
            showUsage();
            return;
        }

        String[] parts = args.split("\\s+", 2);
        String subCmd = parts[0].toLowerCase();
        String subArgs = parts.length > 1 ? parts[1] : "";

        switch (subCmd) {
            case "create":
                createGraph(subArgs);
                break;
            case "use":
                useGraph(subArgs);
                break;
            case "current":
                showCurrent();
                break;
            case "list":
            case "ls":
                listGraphs();
                break;
            case "instances":
                listInstances();
                break;
            case "close-instance":
                closeInstance(subArgs);
                break;
            default:
                System.out.println("Unknown subcommand: " + subCmd);
                showUsage();
        }
    }

    private void showUsage() {
        System.out.println("\n=== Graph Commands ===");
        System.out.println("  graph create <name>   - Create and switch to a new graph");
        System.out.println("  graph use <name>      - Switch to an existing graph");
        System.out.println("  graph current         - Show current graph name");
        System.out.println("  graph list            - List available graphs (HBase tables)");
        System.out.println("  graph instances       - List open JanusGraph instances");
        System.out.println("  graph close-instance <id> - Force-close a stale instance");
        System.out.println();
    }

    private void createGraph(String name) {
        if (name.isEmpty()) {
            System.out.println("Usage: graph create <name>");
            return;
        }

        System.out.print("Creating new graph '" + name + "'. Continue? (y/N): ");
        String confirm = graphManager.getScanner().nextLine().trim().toLowerCase();
        if (!confirm.equals("y") && !confirm.equals("yes")) {
            System.out.println("Operation cancelled");
            return;
        }

        graphManager.switchToGraph(name);
    }

    private void useGraph(String name) {
        if (name.isEmpty()) {
            System.out.println("Usage: graph use <name>");
            return;
        }
        graphManager.switchToGraph(name);
    }

    private void showCurrent() {
        String name = graphManager.getCurrentGraphName();
        System.out.println("Current graph: " + name);
        System.out.println("Connected: " + graphManager.isConnected());
    }

    private void listGraphs() {
        System.out.println("\n=== Available Graphs ===");

        Connection connection = null;
        Admin admin = null;

        try {
            connection = ConnectionFactory.createConnection(buildHBaseConf());
            admin = connection.getAdmin();

            String current = graphManager.getCurrentGraphName();
            List<String> graphTables = new ArrayList<>();
            for (TableName tn : admin.listTableNames()) {
                String name = tn.getNameAsString();
                if (name.startsWith("hbase:")) {
                    continue; // 跳过 HBase 系统表
                }
                // 按列族结构识别：包含 JanusGraph 存储列族的表即视为图；当前图始终保留
                if (isJanusGraphTable(admin, tn) || name.equals(current)) {
                    graphTables.add(name);
                }
            }

            if (graphTables.isEmpty()) {
                System.out.println("  No JanusGraph tables found");
            } else {
                for (String t : graphTables) {
                    String marker = t.equals(current) ? " (*)" : "";
                    System.out.println("  " + t + marker);
                }
                System.out.println("  (*) = current");
            }

        } catch (Exception e) {
            System.out.println("  Failed to list HBase tables: " + e.getMessage());
        } finally {
            try {
                if (admin != null) admin.close();
                if (connection != null) connection.close();
            } catch (Exception ignored) {
            }
        }
        System.out.println();
    }

    /**
     * 按 JanusGraph HBaseStoreManager 的规则构造 HBase 配置，保证与 JanusGraph 用同一套连接参数：
     * 先加载 hbase-site.xml，再透传全部 storage.hbase.ext.* 键，最后映射 storage.hostname/storage.port。
     * 未在配置中出现的键保持 hbase-site.xml 的值，不写死默认值（写死 ZK 端口/znode 会覆盖正确配置）。
     */
    private org.apache.hadoop.conf.Configuration buildHBaseConf() {
        org.apache.hadoop.conf.Configuration hbaseConf =
                org.apache.hadoop.hbase.HBaseConfiguration.create();
        java.util.Properties config = graphManager.getConfig();

        String prefix = "storage.hbase.ext.";
        for (String key : config.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                hbaseConf.set(key.substring(prefix.length()), config.getProperty(key));
            }
        }

        String hosts = config.getProperty("storage.hostname");
        if (hosts != null && !hosts.isEmpty()) {
            hbaseConf.set("hbase.zookeeper.quorum", hosts);
        }
        String port = config.getProperty("storage.port");
        if (port != null && !port.isEmpty()) {
            hbaseConf.set("hbase.zookeeper.property.clientPort", port);
        }

        return hbaseConf;
    }

    private void listInstances() {
        System.out.println("\n=== Open JanusGraph Instances ===");
        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            Set<String> instances = mgmt.getOpenInstances();
            if (instances.isEmpty()) {
                System.out.println("  (none)");
            } else {
                for (String id : instances) {
                    System.out.println("  " + id);
                }
                System.out.println();
                System.out.println("  '(current)' 为当前实例，勿关；其余若来自已退出的进程（旧主机/旧会话），"
                        + "用 'graph close-instance <id>'(id 不含 '(current)') 关闭后，索引即可推进到 REGISTERED。");
            }
        } finally {
            mgmt.commit();
        }
    }

    private void closeInstance(String id) {
        if (id.isEmpty()) {
            System.out.println("Usage: graph close-instance <instance-id>");
            return;
        }
        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            mgmt.forceCloseInstance(id);
            mgmt.commit();
            System.out.println("✓ Instance '" + id + "' force-closed");
        } catch (Exception e) {
            mgmt.rollback();
            System.out.println("✗ Failed to close instance '" + id + "': " + e.getMessage());
        }
    }

    private boolean isJanusGraphTable(Admin admin, TableName tableName) {
        try {
            List<String> cfNames = new ArrayList<>();
            for (byte[] cf : admin.getDescriptor(tableName).getColumnFamilyNames()) {
                cfNames.add(new String(cf, StandardCharsets.UTF_8));
            }
            return looksLikeJanusGraph(cfNames);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean looksLikeJanusGraph(java.util.Collection<String> cfNames) {
        int matches = 0;
        for (String cf : cfNames) {
            if (JANUSGRAPH_CF_NAMES.contains(cf)) {
                matches++;
            }
        }
        return matches >= 3;
    }
}
