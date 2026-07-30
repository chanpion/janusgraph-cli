package com.example.janus.commands;

import com.example.janus.core.GraphManager;

import java.util.concurrent.*;

public class MonitorCommand implements Command {
    
    private final GraphManager graphManager;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> monitorTask;
    
    public MonitorCommand(GraphManager graphManager) {
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
            case "start":
                startMonitor(subArgs);
                break;
            case "stop":
                stopMonitor();
                break;
            case "status":
                showStatus();
                break;
            case "query":
                monitorQuery(subArgs);
                break;
            default:
                System.out.println("Unknown subcommand: " + subCmd);
                showUsage();
        }
    }
    
    private void showUsage() {
        System.out.println("\n=== Monitor Commands ===");
        System.out.println("  monitor start [interval] - Start monitoring (interval in seconds)");
        System.out.println("  monitor stop             - Stop monitoring");
        System.out.println("  monitor status           - Show current status");
        System.out.println("  monitor query <gremlin>  - Monitor query execution");
        System.out.println();
    }
    
    private void startMonitor(String args) {
        int interval = 5;
        if (!args.isEmpty()) {
            try {
                interval = Integer.parseInt(args.trim());
            } catch (NumberFormatException e) {
                System.out.println("Invalid interval, using 5s");
            }
        }
        
        if (monitorTask != null && !monitorTask.isDone()) {
            System.out.println("Monitor is already running");
            return;
        }
        
        scheduler = Executors.newSingleThreadScheduledExecutor();
        monitorTask = scheduler.scheduleAtFixedRate(() -> {
            Runtime runtime = Runtime.getRuntime();
            long usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            long totalMem = runtime.totalMemory() / 1024 / 1024;
            
            System.out.print("\r[Monitor] Memory: " + usedMem + "MB/" + totalMem + "MB | " +
                "Time: " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + "   ");
            System.out.flush();
        }, 0, interval, TimeUnit.SECONDS);
        
        System.out.println("✓ Monitor started (interval: " + interval + "s)");
    }
    
    private void stopMonitor() {
        if (monitorTask != null && !monitorTask.isDone()) {
            monitorTask.cancel(false);
        }
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
        scheduler = null;
        monitorTask = null;
        System.out.println("\n✓ Monitor stopped");
    }
    
    private void showStatus() {
        System.out.println("\n=== Monitor Status ===");
        boolean isRunning = monitorTask != null && !monitorTask.isDone();
        System.out.println("  Status: " + (isRunning ? "Running" : "Stopped"));
        
        Runtime runtime = Runtime.getRuntime();
        System.out.println("  Memory Used: " + (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024 + " MB");
        System.out.println("  Memory Total: " + runtime.totalMemory() / 1024 / 1024 + " MB");
        System.out.println("  Graph Connected: " + graphManager.isConnected());
        System.out.println();
    }
    
    private void monitorQuery(String gremlinQuery) {
        if (gremlinQuery.isEmpty()) {
            System.out.println("Usage: monitor query <gremlin>");
            return;
        }

        // TODO: 实现查询监控（多次执行并统计延迟/吞吐量）
        System.out.println("Query monitoring not yet implemented");
        System.out.println("Query: " + gremlinQuery);
    }
}
