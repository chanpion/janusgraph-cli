package com.example.janus.core;

import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.Properties;
import java.util.Scanner;

public class GraphManager {

    private static final Logger log = LoggerFactory.getLogger(GraphManager.class);

    private final String configPath;
    private JanusGraph graph;
    private Properties config;
    private boolean isConnected = false;
    private UserGroupInformation loginUser;
    private final Scanner scanner = new Scanner(System.in);

    public GraphManager(String configPath) {
        this.configPath = configPath;
        loadConfig();
        initSecurity();
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
            if (loginUser != null) {
                graph = loginUser.doAs((PrivilegedExceptionAction<JanusGraph>) builder::open);
            } else {
                graph = builder.open();
            }
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
        initSecurity();
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

    public UserGroupInformation getLoginUser() {
        return loginUser;
    }

    public boolean isKerberosEnabled() {
        return loginUser != null;
    }

    public long testConnection() {
        JanusGraph g = getGraph();
        try {
            long count = g.traversal().V().count().next();
            log.info("Test query successful - Vertex count: {}", count);
            return count;
        } catch (Exception e) {
            log.warn("Test query failed, but connection may still be valid", e);
            return -1L;
        }
    }

    private void initSecurity() {
        setupSystemProperties(config);
        if (loginUser == null) {
            kerberosLogin(config);
        }
    }

    private void setupSystemProperties(Properties props) {
        setPropertyIfAbsent("java.security.auth.login.config",
                props.getProperty("janusapp.jaas.config"));
        setPropertyIfAbsent("java.security.krb5.conf",
                props.getProperty("janusapp.krb5.config"));
        setPropertyIfAbsent("zookeeper.sasl.client",
                props.getProperty("janusapp.zookeeper.sasl.client"));
        setPropertyIfAbsent("sun.security.krb5.debug",
                props.getProperty("janusapp.krb5.debug"));
    }

    private static void setPropertyIfAbsent(String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private void kerberosLogin(Properties props) {
        String principal = props.getProperty("janusapp.kerberos.principal");
        String keytab = props.getProperty("janusapp.kerberos.keytab");

        if (principal == null || principal.isEmpty() || keytab == null || keytab.isEmpty()) {
            log.warn("Kerberos principal or keytab not configured, skipping explicit login");
            return;
        }

        try {
            Configuration hadoopConf = new Configuration();
            hadoopConf.set("hadoop.security.authentication", "kerberos");
            UserGroupInformation.setConfiguration(hadoopConf);

            UserGroupInformation.loginUserFromKeytab(principal, keytab);
            this.loginUser = UserGroupInformation.getLoginUser();

            log.info("Kerberos login successful: principal={}, keytab={}", principal, keytab);
        } catch (Exception e) {
            throw new RuntimeException("Kerberos login failed: principal=" + principal
                    + ", keytab=" + keytab, e);
        }
    }
}