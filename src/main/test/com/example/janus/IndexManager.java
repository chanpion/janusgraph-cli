package com.example.janus;

import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.Cardinality;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.schema.JanusGraphIndex;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.janusgraph.core.schema.SchemaAction;
import org.janusgraph.core.schema.SchemaStatus;
import org.janusgraph.graphdb.database.management.ManagementSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;

/**
 * 索引管理类
 * 负责创建、启用和管理JanusGraph索引
 */
public class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);
    private final JanusGraph graph;

    public IndexManager(JanusGraph graph) {
        this.graph = graph;
    }

    /**
     * 创建复合索引（Composite Index），元素类型默认为 Vertex
     * 适用于基于属性值的精确匹配查询
     *
     * @param indexName   索引名称（必须唯一）
     * @param propertyKey 属性键名
     * @param dataType    属性数据类型
     * @return 是否创建成功
     */
    public boolean createCompositeIndex(String indexName, String propertyKey, Class<?> dataType) {
        return createCompositeIndex(indexName, propertyKey, dataType, Vertex.class);
    }

    /**
     * 创建复合索引（Composite Index），指定元素类型（Vertex 或 Edge）
     * 适用于基于属性值的精确匹配查询
     *
     * @param indexName   索引名称（必须唯一）
     * @param propertyKey 属性键名
     * @param dataType    属性数据类型
     * @param elementType 元素类型（Vertex.class 或 Edge.class）
     * @return 是否创建成功
     */
    public boolean createCompositeIndex(String indexName, String propertyKey, Class<?> dataType, Class<? extends Element> elementType) {
        String typeName = elementTypeName(elementType);
        log.info("Creating composite {} index: {} on property: {}", typeName, indexName, propertyKey);

        try {
            // 回滚所有开放的事务
            graph.tx().rollback();

            // 打开管理事务
            JanusGraphManagement mgmt = graph.openManagement();

            try {
                // 获取或创建PropertyKey
                PropertyKey key;
                if (mgmt.containsPropertyKey(propertyKey)) {
                    key = mgmt.getPropertyKey(propertyKey);
                    log.info("PropertyKey '{}' already exists", propertyKey);
                } else {
                    key = mgmt.makePropertyKey(propertyKey)
                            .dataType(dataType)
                            .cardinality(Cardinality.SINGLE)
                            .make();
                    log.info("PropertyKey '{}' created with type: {}", propertyKey, dataType.getSimpleName());
                }

                // 检查索引是否已存在
                if (mgmt.getGraphIndex(indexName) != null) {
                    log.warn("Index '{}' already exists, skipping creation", indexName);
                    mgmt.commit();
                    return true;
                }

                // 构建复合索引
                JanusGraphManagement.IndexBuilder indexBuilder = mgmt.buildIndex(indexName, elementType).addKey(key);
                if (elementType == Vertex.class){
                 indexBuilder = indexBuilder.unique();
                }
                indexBuilder.buildCompositeIndex();

                // 提交管理事务
                mgmt.commit();
                log.info("Index '{}' definition committed, status: INSTALLED", indexName);

                // 执行 REGISTER_INDEX：将索引从 INSTALLED 推进到 REGISTERED
                boolean registered = registerIndex(indexName);
                if (!registered) {
                    log.error("Index '{}' failed to reach REGISTERED status", indexName);
                    return false;
                }
                log.info("Index '{}' status: REGISTERED", indexName);

                // 启用索引（处理历史数据）
                boolean enabled = enableIndexWithReindex(indexName);
                if (!enabled) {
                    log.error("Index '{}' failed to reach ENABLED status", indexName);
                    return false;
                }
                log.info("Index '{}' status: ENABLED, ready to use!", indexName);

                return true;

            } catch (Exception e) {
                mgmt.rollback();
                log.error("Failed to create index '{}'", indexName, e);
                throw e;
            }

        } catch (Exception e) {
            log.error("Error creating composite index: {}", indexName, e);
            return false;
        }
    }

    /**
     * 等待索引达到指定状态
     */
    private boolean waitForIndexStatus(String indexName, SchemaStatus targetStatus,
                                       long timeout, TemporalUnit unit) {
        try {
            ManagementSystem.awaitGraphIndexStatus(graph, indexName)
                    .status(targetStatus)
                    .timeout(timeout, unit)
                    .call();
            return true;
        } catch (Exception e) {
            log.error("Failed to wait for index '{}' to reach status {}", indexName, targetStatus, e);
            return false;
        }
    }

    /**
     * 执行 REGISTER_INDEX 操作，将索引从 INSTALLED 推进到 REGISTERED
     */
    private boolean registerIndex(String indexName) {
        try {
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    log.error("Index '{}' not found for REGISTER_INDEX", indexName);
                    return false;
                }
                mgmt.updateIndex(index, SchemaAction.REGISTER_INDEX).get();
                mgmt.commit();
                log.info("REGISTER_INDEX action submitted for index: {}", indexName);
            } catch (Exception e) {
                mgmt.rollback();
                log.error("Failed to register index '{}'", indexName, e);
                return false;
            }
        } catch (Exception e) {
            log.error("Error registering index '{}'", indexName, e);
            return false;
        }

        // 等待索引达到 REGISTERED 状态
        return waitForIndexStatus(indexName, SchemaStatus.REGISTERED, 60, ChronoUnit.SECONDS);
    }

    /**
     * 启用索引并执行REINDEX操作（处理历史数据）
     */
    private boolean enableIndexWithReindex(String indexName) {
        try {
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                // 执行REINDEX操作，重建索引包含历史数据
                mgmt.updateIndex(mgmt.getGraphIndex(indexName), SchemaAction.REINDEX).get();
                mgmt.commit();
                log.info("REINDEX action submitted for index: {}", indexName);

                // 等待索引变为ENABLED状态
                boolean enabled = waitForIndexStatus(indexName, SchemaStatus.ENABLED, 300, ChronoUnit.SECONDS);
                if (!enabled) {
                    log.error("Index '{}' REINDEX did not complete within timeout", indexName);
                    return false;
                }
                return true;

            } catch (Exception e) {
                mgmt.rollback();
                log.error("Failed to enable index '{}' with REINDEX", indexName, e);
                return false;
            }

        } catch (Exception e) {
            log.error("Error enabling index '{}'", indexName, e);
            return false;
        }
    }

    /**
     * 检查索引是否存在并返回状态
     */
    public void listIndexes() {
        try {
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                log.info("=== Vertex Index List ===");
                listIndexesForType(mgmt, Vertex.class);
                log.info("=== Edge Index List ===");
                listIndexesForType(mgmt, Edge.class);
            } finally {
                mgmt.commit();
            }
        } catch (Exception e) {
            log.error("Failed to list indexes", e);
        }
    }

    private void listIndexesForType(JanusGraphManagement mgmt, Class<? extends Element> elementType) {
        Iterable<JanusGraphIndex> indexes = mgmt.getGraphIndexes(elementType);
        int count = 0;
        for (JanusGraphIndex idx : indexes) {
            String indexType = idx.isCompositeIndex() ? "COMPOSITE" : "MIXED";
            log.info("  {} ({})", idx.name(), indexType);
            count++;
        }
        if (count == 0) {
            log.info("  (none)");
        }
    }

    private String elementTypeName(Class<? extends Element> elementType) {
        return elementType == Edge.class ? "edge" : "vertex";
    }

    public String getIndexStatus(String indexName) {
        try {
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    return "NOT_FOUND";
                }
                return index.toString();
            } finally {
                mgmt.commit();
            }
        } catch (Exception e) {
            log.error("Failed to get index status for: {}", indexName, e);
            return "ERROR";
        }
    }

    /**
     * 删除索引（慎用）
     * 支持从任意状态（INSTALLED/REGISTERED/ENABLED/DISABLED）删除索引
     */
    public boolean dropIndex(String indexName) {
        log.warn("Dropping index: {} - This action is irreversible!", indexName);

        try {
            JanusGraphManagement mgmt = graph.openManagement();
            JanusGraphIndex index;
            SchemaStatus currentStatus;
            try {
                index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    log.warn("Index '{}' not found", indexName);
                    mgmt.commit();
                    return false;
                }
                currentStatus = index.getIndexStatus(index.getFieldKeys()[0]);
                log.info("Index '{}' current status: {}", indexName, currentStatus);
                mgmt.commit();
            } catch (Exception e) {
                mgmt.rollback();
                log.error("Failed to get index status for '{}'", indexName, e);
                return false;
            }

            // INSTALLED 状态：先 REGISTER_INDEX → DISABLE_INDEX → REMOVE_INDEX
            if (currentStatus == SchemaStatus.INSTALLED) {
                log.info("Index '{}' is INSTALLED, registering first...", indexName);
                if (!registerIndex(indexName)) {
                    log.error("Failed to register index '{}' for removal", indexName);
                    return false;
                }
                currentStatus = SchemaStatus.REGISTERED;
            }

            // REGISTERED 或 ENABLED 状态：先 DISABLE_INDEX
            if (currentStatus == SchemaStatus.REGISTERED || currentStatus == SchemaStatus.ENABLED) {
                mgmt = graph.openManagement();
                try {
                    index = mgmt.getGraphIndex(indexName);
                    mgmt.updateIndex(index, SchemaAction.DISABLE_INDEX).get();
                    mgmt.commit();
                    log.info("DISABLE_INDEX submitted for index: {}", indexName);
                } catch (Exception e) {
                    mgmt.rollback();
                    log.error("Failed to disable index '{}'", indexName, e);
                    return false;
                }

                if (!waitForIndexStatus(indexName, SchemaStatus.DISABLED, 120, ChronoUnit.SECONDS)) {
                    log.error("Index '{}' failed to reach DISABLED status", indexName);
                    return false;
                }
                log.info("Index '{}' status: DISABLED", indexName);
            }

            // DISABLED 状态：执行 DROP_INDEX
            mgmt = graph.openManagement();
            try {
                JanusGraphIndex indexToRemove = mgmt.getGraphIndex(indexName);
                if (indexToRemove == null) {
                    log.warn("Index '{}' not found for removal", indexName);
                    mgmt.commit();
                    return false;
                }
                mgmt.updateIndex(indexToRemove, SchemaAction.DROP_INDEX).get();
                mgmt.commit();
                log.info("Index '{}' removed successfully", indexName);
                return true;
            } catch (Exception e) {
                mgmt.rollback();
                log.error("Failed to remove index '{}'", indexName, e);
                return false;
            }

        } catch (Exception e) {
            log.error("Error dropping index '{}'", indexName, e);
            return false;
        }
    }
}