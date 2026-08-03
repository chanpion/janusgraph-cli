package com.chenpp.janus.commands;

import com.chenpp.janus.core.GraphManager;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @author April.Chen
 */
public class SystemCommand implements Command {

    private final GraphManager graphManager;
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public SystemCommand(GraphManager graphManager) {
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
            case "info":
            case "status":
                showSystemInfo();
                break;
            case "metrics":
                showMetrics();
                break;
            case "threads":
                showThreads();
                break;
            case "gc":
                forceGC();
                break;
            case "clear":
                clearConsole();
                break;
            case "time":
                showTime();
                break;
            default:
                System.out.println("Unknown subcommand: " + subCmd);
                showUsage();
        }
    }

    private void showUsage() {
        System.out.println("\n=== System Commands ===");
        System.out.println("  system info     - Show system information");
        System.out.println("  system metrics  - Show JVM metrics");
        System.out.println("  system threads  - Show thread information");
        System.out.println("  system gc       - Force garbage collection");
        System.out.println("  system clear    - Clear console");
        System.out.println("  system time     - Show current time");
        System.out.println();
    }

    private void showSystemInfo() {
        System.out.println("\n=== System Information ===");
        System.out.println("  Time: " + sdf.format(new Date()));
        System.out.println("  OS: " + System.getProperty("os.name") +
                " " + System.getProperty("os.version"));
        System.out.println("  Java: " + System.getProperty("java.version"));
        System.out.println("  JVM: " + System.getProperty("java.vm.name"));
        System.out.println("  User: " + System.getProperty("user.name"));
        System.out.println("  Working Dir: " + System.getProperty("user.dir"));
        System.out.println();

        Runtime runtime = Runtime.getRuntime();
        System.out.println("=== Memory ===");
        System.out.println("  Total Memory: " + runtime.totalMemory() / 1024 / 1024 + " MB");
        System.out.println("  Free Memory: " + runtime.freeMemory() / 1024 / 1024 + " MB");
        System.out.println("  Max Memory: " + runtime.maxMemory() / 1024 / 1024 + " MB");
        System.out.println();

        System.out.println("=== Graph Status ===");
        System.out.println("  Connected: " + graphManager.isConnected());
        System.out.println("  Config: " + graphManager.getConfigPath());
        System.out.println();
    }

    private void showMetrics() {
        System.out.println("\n=== JVM Metrics ===");

        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        System.out.println("  CPU Cores: " + osBean.getAvailableProcessors());
        System.out.println("  System Load: " + osBean.getSystemLoadAverage());
        System.out.println();

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        System.out.println("=== Heap Memory ===");
        System.out.println("  Used: " + heapUsage.getUsed() / 1024 / 1024 + " MB");
        System.out.println("  Max: " + heapUsage.getMax() / 1024 / 1024 + " MB");
        System.out.println();

        System.out.println("=== GC ===");
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.println("  " + gcBean.getName() + ": " +
                    gcBean.getCollectionCount() + " collections, " +
                    gcBean.getCollectionTime() + " ms");
        }
        System.out.println();
    }

    private void showThreads() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        System.out.println("\n=== Thread Dump ===");
        System.out.println("  Active Threads: " + threadBean.getThreadCount());
        System.out.println("  Peak Thread Count: " + threadBean.getPeakThreadCount());
        System.out.println("  Daemon Threads: " + threadBean.getDaemonThreadCount());
        System.out.println();
    }

    private void forceGC() {
        System.out.println("Requesting garbage collection...");
        System.gc();
        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.out.println("GC completed");
    }

    private void clearConsole() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private void showTime() {
        System.out.println("Current time: " + sdf.format(new Date()));
    }
}