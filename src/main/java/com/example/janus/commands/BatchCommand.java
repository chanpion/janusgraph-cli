package com.example.janus.commands;

import com.example.janus.core.GraphManager;
import com.example.janus.utils.JsonFormatter;
import com.example.janus.utils.TableFormatter;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

public class BatchCommand implements Command {
    
    private static final Logger log = LoggerFactory.getLogger(BatchCommand.class);
    private final GraphManager graphManager;
    
    public BatchCommand(GraphManager graphManager) {
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
            case "import":
                doImport(subArgs);
                break;
            case "export":
                doExport(subArgs);
                break;
            case "delete-all":
                deleteAll(subArgs);
                break;
            case "count":
                doCount();
                break;
            case "sample":
                doSample(subArgs);
                break;
            default:
                System.out.println("Unknown subcommand: " + subCmd);
                showUsage();
        }
    }
    
    private void showUsage() {
        System.out.println("\n=== Batch Commands ===");
        System.out.println("  batch import <file> [--csv|--json] - Import data");
        System.out.println("  batch export <file> [--csv|--json] - Export data");
        System.out.println("  batch delete-all [--force]   - Delete all data");
        System.out.println("  batch count                  - Count vertices and edges");
        System.out.println("  batch sample <n>             - Sample data");
        System.out.println();
    }
    
    private void doImport(String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length < 1) {
            System.out.println("Usage: batch import <file> [--csv|--json]");
            return;
        }
        
        String filePath = parts[0];
        boolean isJson = args.contains("--json");
        
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("File not found: " + filePath);
            return;
        }
        
        System.out.println("Importing data from " + filePath + "...");
        long startTime = System.currentTimeMillis();
        
        // TODO: 实现完整的数据导入逻辑
        System.out.println("  ! Import not yet implemented - this is a placeholder");
        System.out.println("  File: " + filePath);
    }
    
    private void doExport(String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length < 1) {
            System.out.println("Usage: batch export <file> [--csv|--json]");
            return;
        }
        
        String filePath = parts[0];
        boolean isJson = args.contains("--json");
        
        System.out.println("Exporting data to " + filePath + "...");
        long startTime = System.currentTimeMillis();
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath, java.nio.charset.StandardCharsets.UTF_8))) {
            var graph = graphManager.getGraph();
            var vertices = graph.traversal().V().limit(1000).toList();
            
            if (isJson) {
                List<Map<String, Object>> data = new ArrayList<>();
                for (var v : vertices) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", v.id());
                    map.put("label", v.label());
                    data.add(map);
                }
                writer.println(JsonFormatter.toJson(data));
            } else {
                writer.println("id,label");
                for (var v : vertices) {
                    writer.println(v.id() + "," + v.label());
                }
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        System.out.println("✓ Export completed (took " + duration + "ms)");
    }
    
    private void deleteAll(String args) throws Exception {
        boolean force = args.contains("--force");
        
        if (!force) {
            System.out.print("This will DELETE ALL data. Are you sure? (y/N): ");
            String confirm = graphManager.getScanner().nextLine().trim().toLowerCase();
            if (!confirm.equals("y") && !confirm.equals("yes")) {
                System.out.println("Operation cancelled");
                return;
            }
            
            System.out.print("Type 'DELETE' to confirm: ");
            String confirm2 = graphManager.getScanner().nextLine().trim();
            if (!confirm2.equals("DELETE")) {
                System.out.println("Operation cancelled");
                return;
            }
        }
        
        System.out.println("Deleting all data...");
        long startTime = System.currentTimeMillis();
        
        var graph = graphManager.getGraph();
        graph.traversal().V().drop().iterate();
        graph.tx().commit();
        
        long duration = System.currentTimeMillis() - startTime;
        System.out.println("✓ All data deleted (took " + duration + "ms)");
    }
    
    private void doCount() {
        var graph = graphManager.getGraph();
        try {
            long vertexCount = graph.traversal().V().count().next();
            long edgeCount = graph.traversal().E().count().next();
            
            System.out.println("\n=== Data Count ===");
            System.out.println("  Vertices: " + vertexCount);
            System.out.println("  Edges: " + edgeCount);
            System.out.println("  Total: " + (vertexCount + edgeCount));
            System.out.println();
        } catch (Exception e) {
            System.out.println("Failed to count data: " + e.getMessage());
        }
    }
    
    private void doSample(String args) {
        int n = 10;
        if (!args.isEmpty()) {
            try {
                n = Integer.parseInt(args.trim());
            } catch (NumberFormatException e) {
                System.out.println("Invalid number: " + args);
                return;
            }
        }
        
        var graph = graphManager.getGraph();
        try {
            var vertices = graph.traversal().V().limit(n).toList();
            System.out.println("\n=== Sample Data (" + vertices.size() + " vertices) ===");
            
            for (int i = 0; i < vertices.size(); i++) {
                Vertex v = vertices.get(i);
                System.out.println("  [" + i + "] Vertex id=" + v.id() + ", label=" + v.label());
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println("Failed to sample data: " + e.getMessage());
        }
    }
}
