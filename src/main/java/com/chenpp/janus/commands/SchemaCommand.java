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
                System.out.println("未知子命令: " + subCmd);
                showUsage();
        }
    }

    private void showUsage() {
        System.out.println("\n=== Schema 命令 ===");
        System.out.println("  schema list                - 列出所有 Schema 元素");
        System.out.println("  schema show <name>         - 查看 Schema 元素详情");
        System.out.println("  schema create key <name> <type> [SINGLE|LIST|SET] - 创建属性键");
        System.out.println("  schema create vertex <name>                        - 创建顶点标签");
        System.out.println("  schema create edge <name> [MULTI|SIMPLE|...]       - 创建边标签");
        System.out.println("  schema drop key|vertex|edge <name>                 - 删除 Schema 元素");
        System.out.println("  schema stats               - 查看 Schema 统计");
        System.out.println("  类型: String, Integer, Long, Float, Double, Boolean, Date");
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

            System.out.println("\n=== Schema 概览 ===");
            System.out.println("属性键: " + propertyKeys);
            System.out.println("顶点标签: " + vertexLabels);
            System.out.println("边标签: " + edgeLabels);
            System.out.println("合计: " + (propertyKeys.size() + vertexLabels.size() + edgeLabels.size()));
            System.out.println();

        } finally {
            mgmt.commit();
        }
    }

    private void showSchema(String name) {
        if (name.isEmpty()) {
            System.out.println("用法: schema show <name>");
            return;
        }

        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            if (mgmt.containsPropertyKey(name)) {
                PropertyKey key = mgmt.getPropertyKey(name);
                System.out.println("\n=== 属性键: " + name + " ===");
                System.out.println("  数据类型: " + key.dataType().getSimpleName());
                System.out.println("  基数: " + key.cardinality());
                return;
            }

            if (mgmt.containsVertexLabel(name)) {
                VertexLabel label = mgmt.getVertexLabel(name);
                System.out.println("\n=== 顶点标签: " + name + " ===");
                System.out.println("  分区: " + label.isPartitioned());
                System.out.println("  静态: " + label.isStatic());
                return;
            }

            if (mgmt.containsEdgeLabel(name)) {
                EdgeLabel label = mgmt.getEdgeLabel(name);
                System.out.println("\n=== 边标签: " + name + " ===");
                System.out.println("  多重性: " + label.multiplicity());
                System.out.println("  有向: " + label.isDirected());
                System.out.println("  单向: " + label.isUnidirected());
                return;
            }

            System.out.println("Schema 元素 '" + name + "' 未找到");

        } finally {
            mgmt.commit();
        }
    }

    private void createSchema(String args) throws Exception {
        String[] parts = args.split("\\s+");
        String kind = parts.length > 0 ? parts[0].toLowerCase() : "";
        if (parts.length < 2) {
            System.out.println("用法: schema create key <name> <type> [SINGLE|LIST|SET]");
            System.out.println("       schema create vertex <name>");
            System.out.println("       schema create edge <name> [MULTI|SIMPLE]");
            return;
        }

        switch (kind) {
            case "key":
            case "property":
                if (parts.length < 3) {
                    System.out.println("用法: schema create key <name> <type> [SINGLE|LIST|SET]");
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
                System.out.println("未知创建目标: " + kind + " (可用 key/vertex/edge)");
                showUsage();
        }
    }

    private void createPropertyKey(String name, String typeName, String cardinalityInput) throws Exception {
        Class<?> dataType = getTypeClass(typeName);
        if (dataType == null) {
            System.out.println("类型无效: " + typeName
                    + " (支持: String, Integer, Long, Float, Double, Boolean, Date)");
            return;
        }

        Cardinality cardinality;
        try {
            cardinality = Cardinality.valueOf(cardinalityInput.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println("基数无效: " + cardinalityInput + " (可用 SINGLE/LIST/SET)");
            return;
        }

        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            if (mgmt.containsPropertyKey(name)) {
                System.out.println("属性键 '" + name + "' 已存在");
                return;
            }
            mgmt.makePropertyKey(name).dataType(dataType).cardinality(cardinality).make();
            mgmt.commit();
            System.out.println("✓ 属性键 '" + name + "' 创建成功");
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
                    System.out.println("顶点标签 '" + name + "' 已存在");
                    return;
                }
                mgmt.makeVertexLabel(name).make();
                System.out.println("✓ 顶点标签 '" + name + "' 创建成功");
            } else {
                if (mgmt.containsEdgeLabel(name)) {
                    System.out.println("边标签 '" + name + "' 已存在");
                    return;
                }
                String multi = multiplicityInput.toUpperCase();
                if (!multi.equals("MULTI") && !multi.equals("SIMPLE")) {
                    System.out.println("多重性无效: " + multiplicityInput + " (可用 MULTI|SIMPLE)");
                    return;
                }
                mgmt.makeEdgeLabel(name).multiplicity(Multiplicity.valueOf(multi)).make();
                System.out.println("✓ 边标签 '" + name + "' 创建成功");
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
            System.out.println("用法: schema drop key <name>");
            System.out.println("       schema drop vertex <name>");
            System.out.println("       schema drop edge <name>");
            return;
        }
        String kind = parts[0].toLowerCase();
        String name = parts[1];
        if (!kind.equals("key") && !kind.equals("vertex") && !kind.equals("edge")) {
            System.out.println("未知删除目标: " + kind + " (可用 key/vertex/edge)");
            return;
        }

        System.out.print("删除 Schema " + kind + " '" + name
                + "' 可能影响已有数据，是否继续? (y/N): ");
        String confirm = graphManager.getScanner().nextLine().trim().toLowerCase();
        if (!confirm.equals("y") && !confirm.equals("yes")) {
            System.out.println("操作已取消");
            return;
        }

        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            if (kind.equals("key")) {
                if (!mgmt.containsPropertyKey(name)) {
                    System.out.println("属性键 '" + name + "' 未找到");
                    return;
                }
                mgmt.getPropertyKey(name).remove();
            } else if (kind.equals("vertex")) {
                if (!mgmt.containsVertexLabel(name)) {
                    System.out.println("顶点标签 '" + name + "' 未找到");
                    return;
                }
                mgmt.getVertexLabel(name).remove();
            } else {
                if (!mgmt.containsEdgeLabel(name)) {
                    System.out.println("边标签 '" + name + "' 未找到");
                    return;
                }
                mgmt.getEdgeLabel(name).remove();
            }
            mgmt.commit();
            System.out.println("✓ Schema " + kind + " '" + name + "' 删除成功");
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

            System.out.println("\n=== Schema 统计 ===");
            System.out.println("属性键: " + propertyKeyCount);
            System.out.println("顶点标签: " + vertexLabelCount);
            System.out.println("边标签: " + edgeLabelCount);
            System.out.println("合计: " + (propertyKeyCount + vertexLabelCount + edgeLabelCount));

            try {
                long vertexCount = graph.traversal().V().count().next();
                long edgeCount = graph.traversal().E().count().next();
                System.out.println("\n数据统计:");
                System.out.println("  顶点数: " + vertexCount);
                System.out.println("  边数: " + edgeCount);
            } catch (Exception e) {
                System.out.println("  (获取数据统计失败)");
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