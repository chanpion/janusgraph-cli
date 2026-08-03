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
            case "create-key":
            case "createkey":
                createPropertyKey(subArgs);
                break;
            case "create-label":
            case "createlabel":
                createLabel(subArgs);
                break;
            case "drop-key":
            case "dropkey":
                dropPropertyKey(subArgs);
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
        System.out.println("  schema create-key <name> <type> - Create property key");
        System.out.println("  schema create-label <name> <type> - Create label (vertex/edge)");
        System.out.println("  schema drop-key <name>     - Drop property key");
        System.out.println("  schema stats               - Show schema statistics");
        System.out.println("  types: String, Integer, Long, Double, Boolean");
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

    private void createPropertyKey(String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length < 2) {
            System.out.println("Usage: schema create-key <name> <type>");
            return;
        }

        String name = parts[0];
        String typeName = parts[1];

        Class<?> dataType = getTypeClass(typeName);
        if (dataType == null) {
            System.out.println("Invalid type: " + typeName);
            return;
        }

        System.out.print("Cardinality (SINGLE/LIST/SET) [SINGLE]: ");
        String cardinalityInput = graphManager.getScanner().nextLine().trim().toUpperCase();
        if (cardinalityInput.isEmpty()) {
            cardinalityInput = "SINGLE";
        }

        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            if (mgmt.containsPropertyKey(name)) {
                System.out.println("PropertyKey '" + name + "' already exists");
                return;
            }

            Cardinality cardinality = Cardinality.valueOf(cardinalityInput);
            mgmt.makePropertyKey(name).dataType(dataType).cardinality(cardinality).make();
            mgmt.commit();
            System.out.println("✓ PropertyKey '" + name + "' created successfully");

        } catch (Exception e) {
            mgmt.rollback();
            throw e;
        }
    }

    private void createLabel(String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length < 2) {
            System.out.println("Usage: schema create-label <name> <type>");
            System.out.println("  types: vertex, edge");
            return;
        }

        String name = parts[0];
        String type = parts[1].toLowerCase();

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
            } else if (type.equals("edge")) {
                if (mgmt.containsEdgeLabel(name)) {
                    System.out.println("EdgeLabel '" + name + "' already exists");
                    return;
                }
                System.out.print("Multiplicity (MULTI/SIMPLE/MANY2ONE/ONE2MANY/ONE2ONE) [MULTI]: ");
                String multiInput = graphManager.getScanner().nextLine().trim().toUpperCase();
                if (multiInput.isEmpty()) {
                    multiInput = "MULTI";
                }
                Multiplicity multiplicity = Multiplicity.valueOf(multiInput);
                mgmt.makeEdgeLabel(name).multiplicity(multiplicity).make();
                System.out.println("✓ EdgeLabel '" + name + "' created successfully");
            } else {
                System.out.println("Invalid type: " + type + ". Use 'vertex' or 'edge'");
            }
            mgmt.commit();

        } catch (Exception e) {
            mgmt.rollback();
            throw e;
        }
    }

    private void dropPropertyKey(String name) throws Exception {
        if (name.isEmpty()) {
            System.out.println("Usage: schema drop-key <name>");
            return;
        }

        System.out.print("Dropping property key '" + name + "' may affect existing data. Continue? (y/N): ");
        String confirm = graphManager.getScanner().nextLine().trim().toLowerCase();
        if (!confirm.equals("y") && !confirm.equals("yes")) {
            System.out.println("Operation cancelled");
            return;
        }

        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            if (!mgmt.containsPropertyKey(name)) {
                System.out.println("PropertyKey '" + name + "' not found");
                return;
            }
            mgmt.getPropertyKey(name).remove();
            mgmt.commit();
            System.out.println("✓ PropertyKey '" + name + "' dropped successfully");
        } catch (Exception e) {
            mgmt.rollback();
            throw e;
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
        }
    }

    private Class<?> getTypeClass(String typeName) {
        switch (typeName.toLowerCase()) {
            case "string": return String.class;
            case "integer": return Integer.class;
            case "long": return Long.class;
            case "double": return Double.class;
            case "boolean": return Boolean.class;
            default: return null;
        }
    }
}