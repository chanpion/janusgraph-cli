package com.chenpp.janus.commands;

import com.chenpp.janus.JanusGraphCLI;
import com.chenpp.janus.core.GraphManager;

import java.io.BufferedReader;
import java.io.File;

/**
 * @author April.Chen
 */
public class ScriptCommand implements Command {
    
    private final GraphManager graphManager;
    private final JanusGraphCLI cli;
    private final File scriptDir;
    
    public ScriptCommand(GraphManager graphManager, JanusGraphCLI cli) {
        this.graphManager = graphManager;
        this.cli = cli;
        this.scriptDir = new File("scripts");
        if (!scriptDir.exists()) {
            scriptDir.mkdirs();
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
            case "run":
                runScript(subArgs);
                break;
            case "list":
            case "ls":
                listScripts();
                break;
            default:
                System.out.println("Unknown subcommand: " + subCmd);
                showUsage();
        }
    }
    
    private void showUsage() {
        System.out.println("\n=== Script Commands ===");
        System.out.println("  script run <file>    - Execute script file");
        System.out.println("  script list          - List available scripts");
        System.out.println();
    }
    
    private void runScript(String filePath) throws Exception {
        if (filePath.isEmpty()) {
            System.out.println("Usage: script run <file>");
            return;
        }
        
        File file = new File(filePath);
        if (!file.isAbsolute()) {
            file = new File(scriptDir, filePath);
        }
        
        if (!file.exists()) {
            System.out.println("Script file not found: " + file.getAbsolutePath());
            return;
        }
        
        System.out.println("Running script: " + file.getName());
        long startTime = System.currentTimeMillis();
        
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }
                
                System.out.print("[" + lineNumber + "] ");
                System.out.println(line);
                cli.executeCommand(line);
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        System.out.println("\n✓ Script completed (took " + duration + "ms)");
    }
    
    private void listScripts() {
        File[] files = scriptDir.listFiles((dir, name) -> 
            name.endsWith(".script") || name.endsWith(".gremlin") || name.endsWith(".txt"));
        
        if (files == null || files.length == 0) {
            System.out.println("No scripts found in " + scriptDir.getAbsolutePath());
            return;
        }
        
        System.out.println("\n=== Available Scripts ===");
        for (File f : files) {
            System.out.println("  " + f.getName() + " (" + f.length() + " bytes)");
        }
        System.out.println();
    }
}
