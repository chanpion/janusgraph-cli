package com.chenpp.janus.core;

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
import org.janusgraph.graphdb.database.management.GraphIndexStatusReport;
import org.janusgraph.graphdb.database.management.ManagementSystem;
import org.janusgraph.graphdb.types.vertices.JanusGraphSchemaVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author April.Chen
 */
public class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);
    private final GraphManager graphManager;
    private JanusGraph graph;

    public IndexManager(GraphManager graphManager) {
        this.graphManager = graphManager;
        this.graph = graphManager.getGraph();
    }

    public boolean createCompositeIndex(String indexName, String propertyKey,
                                        Class<?> dataType, Class<? extends Element> elementClass, boolean unique) {
        log.info("Creating composite index: {} on property: {}", indexName, propertyKey);

        try {
            graph.tx().rollback();
            JanusGraphManagement mgmt = graph.openManagement();

            try {
                PropertyKey key;
                if (mgmt.containsPropertyKey(propertyKey)) {
                    key = mgmt.getPropertyKey(propertyKey);
                } else {
                    key = mgmt.makePropertyKey(propertyKey)
                            .dataType(dataType)
                            .cardinality(Cardinality.SINGLE)
                            .make();
                }

                if (mgmt.getGraphIndex(indexName) != null) {
                    log.warn("Index '{}' already exists", indexName);
                    mgmt.commit();
                    return true;
                }

                JanusGraphManagement.IndexBuilder builder = mgmt.buildIndex(indexName, elementClass).addKey(key);
                if (unique) {
                    builder.unique();
                }
                builder.buildCompositeIndex();

                mgmt.commit();
                log.info("Index '{}' definition committed", indexName);

                if (!waitForIndexStatus(indexName, SchemaStatus.REGISTERED, 300)) {
                    log.error("Index '{}' waited to REGISTERED timeout", indexName);
                    return false;
                }
                return enableIndexWithReindex(indexName);

            } catch (Exception e) {
                if (mgmt.isOpen()) {
                    mgmt.rollback();
                }
                throw e;
            }

        } catch (Exception e) {
            log.error("Error creating index '{}'", indexName, e);
            return false;
        }
    }

    public boolean createCompositeIndex(String indexName, String propertyKey, Class<?> dataType) {
        return createCompositeIndex(indexName, propertyKey, dataType, Vertex.class, true);
    }

    private boolean waitForIndexStatus(String indexName, SchemaStatus targetStatus, int timeoutSeconds) {
        try {
            GraphIndexStatusReport report = ManagementSystem.awaitGraphIndexStatus(graph, indexName)
                    .status(targetStatus)
                    .timeout(timeoutSeconds, java.time.temporal.ChronoUnit.SECONDS)
                    .call();
            if (!report.getSucceeded()) {
                log.warn("Index '{}' did not reach {} within {}s (notConverged: {})",
                        indexName, targetStatus, timeoutSeconds, report.getNotConvergedKeys());
            }
            return report.getSucceeded();
        } catch (Exception e) {
            log.error("Failed to wait for index '{}' to reach status {}", indexName, targetStatus, e);
            return false;
        }
    }

    private boolean registerIndex(String indexName) {
        String status = getIndexStatus(indexName);
        log.info("Current status of index '{}' is {}", indexName, status);
        if ("REGISTERED".equals(status) || "ENABLED".equals(status)) {
            log.info("Index '{}' is already {}", indexName, status);
            return true;
        }

        try {
            graph.tx().rollback();
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    log.error("Index '{}' not found for REGISTER_INDEX", indexName);
                    return false;
                }
                // 先提交管理事务、再等状态：不在 .get() 阻塞期间持有管理事务，
                // 避免与后台索引任务的收尾提交争同一把 schema 锁（本地争锁不重试，会直接失败）
                mgmt.updateIndex(index, SchemaAction.REGISTER_INDEX);
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

        if (!waitForIndexStatus(indexName, SchemaStatus.REGISTERED, 300)) {
            // 等待超时：可能是本地 schema 缓存未失效，重开图后从存储读取真实状态
            log.warn("REGISTER_INDEX wait timed out for '{}', reopening graph to refresh schema cache", indexName);
            refreshGraph();
            String actual = getIndexStatus(indexName);
            if ("REGISTERED".equals(actual) || "ENABLED".equals(actual)) {
                log.info("Index '{}' is actually {} after graph reopen (schema cache was stale)", indexName, actual);
                return true;
            }
            log.error("Index '{}' confirmed stuck at {} after graph reopen", indexName, actual);
            return false;
        }
        return true;
    }

    private void refreshGraph() {
        try {
            graphManager.close();
            this.graph = graphManager.getGraph();
            log.info("Graph reopened to refresh schema cache");
        } catch (Exception e) {
            log.error("Failed to reopen graph: {}", e.getMessage());
        }
    }

    private boolean enableIndexWithReindex(String indexName) {
        try {
            graph.tx().rollback();
            String currentStatus = getIndexStatus(indexName);
            if ("ENABLED".equals(currentStatus)) {
                log.info("Index '{}' is already ENABLED", indexName);
                return true;
            }
            if ("INSTALLED".equals(currentStatus)) {
                log.warn("Index '{}' is still INSTALLED, attempting REGISTER_INDEX first", indexName);
                if (!registerIndex(indexName)) {
                    log.error("Index '{}' still stuck at INSTALLED after registration retry", indexName);
                    return false;
                }
            }

            JanusGraphManagement mgmt = graph.openManagement();
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    log.error("Index '{}' not found for REINDEX", indexName);
                    mgmt.rollback();
                    return false;
                }
                mgmt.updateIndex(index, SchemaAction.REINDEX);
                mgmt.commit();
                log.info("REINDEX action submitted for index: {}", indexName);

                return waitForIndexStatus(indexName, SchemaStatus.ENABLED, 300);

            } catch (Exception e) {
                mgmt.rollback();
                log.error("Failed to enable index '{}'", indexName, e);
                return false;
            }
        } catch (Exception e) {
            log.error("Error enabling index '{}'", indexName, e);
            return false;
        }
    }

    /**
     * 列出所有索引及其展示信息。索引的读取依赖管理事务，故所有字段都在事务内取出，
     * 返回可脱离事务使用的普通数据。
     */
    public List<Map<String, Object>> listIndexes() {
        List<Map<String, Object>> result = new ArrayList<>();
        graph.tx().rollback();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            List<JanusGraphIndex> indexes = new ArrayList<>();
            for (JanusGraphIndex idx : mgmt.getGraphIndexes(Vertex.class)) {
                indexes.add(idx);
            }
            for (JanusGraphIndex idx : mgmt.getGraphIndexes(Edge.class)) {
                if (indexes.stream().noneMatch(i -> i.name().equals(idx.name()))) {
                    indexes.add(idx);
                }
            }

            for (JanusGraphIndex idx : indexes) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", idx.name());
                info.put("status", idx.getFieldKeys().length > 0
                        ? String.valueOf(idx.getIndexStatus(idx.getFieldKeys()[0])) : "UNKNOWN");
                info.put("type", idx.isMixedIndex() ? "Mixed" : "Composite");
                info.put("element", idx.getIndexedElement().getSimpleName());

                List<String> keyNames = new ArrayList<>();
                for (PropertyKey key : idx.getFieldKeys()) {
                    keyNames.add(key.name());
                }
                info.put("keys", keyNames);
                if (idx.isUnique()) {
                    info.put("unique", true);
                }
                result.add(info);
            }
        } finally {
            mgmt.commit();
        }
        return result;
    }

    public String getIndexStatus(String indexName) {
        try {
            graph.tx().rollback();
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    return "NOT_FOUND";
                }
                return getIndexStatusForDisplay(index);
            } finally {
                mgmt.commit();
            }
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    public boolean enableIndex(String indexName) {
        try {
            graph.tx().rollback();
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    log.warn("Index '{}' not found", indexName);
                    mgmt.rollback();
                    return false;
                }
                mgmt.updateIndex(index, SchemaAction.ENABLE_INDEX);
                mgmt.commit();
                return waitForIndexStatus(indexName, SchemaStatus.ENABLED, 60);
            } catch (IllegalArgumentException e) {
                mgmt.rollback();
                System.out.println("无法启用索引 '" + indexName + "': 当前状态为 "
                        + getIndexStatus(indexName)
                        + "。仅 DISABLED 状态的索引可启用；INSTALLED/REGISTERED 请用 'index reindex "
                        + indexName + "'。");
                return false;
            } catch (Exception e) {
                if (mgmt.isOpen()) {
                    mgmt.rollback();
                }
                throw e;
            }
        } catch (Exception e) {
            log.error("Failed to enable index '{}': {}", indexName, e.getMessage());
            return false;
        }
    }

    public boolean disableIndex(String indexName) {
        try {
            graph.tx().rollback();
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    log.warn("Index '{}' not found", indexName);
                    mgmt.rollback();
                    return false;
                }
                mgmt.updateIndex(index, SchemaAction.DISABLE_INDEX);
                mgmt.commit();
                return waitForIndexStatus(indexName, SchemaStatus.DISABLED, 60);
            } catch (IllegalArgumentException e) {
                mgmt.rollback();
                System.out.println("无法禁用索引 '" + indexName + "': 当前状态为 "
                        + getIndexStatus(indexName) + "。仅 ENABLED 状态的索引可禁用。");
                return false;
            } catch (Exception e) {
                if (mgmt.isOpen()) {
                    mgmt.rollback();
                }
                throw e;
            }
        } catch (Exception e) {
            log.error("Failed to disable index '{}': {}", indexName, e.getMessage());
            return false;
        }
    }

    public boolean reindex(String indexName) {
        try {
            graph.tx().rollback();
            String currentStatus = getIndexStatus(indexName);
            if ("ENABLED".equals(currentStatus)) {
                log.info("Index '{}' is already ENABLED", indexName);
                return true;
            }
            if ("INSTALLED".equals(currentStatus)) {
                log.warn("Index '{}' is INSTALLED, running REGISTER_INDEX first", indexName);
                if (!registerIndex(indexName)) {
                    log.error("Index '{}' still stuck at INSTALLED after REGISTER_INDEX", indexName);
                    return false;
                }
            }

            JanusGraphManagement mgmt = graph.openManagement();
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    log.warn("Index '{}' not found", indexName);
                    mgmt.rollback();
                    return false;
                }
                mgmt.updateIndex(index, SchemaAction.REINDEX);
                mgmt.commit();
                return waitForIndexStatus(indexName, SchemaStatus.ENABLED, 600);
            } catch (Exception e) {
                if (mgmt.isOpen()) {
                    mgmt.rollback();
                }
                throw e;
            }
        } catch (Exception e) {
            log.error("Failed to reindex '{}'", indexName, e);
            return false;
        }
    }

    public boolean dropIndexSafely(String indexName) {
        log.warn("Dropping index: {}", indexName);

        try {
            graph.tx().rollback();
            JanusGraphManagement mgmt = graph.openManagement();
            JanusGraphIndex index = mgmt.getGraphIndex(indexName);
            if (index == null) {
                mgmt.commit();
                log.warn("Index '{}' does not exist", indexName);
                return true;
            }
            SchemaStatus status = getIndexStatusSafely(index);
            mgmt.commit();

            if (status == null) {
                log.warn("Index '{}' has no status", indexName);
                return false;
            }

            if (status == SchemaStatus.INSTALLED) {
                log.info("Index '{}' is INSTALLED, registering first...", indexName);
                if (!registerIndex(indexName)) {
                    return false;
                }
                status = SchemaStatus.REGISTERED;
            }

            if (status == SchemaStatus.REGISTERED || status == SchemaStatus.ENABLED) {
                if (!disableIndex(indexName)) {
                    return false;
                }
                status = SchemaStatus.DISABLED;
            }

            if (status == SchemaStatus.DISABLED) {
                return removeIndexDirectly(indexName);
            }

            log.error("Index '{}' in unexpected state '{}'", indexName, status);
            return false;

        } catch (Exception e) {
            log.error("Error dropping index '{}': {}", indexName, e.getMessage());
            return false;
        }
    }

    private boolean removeIndexDirectly(String indexName) {
        try {
            graph.tx().rollback();
            JanusGraphManagement mgmt = graph.openManagement();
            JanusGraphManagement.IndexJobFuture jobFuture;
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    mgmt.commit();
                    return true;
                }

                String status = getIndexStatusForDisplay(index);
                if (!status.contains("DISABLED")) {
                    log.error("Index '{}' is in state '{}', not DISABLED", indexName, status);
                    mgmt.commit();
                    return false;
                }

                // REMOVE_INDEX 只负责删除索引数据（异步 scan job），定义删除见 dropIndexDefinition
                jobFuture = mgmt.updateIndex(index, SchemaAction.REMOVE_INDEX);
                mgmt.commit();

            } catch (Exception e) {
                if (mgmt.isOpen()) {
                    mgmt.rollback();
                }
                throw e;
            }

            awaitIndexDataRemoval(jobFuture, indexName);
            return dropIndexDefinition(indexName);
        } catch (Exception e) {
            log.error("Failed to remove index '{}': {}", indexName, e.getMessage());
            return false;
        }
    }

    private void awaitIndexDataRemoval(JanusGraphManagement.IndexJobFuture jobFuture, String indexName) {
        if (jobFuture == null) {
            return;
        }
        try {
            jobFuture.get(300, TimeUnit.SECONDS);
            log.info("Index data removal job finished for '{}'", indexName);
        } catch (Exception e) {
            log.warn("Index data removal job for '{}' did not finish cleanly ({}); "
                    + "index definition will still be removed", indexName, e.getMessage());
        }
    }

    /**
     * JanusGraph 0.6.x 的 REMOVE_INDEX 只删除索引数据、不删除索引定义（1.0 才拆出 DROP_INDEX），
     * 这里补上删除索引 schema 顶点这一步，否则索引会一直以 DISABLED 状态留在 schema 中。
     */
    private boolean dropIndexDefinition(String indexName) {
        graph.tx().rollback();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            JanusGraphIndex index = mgmt.getGraphIndex(indexName);
            if (index == null) {
                mgmt.commit();
                return true;
            }
            JanusGraphSchemaVertex schemaVertex = ((ManagementSystem) mgmt).getSchemaVertex(index);
            schemaVertex.remove();
            mgmt.commit();

        } catch (Exception e) {
            if (mgmt.isOpen()) {
                mgmt.rollback();
            }
            throw e;
        }

        // 删除索引定义不会触发 schema 缓存失效（它不在 management 的 updatedTypes 中），
        // 重开图清除本地缓存后再确认删除结果
        refreshGraph();
        String status = getIndexStatus(indexName);
        if ("NOT_FOUND".equals(status)) {
            log.info("Index '{}' removed successfully", indexName);
            return true;
        }
        log.error("Index '{}' still present after removal (status: {})", indexName, status);
        return false;
    }

    private SchemaStatus getIndexStatusSafely(org.janusgraph.core.schema.JanusGraphIndex index) {
        PropertyKey[] keys = index.getFieldKeys();
        if (keys.length == 0) {
            return null;
        }
        PropertyKey firstKey = keys[0];
        if (firstKey == null) {
            return null;
        }
        return index.getIndexStatus(firstKey);
    }

    private String getIndexStatusForDisplay(org.janusgraph.core.schema.JanusGraphIndex index) {
        SchemaStatus status = getIndexStatusSafely(index);
        return status != null ? status.name() : "UNKNOWN";
    }
}