package com.example.janus.core;

import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.Scanner;

public class GraphManager {

    private static final Logger log = LoggerFactory.getLogger(GraphManager.class);

    private final String configPath;
    private JanusGraph graph;
    private Properties config;
    private boolean isConnected = false;
    private final Scanner scanner = new Scanner(System.in);

    public GraphManager(String configPath) {
        this.configPath = configPath;
        loadConfig();
        connect();
    }

    private void loadConfig() {
        config = new Properties();
        try (FileInputStream fis = new FileInputStream(configPath)) {
            config.load(fis);
            log.info("Configuration loaded from: {}", configPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config: " + configPath, e);
        }
    }

    private void connect() {
        try {
            if (graph != null && !graph.isClosed()) {
                return;
            }
            JanusGraphFactory.Builder builder = JanusGraphFactory.build();
            for (String key : config.stringPropertyNames()) {
                builder.set(key, config.getProperty(key));
            }
            graph = builder.open();
            isConnected = true;
            log.info("JanusGraph connected successfully");
        } catch (Exception e) {
            isConnected = false;
            log.error("Failed to connect to JanusGraph: {}", e.getMessage());
            throw new RuntimeException("Connection failed", e);
        }
    }

    public synchronized JanusGraph getGraph() {
        if (graph == null || graph.isClosed()) {
            connect();
        }
        return graph;
    }

    public synchronized boolean isConnected() {
        return isConnected && graph != null && !graph.isClosed();
    }

    public synchronized void reloadConfig() {
        close();
        loadConfig();
        connect();
        log.info("Configuration reloaded and reconnected");
    }

    public synchronized void close() {
        if (graph != null && !graph.isClosed()) {
            try {
                graph.tx().rollback();
                graph.close();
                isConnected = false;
                log.info("JanusGraph closed");
            } catch (Exception e) {
                log.warn("Error closing graph: {}", e.getMessage());
            }
        }
    }

    public void printConfig() {
        System.out.println("\n=== Current Configuration ===");
        config.forEach((k, v) -> {
            String key = k.toString();
            if (key.contains("password") || key.contains("secret") ||
                    key.contains("keytab") || key.contains("principal")) {
                System.out.println("  " + key + " = ***");
            } else {
                System.out.println("  " + key + " = " + v);
            }
        });
        System.out.println("==============================\n");
    }

    public String getConfigPath() {
        return configPath;
    }

    public Properties getConfig() {
        return config;
    }

    public Scanner getScanner() {
        return scanner;
    }
}