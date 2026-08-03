package com.example.janus;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.InputStream;
import java.security.PrivilegedExceptionAction;
import java.util.Properties;

/**
 * JanusGraph HBase with Kerberos 主程序
 * <p>
 * 所有配置项均从 janusgraph-hbase.properties 读取，包括 JAAS、Kerberos 文件路径等。
 * <p>
 * 命令行参数：
 *   java JanusApp [config-path] create &lt;indexName&gt; &lt;propertyKey&gt; &lt;dataType&gt;
 *   java JanusApp [config-path] status &lt;indexName&gt;
 *   java JanusApp [config-path] drop &lt;indexName&gt;
 *   java JanusApp [config-path] list
 *   java JanusApp help
 */
public class JanusApp {

    private static final Logger log = LoggerFactory.getLogger(JanusApp.class);

    public static void main(String[] args) {
        if (args.length == 0 || "help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            return;
        }

        log.info("=== JanusGraph HBase with Kerberos Application Started ===");

        try {
            // 1. 确定配置文件路径（编译期默认，可在配置文件中通过 janusapp.default.config.path 覆盖）
            String configPath = resolveConfigPath(args);
            Properties props = loadProperties(configPath);
            log.info("Configuration loaded from: {}", configPath);

            // 2. 从配置文件中读取应用设置并设置 JVM 系统属性
            setupSystemProperties(props);

            // 3. 显式 Kerberos 登录（提前捕获认证失败，避免等到 HBase 连接时才发现）
            kerberosLogin(props);

            // 4. 解析命令行操作
            String operation = resolveOperation(args, configPath);
            log.info("Operation: {}", operation);

            // 4. 在 Kerberos 认证 Subject 上下文中执行 JanusGraph 操作
            UserGroupInformation loginUser = UserGroupInformation.getCurrentUser();
            loginUser.doAs((PrivilegedExceptionAction<Void>) () -> {
                // 4.1 创建图实例
                JanusGraph graph = GraphFactory.getGraphInstance(configPath);
                log.info("Graph instance created successfully");

                // 4.2 测试连接
                testConnection(graph);

                // 4.3 执行操作
                IndexManager indexManager = new IndexManager(graph);
                executeOperation(indexManager, operation, collectOpArgs(args, configPath));

                return null;
            });

            log.info("=== Application completed successfully ===");

        } catch (Exception e) {
            log.error("Application failed with error", e);
            System.exit(1);
        } finally {
            GraphFactory.closeGraph();
            log.info("Resources cleaned up");
        }
    }

    /**
     * 解析配置文件路径：如果第一个参数是文件路径则使用它，否则使用默认路径
     */
    private static String resolveConfigPath(String[] args) {
        if (args != null && args.length > 0
                && (args[0].startsWith("src/") || args[0].startsWith("/") || args[0].startsWith("file:"))) {
            return args[0];
        }
        return null;
    }

    /**
     * 解析操作类型
     */
    private static String resolveOperation(String[] args, String configPath) {
        if (configPath != null) {
            return args.length > 1 ? args[1] : "";
        }
        return args[0];
    }

    /**
     * 收集操作参数（排除 configPath 和 operation）
     */
    private static String[] collectOpArgs(String[] args, String configPath) {
        int skip = (configPath != null) ? 2 : 1;
        String[] opArgs = new String[args.length - skip];
        System.arraycopy(args, skip, opArgs, 0, args.length - skip);
        return opArgs;
    }

    /**
     * 根据操作类型执行对应的索引操作
     */
    private static void executeOperation(IndexManager indexManager, String operation, String[] args) {
        switch (operation) {
            case "create":
                handleCreate(indexManager, args);
                break;
            case "status":
                handleStatus(indexManager, args);
                break;
            case "drop":
                handleDrop(indexManager, args);
                break;
            case "list":
                handleList(indexManager);
                break;
            default:
                log.error("Unknown operation: {}", operation);
                printUsage();
                System.exit(1);
        }
    }

    private static void handleCreate(IndexManager indexManager, String[] args) {
        if (args.length < 3) {
            log.error("Usage: create <indexName> <propertyKey> <dataType> [elementType]");
            log.error("Example: create byName name String");
            log.error("Example: create byEdgeWeight weight Double edge");
            System.exit(1);
        }

        String indexName = args[0];
        String propertyKey = args[1];
        String dataTypeName = args[2];
        String elementTypeName = (args.length >= 4) ? args[3] : "vertex";

        Class<?> dataType = parseDataType(dataTypeName);
        if (dataType == null) {
            log.error("Unsupported data type: {}. Supported: String, Integer, Long, Double, Boolean", dataTypeName);
            System.exit(1);
        }

        Class<? extends Element> elementType = parseElementType(elementTypeName);
        if (elementType == null) {
            log.error("Unsupported element type: {}. Supported: vertex (v), edge (e)", elementTypeName);
            System.exit(1);
        }

        boolean created = indexManager.createCompositeIndex(indexName, propertyKey, dataType, elementType);
        if (created) {
            log.info("Index '{}' created successfully on {}!", indexName, elementTypeName);
            String status = indexManager.getIndexStatus(indexName);
            log.info("Index status: {}", status);
        } else {
            log.warn("Failed to create {} index '{}' or it already exists", elementTypeName, indexName);
        }
    }

    private static void handleStatus(IndexManager indexManager, String[] args) {
        if (args.length < 1) {
            log.error("Usage: status <indexName>");
            System.exit(1);
        }

        String indexName = args[0];
        String status = indexManager.getIndexStatus(indexName);
        log.info("Index '{}' status: {}", indexName, status);
    }

