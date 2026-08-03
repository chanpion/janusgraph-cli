package com.chenpp.janus.commands;

import com.chenpp.janus.core.GraphManager;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author April.Chen
 */
public class QueryCommand implements Command {

    private final GraphManager graphManager;

    public QueryCommand(GraphManager graphManager) {
        this.graphManager = graphManager;
    }

    @Override
    public void execute(String args) throws Exception {
        if (args.isEmpty() || args.startsWith("help")) {
            showUsage();
            return;
        }

        JanusGraph graph = graphManager.getGraph();
        GraphTraversalSource g = graph.traversal();

        try {
            long startTime = System.nanoTime();
            Object result = executeGremlin(g, args);
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            formatResult(result, duration);

        } catch (Exception e) {
            System.err.println("Query execution failed: " + e.getMessage());
        }
    }

    private Object executeGremlin(GraphTraversalSource g, String query) {
        // 简化实现，支持基本查询
        if (query.startsWith("V(") || query.startsWith("v(")) {
            if (query.contains("has")) {
                return g.V().has("name", extractValue(query)).limit(100).toList();
            }
            return g.V().limit(100).toList();
        } else if (query.startsWith("E(") || query.startsWith("e(")) {
            return g.E().limit(100).toList();
        } else if (query.equals("count") || query.equals("count()")) {
            return g.V().count().next();
        } else if (query.startsWith("count(")) {
            if (query.contains("v") || query.contains("V")) {
                return g.V().count().next();
            } else if (query.contains("e") || query.contains("E")) {
                return g.E().count().next();
            }
        }
        return g.V().limit(10).toList();
    }

    private String extractValue(String query) {
        int start = query.indexOf("'");
        if (start == -1) {
            start = query.indexOf("\"");
        }
        if (start == -1) {
            return "";
        }
        int end = query.indexOf(query.charAt(start), start + 1);
        if (end == -1) {
            return "";
        }
        return query.substring(start + 1, end);
    }

    private void formatResult(Object result, long duration) {
        System.out.println("Result (took " + duration + "ms):");

        if (result instanceof List) {
            List<?> list = (List<?>) result;
            System.out.println("  Count: " + list.size());

            if (!list.isEmpty()) {
                int max = Math.min(list.size(), 5);
                for (int i = 0; i < max; i++) {
                    Object item = list.get(i);
                    if (item instanceof Vertex) {
                        Vertex v = (Vertex) item;
                        System.out.println("  [" + i + "] Vertex: id=" + v.id() +
                                ", label=" + v.label());
                    } else if (item instanceof Edge) {
                        Edge e = (Edge) item;
                        System.out.println("  [" + i + "] Edge: id=" + e.id() +
                                ", label=" + e.label());
                    } else {
                        System.out.println("  [" + i + "] " + item);
                    }
                }
                if (list.size() > max) {
                    System.out.println("  ... and " + (list.size() - max) + " more");
                }
            }
        } else {
            System.out.println("  " + result);
        }
    }

    private void showUsage() {
        System.out.println("\n=== Query Commands ===");
        System.out.println("  query <gremlin>    - Execute Gremlin query");
        System.out.println("  q <gremlin>        - Alias");
        System.out.println("  g <gremlin>        - Alias");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  q V().count()");
        System.out.println("  q V().limit(10)");
        System.out.println("  q V().has('name','test')");
        System.out.println();
    }
}