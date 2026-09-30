package com.chenpp.janus.core;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.PrivilegedExceptionAction;
import java.util.Properties;
import java.util.Scanner;

/**
 * @author April.Chen
 */
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
        try (InputStream in = openConfigStream(configPath)) {
            config.load(in);
            logConfigSummary();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config: " + configPath, e);
        }
    }

    /**
     * 优先按文件系统路径读取配置，不存在时回退到 classpath 资源，
     * 以便 jar 直接运行时能加载打入包内的默认配置。
     */
    private InputStream openConfigStream(String path) throws IOException {
        File file = new File(path);
        if (file.isFile()) {
            log.info("Configuration loaded from file: {}", file.getAbsolutePath());
            return new FileInputStream(file);
        }
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null) {
            in = GraphManager.class.getClassLoader().getResourceAsStream(path);
        }
        if (in == null) {
            throw new FileNotFoundException("Config not found on filesystem or classpath: " + path);
        }
        log.info("Configuration loaded from classpath: {}", path);
        return in;
    }

    private void logConfigSummary() {
        log.info("=== Configuration Summary ===");
        config.forEach((k, v) -> {
            String key = k.toString();
            log.info("  {} = {}", key, isSensitive(key) ? "***" : v);
        });
        log.info("=============================");
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
            System.out.println("  " + key + " = " + (isSensitive(key) ? "***" : v));
        });
        System.out.println("==============================\n");
    }

    private static boolean isSensitive(String key) {
        return key.contains("password") || key.contains("secret")
                || key.contains("keytab") || key.contains("principal");
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
        } finally {
            g.tx().rollback();
        }
    }

    public String getCurrentGraphName() {
        return config.getProperty("storage.hbase.table");
    }

    public boolean switchToGraph(String tableName) {
        String currentTable = config.getProperty("storage.hbase.table");
        if (tableName == null || tableName.trim().isEmpty()) {
            System.out.println("Graph name cannot be empty");
            return false;
        }

        if (tableName.equals(currentTable)) {
            System.out.println("Already connected to graph '" + tableName + "'");
            return true;
        }

        System.out.println("Switching from '" + currentTable + "' to '" + tableName + "'...");

        close();

        config.setProperty("storage.hbase.table", tableName);
        try {
            connect();
            long count = testConnection();
            if (count >= 0) {
                System.out.println("Switched to graph '" + tableName + "' (vertex count: " + count + ")");
                return true;
            } else {
                System.out.println("Switched to graph '" + tableName + "' (new graph, no data yet)");
                return true;
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("Failed to switch to graph '" + tableName + "': " + cause.getMessage());
            config.setProperty("storage.hbase.table", currentTable);
            try {
                connect();
                System.out.println("Reverted to graph '" + currentTable + "'");
            } catch (Exception ignored) {
            }
            return false;
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