    private static void handleDrop(IndexManager indexManager, String[] args) {
        if (args.length < 1) {
            log.error("Usage: drop <indexName>");
            System.exit(1);
        }

        String indexName = args[0];
        boolean dropped = indexManager.dropIndex(indexName);
        if (dropped) {
            log.info("Index '{}' dropped successfully", indexName);
        } else {
            log.warn("Failed to drop index '{}'", indexName);
        }
    }

    private static void handleList(IndexManager indexManager) {
        indexManager.listIndexes();
    }

    /**
     * 将元素类型名称字符串解析为对应的 Class 对象
     */
    private static Class<? extends Element> parseElementType(String typeName) {
        switch (typeName.toLowerCase()) {
            case "vertex":
            case "v":
                return Vertex.class;
            case "edge":
            case "e":
                return Edge.class;
            default:
                return null;
        }
    }

    /**
     * 将数据类型名称字符串解析为对应的Class对象
     */
    private static Class<?> parseDataType(String typeName) {
        switch (typeName.toLowerCase()) {
            case "string":
                return String.class;
            case "integer":
            case "int":
                return Integer.class;
            case "long":
                return Long.class;
            case "double":
                return Double.class;
            case "boolean":
                return Boolean.class;
            default:
                return null;
        }
    }

    /**
     * 打印帮助信息
     */
    private static void printUsage() {
        System.out.println("=== JanusGraph HBase Demo - Usage ===");
        System.out.println();
        System.out.println("Create composite index:");
        System.out.println("  java JanusApp [config-path] create <indexName> <propertyKey> <dataType> [elementType]");
        System.out.println("  Example: java JanusApp create byName name String");
        System.out.println("  Example: java JanusApp create byEdgeWeight weight Double edge");
        System.out.println("  elementType: vertex (v) or edge (e), defaults to vertex");
        System.out.println();
        System.out.println("Query index status:");
        System.out.println("  java JanusApp [config-path] status <indexName>");
        System.out.println("  Example: java JanusApp status byNameUnique");
        System.out.println();
        System.out.println("Drop index:");
        System.out.println("  java JanusApp [config-path] drop <indexName>");
        System.out.println("  Example: java JanusApp drop byAge");
        System.out.println();
        System.out.println("List all indexes:");
        System.out.println("  java JanusApp [config-path] list");
        System.out.println();
        System.out.println("Supported data types: String, Integer, Long, Double, Boolean");
    }

    /**
     * 从配置文件中读取应用设置，设置JVM系统属性
     * 如果系统属性已存在（通过 -D 参数传入），则不覆盖
     */
    private static void setupSystemProperties(Properties props) {
        setPropertyIfAbsent("java.security.auth.login.config",
                props.getProperty("janusapp.jaas.config"));
        setPropertyIfAbsent("java.security.krb5.conf",
                props.getProperty("janusapp.krb5.config"));
        setPropertyIfAbsent("zookeeper.sasl.client",
                props.getProperty("janusapp.zookeeper.sasl.client"));
        setPropertyIfAbsent("sun.security.krb5.debug",
                props.getProperty("janusapp.krb5.debug"));
    }

    /**
     * 设置系统属性（如果未设置过）
     */
    private static void setPropertyIfAbsent(String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
            log.info("Set {} to {}", key, value);
        }
    }

    /**
     * 显式 Kerberos 登录
     * 使用 keytab 方式登录，提前发现认证失败（凭证过期、keytab 不匹配等）
     * <p>
     * 关键：必须在 loginUserFromKeytab 之前通过 UserGroupInformation.setConfiguration()
     * 设置 hadoop.security.authentication=kerberos，否则 UGI 不会实际获取 TGT，
     * 后续 HBase RPC SASL 将找不到 Kerberos 凭据。
     */
    private static void kerberosLogin(Properties props) {
        String principal = props.getProperty("janusapp.kerberos.principal");
        String keytab = props.getProperty("janusapp.kerberos.keytab");

        if (principal == null || principal.isEmpty() || keytab == null || keytab.isEmpty()) {
            log.warn("Kerberos principal or keytab not configured, skipping explicit login");
            return;
        }

        try {
            // 1. 设置 Hadoop 安全认证方式为 kerberos，使 UGI.isSecurityEnabled() 返回 true
            Configuration hadoopConf = new Configuration();
            hadoopConf.set("hadoop.security.authentication", "kerberos");
            UserGroupInformation.setConfiguration(hadoopConf);

            // 2. 使用 keytab 执行 Kerberos 登录
            UserGroupInformation.loginUserFromKeytab(principal, keytab);

            log.info("Kerberos login successful: principal={}, keytab={}", principal, keytab);
        } catch (Exception e) {
            throw new RuntimeException("Kerberos login failed: principal=" + principal
                    + ", keytab=" + keytab, e);
        }
    }

    /**
     * 加载Properties文件
     * 当 configPath 为 null 时（用户未在命令行指定），使用编译期默认路径兜底
     */
    private static Properties loadProperties(String configPath) {
        String effectivePath = configPath;
        if (effectivePath == null) {
            effectivePath = "/Users/chenpp/workspace/yuntu-master/hbase-demo/src/main/resources/janusgraph-hbase.properties";
        }
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(effectivePath)) {
            props.load(input);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration: " + effectivePath, e);
        }
        return props;
    }

    /**
     * 测试连接 - 执行简单查询验证
     */
    private static void testConnection(JanusGraph graph) {
        try {
            long count = graph.traversal().V().count().next();
            log.info("Test query successful - Vertex count: {}", count);
        } catch (Exception e) {
            log.warn("Test query failed, but connection may still be valid", e);
        }
    }
}
