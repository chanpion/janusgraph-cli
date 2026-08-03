package com.chenpp.janus.commands;

import com.chenpp.janus.core.GraphManager;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author April.Chen
 */
public class BackupCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(BackupCommand.class);
    private final GraphManager graphManager;
    private final File backupDir;

    public BackupCommand(GraphManager graphManager) {
        this.graphManager = graphManager;
        this.backupDir = new File("backups");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
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
                createBackup(subArgs);
                break;
            case "restore":
                restoreBackup(subArgs);
                break;
            case "list":
            case "ls":
                listBackups();
                break;
            case "delete":
            case "rm":
                deleteBackup(subArgs);
                break;
            default:
                System.out.println("Unknown subcommand: " + subCmd);
                showUsage();
        }
    }

    private void showUsage() {
        System.out.println("\n=== Backup Commands ===");
        System.out.println("  backup create [name]    - Create a backup");
        System.out.println("  backup restore <file>   - Restore from backup");
        System.out.println("  backup list             - List backups");
        System.out.println("  backup delete <file>    - Delete backup");
        System.out.println();
    }

    private void createBackup(String name) throws Exception {
        if (name.isEmpty()) {
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            name = "backup-" + timestamp;
        }

        File backupFile = new File(backupDir, name + ".json");
        System.out.println("Creating backup: " + backupFile.getName());

        JanusGraph graph = graphManager.getGraph();
        List<Vertex> vertices = graph.traversal().V().toList();

        List<Map<String, Object>> data = new ArrayList<>();
        for (Vertex v : vertices) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", v.id());
            map.put("label", v.label());

            Map<String, Object> props = new LinkedHashMap<>();
            v.properties().forEachRemaining(p -> props.put(p.key(), p.value()));
            map.put("properties", props);

            data.add(map);
        }

        try (PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(backupFile), java.nio.charset.StandardCharsets.UTF_8))) {
            writer.println(com.alibaba.fastjson.JSON.toJSONString(data, true));
        }

        System.out.println("✓ Backup created: " + backupFile.getAbsolutePath());
        System.out.println("  Vertices: " + data.size());
    }

    private void restoreBackup(String filePath) throws Exception {
        if (filePath.isEmpty()) {
            System.out.println("Usage: backup restore <file>");
            return;
        }

        File file = new File(filePath);
        if (!file.isAbsolute()) {
            file = new File(backupDir, filePath);
        }

        if (!file.exists()) {
            System.out.println("Backup file not found: " + file.getAbsolutePath());
            return;
        }

        System.out.print("Restore will overwrite existing data. Continue? (y/N): ");
        String confirm = graphManager.getScanner().nextLine().trim().toLowerCase();
        if (!confirm.equals("y") && !confirm.equals("yes")) {
            System.out.println("Operation cancelled");
            return;
        }

        System.out.println("Restoring from: " + file.getName());

        // TODO: 实现完整的数据恢复逻辑
        System.out.println("  ! Restore not yet implemented - this is a placeholder");
        System.out.println("  Backup file: " + file.getAbsolutePath());
    }

    private void listBackups() {
        File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".json"));

        if (files == null || files.length == 0) {
            System.out.println("No backups found");
            return;
        }

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        System.out.println("\n=== Available Backups ===");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Name", "Size", "Date", "Path"});

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (File f : files) {
            rows.add(new String[]{
                    f.getName(),
                    formatSize(f.length()),
                    sdf.format(new Date(f.lastModified())),
                    f.getAbsolutePath()
            });
        }

        com.chenpp.janus.utils.TableFormatter.printTable(rows);
    }

    private void deleteBackup(String filePath) {
        if (filePath.isEmpty()) {
            System.out.println("Usage: backup delete <file>");
            return;
        }

        File file = new File(filePath);
        if (!file.isAbsolute()) {
            file = new File(backupDir, filePath);
        }

        if (!file.exists()) {
            System.out.println("Backup file not found: " + file.getAbsolutePath());
            return;
        }

        if (file.delete()) {
            System.out.println("✓ Backup deleted: " + file.getName());
        } else {
            System.out.println("✗ Failed to delete backup");
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
