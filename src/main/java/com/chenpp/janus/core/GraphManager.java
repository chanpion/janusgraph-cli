package com.chenpp.janus.core;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
            throw new RuntimeException("加载配置失败: " + configPath, e);
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
            return Files.newInputStream(file.toPath());
        }
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null) {
            in = GraphManager.class.getClassLoader().getResourceAsStream(path);
        }
        if (in == null) {
            throw new FileNotFoundException("在文件系统或 classpath 中未找到配置: " + path);
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
            log.error("Failed to connect to JanusGraph: {}", causeChain(e), e);
            throw new RuntimeException("连接失败", e);
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
        System.out.println("\n=== 当前配置 ===");
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
        return config.getProperty(graphNameKey());
    }

    public String getBackend() {
        String backend = config.getProperty("storage.backend", "hbase");
        return backend.trim().toLowerCase();
    }

    public boolean isHBaseBackend() {
        return "hbase".equals(getBackend());
    }

    public boolean isCqlBackend() {
        return "cql".equals(getBackend());
    }

    private String graphNameKey() {
        return isCqlBackend() ? "storage.cql.keyspace" : "storage.hbase.table";
    }

    public boolean switchToGraph(String tableName) {
        if (!isHBaseBackend()) {
            System.out.println("后端 '" + getBackend() + "' 不支持切换图 (仅 HBase)");
            return false;
        }

        String currentTable = config.getProperty(graphNameKey());
        if (tableName == null || tableName.trim().isEmpty()) {
            System.out.println("图名不能为空");
            return false;
        }

        String namespace = getCurrentNamespace();
        if (tableName.contains(":")) {
            System.out.println("仅支持当前库 '" + namespace + "' 下的图名，不支持跨库名: " + tableName);
            return false;
        }

        String target = "default".equals(namespace) ? tableName : namespace + ":" + tableName;

        if (target.equals(currentTable)) {
            System.out.println("已连接到图 '" + target + "'");
            return true;
        }

        System.out.println("正在从 '" + currentTable + "' 切换到 '" + target + "'...");

        close();

        config.setProperty(graphNameKey(), target);
        try {
            connect();
            long count = testConnection();
            if (count >= 0) {
                System.out.println("已切换到图 '" + target + "' (顶点数: " + count + ")");
                return true;
            } else {
                System.out.println("已切换到图 '" + target + "' (新图, 暂无数据)");
                return true;
            }
        } catch (Exception e) {
            System.out.println("切换到图 '" + target + "' 失败: " + causeChain(e));
            config.setProperty(graphNameKey(), currentTable);
            try {
                connect();
                System.out.println("已回退到图 '" + currentTable + "'");
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    public String getCurrentNamespace() {
        return namespaceOf(config.getProperty(graphNameKey()));
    }

    public static String namespaceOf(String tableName) {
        if (tableName == null) {
            return "default";
        }
        int idx = tableName.indexOf(':');
        return idx >= 0 ? tableName.substring(0, idx) : "default";
    }

    /**
     * 展开异常链，逐层拼接 "类名: 消息"，用 " <- " 连接，
     * 便于在只有一行输出的场景（切换图提示）看到真正的根因。
     */
    static String causeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            t = t.getCause();
        }
        return sb.toString();
    }

    private void initSecurity() {
        if (!isHBaseBackend()) {
            return;
        }
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
            throw new RuntimeException("Kerberos 登录失败: principal=" + principal
                    + ", keytab=" + keytab, e);
        }
    }
}