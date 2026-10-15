package com.chenpp.janus.commands;

import com.alibaba.fastjson.JSON;
import com.chenpp.janus.core.GraphManager;
import com.chenpp.janus.utils.TableFormatter;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
                System.out.println("未知子命令: " + subCmd);
                showUsage();
        }
    }

    private void showUsage() {
        System.out.println("\n=== 备份命令 ===");
        System.out.println("  backup create [name]    - 创建备份");
        System.out.println("  backup restore <file>   - 从备份恢复");
        System.out.println("  backup list             - 列出备份");
        System.out.println("  backup delete <file>    - 删除备份");
        System.out.println();
    }

    private void createBackup(String name) throws Exception {
        if (name.isEmpty()) {
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            name = "backup-" + timestamp;
        }

        File backupFile = new File(backupDir, name + ".json");
        System.out.println("正在创建备份: " + backupFile.getName());

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

        try (BufferedWriter writer = Files.newBufferedWriter(backupFile.toPath(), StandardCharsets.UTF_8)) {
            writer.write(JSON.toJSONString(data, true));
            writer.newLine();
        }

        System.out.println("✓ 备份已创建: " + backupFile.getAbsolutePath());
        System.out.println("  顶点数: " + data.size());
    }

    private void restoreBackup(String filePath) throws Exception {
        if (filePath.isEmpty()) {
            System.out.println("用法: backup restore <file>");
            return;
        }

        File file = new File(filePath);
        if (!file.isAbsolute()) {
            file = new File(backupDir, filePath);
        }

        if (!file.exists()) {
            System.out.println("备份文件不存在: " + file.getAbsolutePath());
            return;
        }

        System.out.print("恢复将覆盖现有数据，是否继续? (y/N): ");
        String confirm = graphManager.getScanner().nextLine().trim().toLowerCase();
        if (!confirm.equals("y") && !confirm.equals("yes")) {
            System.out.println("操作已取消");
            return;
        }

        System.out.println("正在从以下文件恢复: " + file.getName());

        System.out.println("  ! 恢复功能尚未实现，此为占位提示");
        System.out.println("  备份文件: " + file.getAbsolutePath());
    }

    private void listBackups() {
        File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".json"));

        if (files == null || files.length == 0) {
            System.out.println("未找到备份");
            return;
        }

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        System.out.println("\n=== 可用备份 ===");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"名称", "大小", "日期", "路径"});

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (File f : files) {
            rows.add(new String[]{
                    f.getName(),
                    formatSize(f.length()),
                    sdf.format(new Date(f.lastModified())),
                    f.getAbsolutePath()
            });
        }

        TableFormatter.printTable(rows);
    }

    private void deleteBackup(String filePath) {
        if (filePath.isEmpty()) {
            System.out.println("用法: backup delete <file>");
            return;
        }

        File file = new File(filePath);
        if (!file.isAbsolute()) {
            file = new File(backupDir, filePath);
        }

        if (!file.exists()) {
            System.out.println("备份文件不存在: " + file.getAbsolutePath());
            return;
        }

        if (file.delete()) {
            System.out.println("✓ 备份已删除: " + file.getName());
        } else {
            System.out.println("✗ 删除备份失败");
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
