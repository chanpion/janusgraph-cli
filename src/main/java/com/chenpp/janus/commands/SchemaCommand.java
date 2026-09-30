package com.chenpp.janus.commands;

import com.chenpp.janus.core.GraphManager;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.EdgeLabel;
import org.janusgraph.core.VertexLabel;
import org.janusgraph.core.Cardinality;
import org.janusgraph.core.Multiplicity;
import org.janusgraph.core.schema.JanusGraphManagement;

import java.util.*;

/**
 * @author April.Chen
 */
public class SchemaCommand implements Command {

    private final GraphManager graphManager;

    public SchemaCommand(GraphManager graphManager) {
        this.graphManager = graphManager;
    }

    @Override
    public void execute(String args) throws Exception {
        if (args.isEmpty()) {
            showUsage();
            return;
        }

        String[] parts = args.split("\\s+", 2);
        String subCmd = parts[0].toLowerCase();
        String subArgs = parts.length > 1 ? parts[1] : "";

        switch (subCmd) {
            case "list":
            case "ls":
                listSchema();
                break;
            case "show":
            case "describe":
                showSchema(subArgs);
                break;
            case "create":
                createSchema(subArgs);
                break;
            case "drop":
                dropSchema(subArgs);
                break;
            case "stats":
                showStats();
                break;
            default:
                System.out.println("Unknown subcommand: " + subCmd);
                showUsage();
        }
    }

    private void showUsage() {
        System.out.println("\n=== Schema Commands ===");
        System.out.println("  schema list                - List all schema elements");
        System.out.println("  schema show <name>         - Show schema element details");
        System.out.println("  schema create key <name> <type> [SINGLE|LIST|SET] - Create property key");
        System.out.println("  schema create vertex <name>                        - Create vertex label");
        System.out.println("  schema create edge <name> [MULTI|SIMPLE|...]       - Create edge label");
        System.out.println("  schema drop key|vertex|edge <name>                 - Drop schema element");
        System.out.println("  schema stats               - Show schema statistics");
        System.out.println("  types: String, Integer, Long, Float, Double, Boolean, Date");
        System.out.println();
    }

