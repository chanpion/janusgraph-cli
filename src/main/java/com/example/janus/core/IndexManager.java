package com.example.janus.core;

import org.apache.tinkerpop.gremlin.structure.Element;
import org.janusgraph.core.Cardinality;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.janusgraph.core.schema.SchemaAction;
import org.janusgraph.core.schema.SchemaStatus;
import org.janusgraph.graphdb.database.management.ManagementSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);
    private final JanusGraph graph;

    public IndexManager(JanusGraph graph) {
        this.graph = graph;
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

                var builder = mgmt.buildIndex(indexName, elementClass).addKey(key);
                if (unique) {
                    builder.unique();
                }
                builder.buildCompositeIndex();

                mgmt.commit();
                log.info("Index '{}' definition committed", indexName);

                boolean registered = waitForIndexStatus(indexName, SchemaStatus.REGISTERED, 60);
                if (!registered) {
                    log.error("Index '{}' failed to reach REGISTERED status", indexName);
                    return false;
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

    private boolean waitForIndexStatus(String indexName, SchemaStatus targetStatus, int timeoutSeconds) {
        try {
            ManagementSystem.awaitGraphIndexStatus(graph, indexName)
                    .status(targetStatus)
                    .timeout(timeoutSeconds, java.time.temporal.ChronoUnit.SECONDS)
                    .call();
            return true;
        } catch (Exception e) {
            log.error("Failed to wait for index '{}' to reach status {}", indexName, targetStatus, e);
            return false;
        }
    }

    private boolean enableIndexWithReindex(String indexName) {
        try {
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                mgmt.updateIndex(mgmt.getGraphIndex(indexName), SchemaAction.REINDEX).get();
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

    public String getIndexStatus(String indexName) {
        try {
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                var index = mgmt.getGraphIndex(indexName);
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
                var index = mgmt.getGraphIndex(indexName);
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
                var index = mgmt.getGraphIndex(indexName);
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
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                var index = mgmt.getGraphIndex(indexName);
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
            // 先检查当前状态
            String status = getIndexStatus(indexName);
            if ("NOT_FOUND".equals(status)) {
                log.warn("Index '{}' does not exist", indexName);
                return true;
            }

            // 如果已经是 DISABLED，直接删除
            if (status.contains("DISABLED")) {
                return removeIndexDirectly(indexName);
            }

            // 否则先禁用
            boolean disabled = disableIndex(indexName);
            if (!disabled) {
                log.error("Failed to disable index '{}'", indexName);
                return false;
            }

            Thread.sleep(1000);
            return removeIndexDirectly(indexName);

        } catch (Exception e) {
            log.error("Error dropping index '{}'", indexName, e);
            return false;
        }
    }

    private boolean removeIndexDirectly(String indexName) {
        try {
            JanusGraphManagement mgmt = graph.openManagement();
            try {
                var index = mgmt.getGraphIndex(indexName);
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

                mgmt.updateIndex(index, SchemaAction.DROP_INDEX).get();
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