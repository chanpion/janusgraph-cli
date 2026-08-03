package com.example.janus;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * JanusGraph图实例工厂类
 * 负责加载配置并创建图实例
 */
public class GraphFactory {

    private static final Logger log = LoggerFactory.getLogger(GraphFactory.class);
    private static JanusGraph graphInstance;
    private static final Object lock = new Object();

    /**
     * 使用Properties文件创建图实例（单例模式）
     *
     * @param configPath 配置文件路径
     * @return JanusGraph实例
     */
    public static JanusGraph getGraphInstance(String configPath) {
        if (graphInstance == null) {
            synchronized (lock) {
                if (graphInstance == null) {
                    graphInstance = createGraphFromProperties(configPath);
                }
            }
        }
        return graphInstance;
    }

    /**
     * 从Properties文件加载配置并创建图实例
     */
    private static JanusGraph createGraphFromProperties(String configPath) {
        log.info("Loading configuration from: {}", configPath);

        // 方式一：使用Java原生Properties加载
        Properties props = loadProperties(configPath);

        // 打印关键配置（敏感信息脱敏）
        logConfiguration(props);

        // 创建JanusGraph实例
        JanusGraphFactory.Builder builder = JanusGraphFactory.build();
        for (String key : props.stringPropertyNames()) {
            builder.set(key, props.getProperty(key));
        }
        JanusGraph graph = builder.open();

        log.info("JanusGraph instance created successfully with HBase backend");
        return graph;
    }

    /**
     * 使用Apache Commons Configuration加载
     * 优势：支持配置文件的动态刷新和环境变量覆盖
     */
    @SuppressWarnings("unused")
    private static JanusGraph createGraphWithCommonsConfig(String configPath) {
        try {
            // commons-configuration2 2.10 已移除 File 构造器，改用 builder 加载
            Configuration config = new FileBasedConfigurationBuilder<>(PropertiesConfiguration.class)
                    .configure(new Parameters().fileBased().setFile(new File(configPath)))
                    .getConfiguration();

            // 支持从环境变量覆盖配置
            // 例如：export HBASE_TABLE=my_graph 会覆盖 storage.hbase.table
            String hbaseTable = System.getenv("HBASE_TABLE");
            if (hbaseTable != null && !hbaseTable.isEmpty()) {
                config.setProperty("storage.hbase.table", hbaseTable);
                log.info("Overriding storage.hbase.table from env: {}", hbaseTable);
            }

            String zkHosts = System.getenv("HBASE_ZK_HOSTS");
            if (zkHosts != null && !zkHosts.isEmpty()) {
                config.setProperty("storage.hostname", zkHosts);
                log.info("Overriding storage.hostname from env: {}", zkHosts);
            }

            return JanusGraphFactory.open(config);

        } catch (Exception e) {
            throw new RuntimeException("Failed to load configuration: " + configPath, e);
        }
    }

    /**
     * 使用Java原生Properties加载配置
     */
    private static Properties loadProperties(String configPath) {
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(configPath)) {
            props.load(input);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load properties file: " + configPath, e);
        }
        return props;
    }

    /**
     * 日志打印配置（敏感信息脱敏）
     */
    private static void logConfiguration(Properties props) {
        log.info("=== Configuration Summary ===");
        log.info("storage.backend: {}", props.getProperty("storage.backend"));
        log.info("storage.hostname: {}", props.getProperty("storage.hostname"));
        log.info("storage.hbase.table: {}", props.getProperty("storage.hbase.table"));
        log.info("hbase.security.authentication: {}",
                props.getProperty("storage.hbase.ext.hbase.security.authentication"));
        log.info("kerberos.principal: {}",
                props.getProperty("storage.hbase.ext.hbase.master.kerberos.principal"));
        log.info("============================");
    }

    /**
     * 关闭图实例并释放资源
     */
    public static void closeGraph() {
        if (graphInstance != null) {
            try {
                graphInstance.close();
                log.info("JanusGraph instance closed successfully");
            } catch (Exception e) {
                log.error("Error while closing JanusGraph", e);
            } finally {
                graphInstance = null;
            }
        }
    }
}