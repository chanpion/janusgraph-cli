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
import org.janusgraph.graphdb.database.management.ManagementSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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

                boolean registered = registerIndex(indexName);
                if (!registered) {
                    // 超时后检查实际状态，可能已 REGISTERED
                    String status = getIndexStatus(indexName);
                    if ("REGISTERED".equals(status) || "ENABLED".equals(status)) {
                        log.info("Index '{}' actually reached {} despite timeout, proceeding", indexName, status);
                    } else {
                        log.error("Index '{}' stuck at {}, cannot proceed to REINDEX", indexName, status);
                        return false;
                    }
                }


                return enableIndexWithReindex(indexName);

            } catch (Exception e) {
                mgmt.rollback();
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
            ManagementSystem.awaitGraphIndexStatus(graph, indexName)
                    .status(targetStatus)
                    .timeout(timeoutSeconds, java.time.temporal.ChronoUnit.SECONDS)
                    .call();
        } catch (Exception e) {
            log.error("Failed to wait for index '{}' to reach status {}", indexName, targetStatus, e);
            return false;
        }
        // awaitGraphIndexStatus().call() 超时时只记录日志、不抛异常，必须复核实际状态
        String actual = getIndexStatus(indexName);
        if (!targetStatus.name().equals(actual)) {
            log.warn("Index '{}' did not reach {} within {}s (current: {})",
                    indexName, targetStatus, timeoutSeconds, actual);
            return false;
        }
        return true;
    }

    private boolean registerIndex(String indexName) {
        try {
            graph.tx().rollback();
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
            // 先检查当前状态
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
                mgmt.updateIndex(index, SchemaAction.REINDEX).get();
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

    public List<JanusGraphIndex> listIndexes() {
        List<JanusGraphIndex> result = new ArrayList<>();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            for (JanusGraphIndex idx : mgmt.getGraphIndexes(Vertex.class)) {
                result.add(idx);
            }
            for (JanusGraphIndex idx : mgmt.getGraphIndexes(Edge.class)) {
                if (result.stream().noneMatch(i -> i.name().equals(idx.name()))) {
                    result.add(idx);
                }
            }
        } finally {
            mgmt.commit();
        }
        return result;
    }

    public String getIndexStatus(String indexName) {
        try {
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
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    log.warn("Index '{}' not found", indexName);
                    return false;
                }
                mgmt.updateIndex(index, SchemaAction.ENABLE_INDEX).get();
                mgmt.commit();
                return waitForIndexStatus(indexName, SchemaStatus.ENABLED, 60);
            } catch (Exception e) {
                mgmt.rollback();
                throw e;
            }
        } catch (Exception e) {
            log.error("Failed to enable index '{}'", indexName, e);
            return false;
        }
    }

    public boolean disableIndex(String indexName) {
        try {
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                JanusGraphIndex index = mgmt.getGraphIndex(indexName);
                if (index == null) {
                    log.warn("Index '{}' not found", indexName);
                    return false;
                }
                mgmt.updateIndex(index, SchemaAction.DISABLE_INDEX).get();
                mgmt.commit();
                return waitForIndexStatus(indexName, SchemaStatus.DISABLED, 60);
            } catch (Exception e) {
                mgmt.rollback();
                throw e;
            }
        } catch (Exception e) {
            log.error("Failed to disable index '{}'", indexName, e);
            return false;
        }
    }

    public boolean reindex(String indexName) {
        try {
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
                    return false;
                }
                mgmt.updateIndex(index, SchemaAction.REINDEX).get();
                mgmt.commit();
                return waitForIndexStatus(indexName, SchemaStatus.ENABLED, 600);
            } catch (Exception e) {
                mgmt.rollback();
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
            log.error("Error dropping index '{}'", indexName, e);
            return false;
        }
    }

    private boolean removeIndexDirectly(String indexName) {
        try {
            JanusGraphManagement mgmt = graph.openManagement();
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

                mgmt.updateIndex(index, SchemaAction.REMOVE_INDEX).get();
                mgmt.commit();
                log.info("Index '{}' removed successfully", indexName);
                return true;

            } catch (Exception e) {
                mgmt.rollback();
                throw e;
            }
        } catch (Exception e) {
            log.error("Failed to remove index '{}'", indexName, e);
            return false;
        }
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