    private void listSchema() {
        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            List<String> propertyKeys = new ArrayList<>();
            for (PropertyKey pk : mgmt.getRelationTypes(PropertyKey.class)) {
                propertyKeys.add(pk.name());
            }

            List<String> vertexLabels = new ArrayList<>();
            for (VertexLabel label : mgmt.getVertexLabels()) {
                vertexLabels.add(label.name());
            }

            List<String> edgeLabels = new ArrayList<>();
            for (EdgeLabel el : mgmt.getRelationTypes(EdgeLabel.class)) {
                edgeLabels.add(el.name());
            }

            System.out.println("\n=== Schema Summary ===");
            System.out.println("Property Keys: " + propertyKeys);
            System.out.println("Vertex Labels: " + vertexLabels);
            System.out.println("Edge Labels: " + edgeLabels);
            System.out.println("Total: " + (propertyKeys.size() + vertexLabels.size() + edgeLabels.size()));
            System.out.println();

        } finally {
            mgmt.commit();
        }
    }

    private void showSchema(String name) {
        if (name.isEmpty()) {
            System.out.println("Usage: schema show <name>");
            return;
        }

        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            if (mgmt.containsPropertyKey(name)) {
                PropertyKey key = mgmt.getPropertyKey(name);
                System.out.println("\n=== Property Key: " + name + " ===");
                System.out.println("  Data Type: " + key.dataType().getSimpleName());
                System.out.println("  Cardinality: " + key.cardinality());
                return;
            }

            if (mgmt.containsVertexLabel(name)) {
                VertexLabel label = mgmt.getVertexLabel(name);
                System.out.println("\n=== Vertex Label: " + name + " ===");
                System.out.println("  Partitioned: " + label.isPartitioned());
                System.out.println("  Static: " + label.isStatic());
                return;
            }

            if (mgmt.containsEdgeLabel(name)) {
                EdgeLabel label = mgmt.getEdgeLabel(name);
                System.out.println("\n=== Edge Label: " + name + " ===");
                System.out.println("  Multiplicity: " + label.multiplicity());
                System.out.println("  Directed: " + label.isDirected());
                System.out.println("  Unidirected: " + label.isUnidirected());
                return;
            }

            System.out.println("Schema element '" + name + "' not found");

        } finally {
            mgmt.commit();
        }
    }

    private void createSchema(String args) throws Exception {
        String[] parts = args.split("\\s+");
        String kind = parts.length > 0 ? parts[0].toLowerCase() : "";
        if (parts.length < 2) {
            System.out.println("Usage: schema create key <name> <type> [SINGLE|LIST|SET]");
            System.out.println("       schema create vertex <name>");
            System.out.println("       schema create edge <name> [MULTI|SIMPLE]");
            return;
        }

        switch (kind) {
            case "key":
            case "property":
                if (parts.length < 3) {
                    System.out.println("Usage: schema create key <name> <type> [SINGLE|LIST|SET]");
                    return;
                }
                createPropertyKey(parts[1], parts[2], parts.length > 3 ? parts[3] : "SINGLE");
                break;
            case "vertex":
                createLabel(parts[1], "vertex", null);
                break;
            case "edge":
                createLabel(parts[1], "edge", parts.length > 2 ? parts[2] : "MULTI");
                break;
            default:
                System.out.println("Unknown create target: " + kind + " (use key/vertex/edge)");
                showUsage();
        }
    }

    private void createPropertyKey(String name, String typeName, String cardinalityInput) throws Exception {
        Class<?> dataType = getTypeClass(typeName);
        if (dataType == null) {
            System.out.println("Invalid type: " + typeName
                    + " (supported: String, Integer, Long, Float, Double, Boolean, Date)");
            return;
        }

        Cardinality cardinality;
        try {
            cardinality = Cardinality.valueOf(cardinalityInput.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println("Invalid cardinality: " + cardinalityInput + " (use SINGLE/LIST/SET)");
            return;
        }

        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            if (mgmt.containsPropertyKey(name)) {
                System.out.println("PropertyKey '" + name + "' already exists");
                return;
            }
            mgmt.makePropertyKey(name).dataType(dataType).cardinality(cardinality).make();
            mgmt.commit();
            System.out.println("✓ PropertyKey '" + name + "' created successfully");
        } finally {
            if (mgmt.isOpen()) {
                mgmt.rollback();
            }
        }
    }

    private void createLabel(String name, String type, String multiplicityInput) throws Exception {
        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            if (type.equals("vertex")) {
                if (mgmt.containsVertexLabel(name)) {
                    System.out.println("VertexLabel '" + name + "' already exists");
                    return;
                }
                mgmt.makeVertexLabel(name).make();
                System.out.println("✓ VertexLabel '" + name + "' created successfully");
            } else {
                if (mgmt.containsEdgeLabel(name)) {
                    System.out.println("EdgeLabel '" + name + "' already exists");
                    return;
                }
                String multi = multiplicityInput.toUpperCase();
                if (!multi.equals("MULTI") && !multi.equals("SIMPLE")) {
                    System.out.println("Invalid multiplicity: " + multiplicityInput + " (use MULTI|SIMPLE)");
                    return;
                }
                mgmt.makeEdgeLabel(name).multiplicity(Multiplicity.valueOf(multi)).make();
                System.out.println("✓ EdgeLabel '" + name + "' created successfully");
            }
            mgmt.commit();
        } finally {
            if (mgmt.isOpen()) {
                mgmt.rollback();
            }
        }
    }

    private void dropSchema(String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length < 2) {
            System.out.println("Usage: schema drop key <name>");
            System.out.println("       schema drop vertex <name>");
            System.out.println("       schema drop edge <name>");
            return;
        }
        String kind = parts[0].toLowerCase();
        String name = parts[1];
        if (!kind.equals("key") && !kind.equals("vertex") && !kind.equals("edge")) {
            System.out.println("Unknown drop target: " + kind + " (use key/vertex/edge)");
            return;
        }

        System.out.print("Dropping schema " + kind + " '" + name
                + "' may affect existing data. Continue? (y/N): ");
        String confirm = graphManager.getScanner().nextLine().trim().toLowerCase();
        if (!confirm.equals("y") && !confirm.equals("yes")) {
            System.out.println("Operation cancelled");
            return;
        }

        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            if (kind.equals("key")) {
                if (!mgmt.containsPropertyKey(name)) {
                    System.out.println("PropertyKey '" + name + "' not found");
                    return;
                }
                mgmt.getPropertyKey(name).remove();
            } else if (kind.equals("vertex")) {
                if (!mgmt.containsVertexLabel(name)) {
                    System.out.println("VertexLabel '" + name + "' not found");
                    return;
                }
                mgmt.getVertexLabel(name).remove();
            } else {
                if (!mgmt.containsEdgeLabel(name)) {
                    System.out.println("EdgeLabel '" + name + "' not found");
                    return;
                }
                mgmt.getEdgeLabel(name).remove();
            }
            mgmt.commit();
            System.out.println("✓ Schema " + kind + " '" + name + "' dropped successfully");
        } finally {
            if (mgmt.isOpen()) {
                mgmt.rollback();
            }
        }
    }

    private void showStats() {
        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            List<PropertyKey> propertyKeys = new ArrayList<>();
            for (PropertyKey pk : mgmt.getRelationTypes(PropertyKey.class)) {
                propertyKeys.add(pk);
            }
            List<VertexLabel> vertexLabels = new ArrayList<>();
            for (VertexLabel vl : mgmt.getVertexLabels()) {
                vertexLabels.add(vl);
            }
            List<EdgeLabel> edgeLabels = new ArrayList<>();
            for (EdgeLabel el : mgmt.getRelationTypes(EdgeLabel.class)) {
                edgeLabels.add(el);
            }

            int propertyKeyCount = propertyKeys.size();
            int vertexLabelCount = vertexLabels.size();
            int edgeLabelCount = edgeLabels.size();

            System.out.println("\n=== Schema Statistics ===");
            System.out.println("Property Keys: " + propertyKeyCount);
            System.out.println("Vertex Labels: " + vertexLabelCount);
            System.out.println("Edge Labels: " + edgeLabelCount);
            System.out.println("Total: " + (propertyKeyCount + vertexLabelCount + edgeLabelCount));

            try {
                long vertexCount = graph.traversal().V().count().next();
                long edgeCount = graph.traversal().E().count().next();
                System.out.println("\nData Statistics:");
                System.out.println("  Vertices: " + vertexCount);
                System.out.println("  Edges: " + edgeCount);
            } catch (Exception e) {
                System.out.println("  (Failed to get data statistics)");
            }
            System.out.println();

        } finally {
            mgmt.commit();
            graph.tx().rollback();
        }
    }

    private Class<?> getTypeClass(String typeName) {
        switch (typeName.toLowerCase()) {
            case "string": return String.class;
            case "integer": return Integer.class;
            case "long": return Long.class;
            case "float": return Float.class;
            case "double": return Double.class;
            case "boolean": return Boolean.class;
            case "date": return Date.class;
            default: return null;
        }
    }
}