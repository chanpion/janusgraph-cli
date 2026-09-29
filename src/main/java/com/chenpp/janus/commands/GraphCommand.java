package com.chenpp.janus.commands;

import com.chenpp.janus.core.GraphManager;
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

        org.apache.hadoop.conf.Configuration hbaseConf = null;
        Connection connection = null;
        Admin admin = null;

        try {
            hbaseConf = org.apache.hadoop.hbase.HBaseConfiguration.create();
            hbaseConf.set("hbase.zookeeper.quorum",
                    graphManager.getConfig().getProperty("storage.hostname"));
            hbaseConf.set("hbase.zookeeper.property.clientPort",
                    graphManager.getConfig().getProperty("storage.hbase.ext.hbase.zookeeper.property.clientPort", "2181"));
            hbaseConf.set("zookeeper.znode.parent",
                    graphManager.getConfig().getProperty("storage.hbase.ext.zookeeper.znode.parent", "/hbase"));

            connection = ConnectionFactory.createConnection(hbaseConf);
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
