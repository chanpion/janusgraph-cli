package com.chenpp.janus.commands;

import com.chenpp.janus.core.GraphManager;
import com.chenpp.janus.utils.JsonFormatter;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.Cardinality;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author April.Chen
 */
public class BatchCommand implements Command {

    private static final Logger log = LoggerFactory.getLogger(BatchCommand.class);
    private final GraphManager graphManager;

    public BatchCommand(GraphManager graphManager) {
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
            case "import":
                doImport(subArgs);
                break;
            case "export":
                doExport(subArgs);
                break;
            case "delete-all":
                deleteAll(subArgs);
                break;
            case "count":
                doCount();
                break;
            case "sample":
                doSample(subArgs);
                break;
            default:
                System.out.println("未知子命令: " + subCmd);
                showUsage();
        }
    }

    private void showUsage() {
        System.out.println("\n=== 批量命令 ===");
        System.out.println("  batch import <file> [--csv|--json] - 导入数据");
        System.out.println("  batch export <file> [--csv|--json] - 导出数据");
        System.out.println("  batch delete-all [--force]   - 删除所有数据");
        System.out.println("  batch count                  - 统计顶点与边数量");
        System.out.println("  batch sample <n>             - 采样数据");
        System.out.println();
    }

    private void doImport(String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length < 1) {
            System.out.println("用法: batch import <file> [--csv|--json]");
            return;
        }
        String filePath = parts[0];
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("文件不存在: " + filePath);
            return;
        }
        boolean isJson;
        if (args.contains("--csv")) {
            isJson = false;
        } else if (args.contains("--json")) {
            isJson = true;
        } else {
            isJson = filePath.toLowerCase().endsWith(".json");
        }
        System.out.println("正在从 " + filePath + " 导入数据 (" + (isJson ? "JSON" : "CSV") + ")...");
        long startTime = System.currentTimeMillis();
        try {
            int[] counts = isJson ? importJson(file) : importCsv(file);
            long duration = System.currentTimeMillis() - startTime;
            System.out.println("导入完成: " + counts[0] + " 顶点, " + counts[1]
                    + " 边 (耗时 " + duration + "ms)");
        } catch (Exception e) {
            System.out.println("导入失败: " + e.getMessage());
            log.error("Import failed", e);
        }
    }

    private int[] importJson(File file) throws Exception {
        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        JSONObject root = JSON.parseObject(content);
        if (root == null) {
            throw new IllegalArgumentException("JSON 无效: 文件为空或不是 JSON 对象");
        }
        JSONArray vertices = root.getJSONArray("vertices");
        JSONArray edges = root.getJSONArray("edges");
        Set<String> vertexLabels = new HashSet<>();
        Set<String> edgeLabels = new HashSet<>();
        Map<String, Class<?>> propertyTypes = new LinkedHashMap<>();
        collectSchemaFromJson(vertices, edges, vertexLabels, edgeLabels, propertyTypes);
        ensureSchema(graphManager.getGraph(), vertexLabels, edgeLabels, propertyTypes);
        return importJsonData(graphManager.getGraph(), vertices, edges);
    }

    private void collectSchemaFromJson(JSONArray vertices, JSONArray edges, Set<String> vertexLabels, Set<String> edgeLabels, Map<String, Class<?>> propertyTypes) {
        if (vertices != null) {
            for (int i = 0; i < vertices.size(); i++) {
                JSONObject v = vertices.getJSONObject(i);
                String label = v.getString("label");
                if (label != null) {
                    vertexLabels.add(label);
                }
                JSONObject props = v.getJSONObject("properties");
                if (props != null) {
                    collectTypes(props, propertyTypes);
                }
            }
        }
        if (edges != null) {
            for (int i = 0; i < edges.size(); i++) {
                JSONObject e = edges.getJSONObject(i);
                String label = e.getString("label");
                if (label != null) {
                    edgeLabels.add(label);
                }
                JSONObject props = e.getJSONObject("properties");
                if (props != null) {
                    collectTypes(props, propertyTypes);
                }
            }
        }
    }

    private int[] importJsonData(org.janusgraph.core.JanusGraph graph, JSONArray vertices, JSONArray edges) throws Exception {
        Map<String, Vertex> idMap = new HashMap<>();
        int vCount = 0;
        int eCount = 0;
        graph.tx().rollback();
        try {
            if (vertices != null) {
                for (int i = 0; i < vertices.size(); i++) {
                    JSONObject v = vertices.getJSONObject(i);
                    String label = v.getString("label") != null ? v.getString("label") : "vertex";
                    Vertex vertex = graph.addVertex(T.label, label);
                    JSONObject props = v.getJSONObject("properties");
                    if (props != null) {
                        setProps(vertex, props);
                    }
                    String id = v.getString("id");
                    if (id != null) {
                        idMap.put(id, vertex);
                    }
                    vCount++;
                }
            }
            if (edges != null) {
                for (int i = 0; i < edges.size(); i++) {
                    JSONObject e = edges.getJSONObject(i);
                    String from = e.getString("from");
                    String to = e.getString("to");
                    Vertex fromV = from != null ? idMap.get(from) : null;
                    Vertex toV = to != null ? idMap.get(to) : null;
                    if (fromV == null || toV == null) {
                        System.out.println("  已跳过边: 缺少端点");
                        continue;
                    }
                    String label = e.getString("label") != null ? e.getString("label") : "edge";
                    Edge edge = fromV.addEdge(label, toV);
                    JSONObject props = e.getJSONObject("properties");
                    if (props != null) {
                        setProps(edge, props);
                    }
                    eCount++;
                }
            }
            graph.tx().commit();
        } catch (Exception ex) {
            graph.tx().rollback();
            throw ex;
        }
        return new int[]{vCount, eCount};
    }

    private void collectTypes(JSONObject props, Map<String, Class<?>> propertyTypes) {
        for (String key : props.keySet()) {
            Object val = props.get(key);
            if (val == null || val instanceof JSONObject) {
                continue;
            }
            Class<?> cls = inferJsonType(val);
            Class<?> existing = propertyTypes.get(key);
            if (existing == null) {
                propertyTypes.put(key, cls);
            } else if (!existing.equals(cls)) {
                propertyTypes.put(key, String.class);
            }
        }
    }

    private Class<?> inferJsonType(Object val) {
        if (val instanceof String) {
            return String.class;
        }
        if (val instanceof Integer) {
            return Integer.class;
        }
        if (val instanceof Long) {
            return Long.class;
        }
        if (val instanceof Double || val instanceof Float) {
            return Double.class;
        }
        if (val instanceof Boolean) {
            return Boolean.class;
        }
        return String.class;
    }

    private void setProps(Element element, JSONObject props) {
        for (String key : props.keySet()) {
            Object val = props.get(key);
            if (val != null && !(val instanceof JSONObject)) {
                element.property(key, val);
            }
        }
    }

    private int[] importCsv(File file) throws Exception {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return new int[]{0, 0};
        }
        String header = lines.get(0);
        boolean isEdge = header.toLowerCase().startsWith("from,");
        JanusGraph graph = graphManager.getGraph();
        Set<String> vertexLabels = new HashSet<>();
        Set<String> edgeLabels = new HashSet<>();
        Map<String, Class<?>> propertyTypes = new LinkedHashMap<>();
        List<String> cols = splitCsv(header);
        if (isEdge) {
            edgeLabels.add("edge");
            for (String c : cols) {
                if (!c.equalsIgnoreCase("from") && !c.equalsIgnoreCase("to")) {
                    propertyTypes.put(c, inferCsvType(c));
                }
            }
            ensureSchema(graph, vertexLabels, edgeLabels, propertyTypes);
            graph.tx().rollback();
            int eCount = 0;
            try {
                for (int i = 1; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    List<String> vals = splitCsv(line);
                    Vertex fromV = vals.size() > 0 && !vals.get(0).isEmpty() ? findVertex(graph, vals.get(0)) : null;
                    Vertex toV = vals.size() > 1 && !vals.get(1).isEmpty() ? findVertex(graph, vals.get(1)) : null;
                    if (fromV == null || toV == null) {
                        continue;
                    }
                    Edge edge = fromV.addEdge("edge", toV);
                    for (int j = 2; j < cols.size() && j < vals.size(); j++) {
                        Object val = parseCsvValue(vals.get(j), propertyTypes.get(cols.get(j)));
                        if (val != null) {
                            edge.property(cols.get(j), val);
                        }
                    }
                    eCount++;
                }
                graph.tx().commit();
            } catch (Exception ex) {
                graph.tx().rollback();
                throw ex;
            }
            return new int[]{0, eCount};
        }
        vertexLabels.add("vertex");
        for (String c : cols) {
            propertyTypes.put(c, inferCsvType(c));
        }
        ensureSchema(graph, vertexLabels, edgeLabels, propertyTypes);
        graph.tx().rollback();
        int vCount = 0;
        try {
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> vals = splitCsv(line);
                Vertex vertex = graph.addVertex(T.label, "vertex");
                for (int j = 0; j < cols.size() && j < vals.size(); j++) {
                    Object val = parseCsvValue(vals.get(j), propertyTypes.get(cols.get(j)));
                    if (val != null) {
                        vertex.property(cols.get(j), val);
                    }
                }
                vCount++;
            }
            graph.tx().commit();
        } catch (Exception ex) {
            graph.tx().rollback();
            throw ex;
        }
        return new int[]{vCount, 0};
    }

    private void ensureSchema(JanusGraph graph, Set<String> vertexLabels, Set<String> edgeLabels, Map<String, Class<?>> propertyTypes) {
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            for (Map.Entry<String, Class<?>> entry : propertyTypes.entrySet()) {
                if (mgmt.getPropertyKey(entry.getKey()) == null) {
                    mgmt.makePropertyKey(entry.getKey()).dataType(entry.getValue()).cardinality(Cardinality.SINGLE).make();
                }
            }
            for (String label : vertexLabels) {
                if (mgmt.getVertexLabel(label) == null) {
                    mgmt.makeVertexLabel(label).make();
                }
            }
            for (String label : edgeLabels) {
                if (mgmt.getEdgeLabel(label) == null) {
                    mgmt.makeEdgeLabel(label).make();
                }
            }
            mgmt.commit();
        } catch (Exception e) {
            mgmt.rollback();
            throw new RuntimeException("Schema 创建失败: " + e.getMessage(), e);
        }
    }

    private Vertex findVertex(JanusGraph graph, String name) {
        Iterator<Vertex> it = graph.traversal().V().has("name", name);
        if (it.hasNext()) {
            return it.next();
        }
        return null;
    }

    private Class<?> inferCsvType(String colName) {
        String lower = colName.toLowerCase();
        if (lower.equals("age") || lower.endsWith("count")) {
            return Integer.class;
        }
        if (lower.equals("weight") || lower.contains("score")) {
            return Double.class;
        }
        if (lower.equals("active") || lower.startsWith("is")) {
            return Boolean.class;
        }
        return String.class;
    }

    private Object parseCsvValue(String raw, Class<?> type) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();
        if (type == Integer.class) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        } else if (type == Double.class) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        } else if (type == Boolean.class) {
            return Boolean.parseBoolean(s);
        }
        return s;
    }

    private List<String> splitCsv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString());
        return result;
    }

    private void doExport(String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length < 1) {
            System.out.println("用法: batch export <file> [--csv|--json]");
            return;
        }
        String filePath = parts[0];
        boolean isJson = args.contains("--json") || (!args.contains("--csv") && filePath.toLowerCase().endsWith(".json"));
        JanusGraph graph = graphManager.getGraph();
        long startTime = System.currentTimeMillis();
        if (isJson) {
            List<Vertex> vs = new ArrayList<>();
            graph.traversal().V().forEachRemaining(vs::add);
            List<Edge> es = new ArrayList<>();
            graph.traversal().E().forEachRemaining(es::add);
            JSONObject root = new JSONObject();
            JSONArray va = new JSONArray();
            Map<Object, String> vIdMap = new HashMap<>();
            for (Vertex v : vs) {
                JSONObject jo = new JSONObject();
                jo.put("id", v.id().toString());
                jo.put("label", v.label());
                JSONObject props = new JSONObject();
                v.properties().forEachRemaining(p -> props.put(p.key(), p.value()));
                jo.put("properties", props);
                va.add(jo);
                vIdMap.put(v.id(), v.id().toString());
            }
            root.put("vertices", va);
            JSONArray ea = new JSONArray();
            for (Edge e : es) {
                JSONObject jo = new JSONObject();
                jo.put("from", vIdMap.get(e.outVertex().id()));
                jo.put("to", vIdMap.get(e.inVertex().id()));
                jo.put("label", e.label());
                JSONObject props = new JSONObject();
                e.properties().forEachRemaining(p -> props.put(p.key(), p.value()));
                jo.put("properties", props);
                ea.add(jo);
            }
            root.put("edges", ea);
            try (PrintWriter w = new PrintWriter(filePath, StandardCharsets.UTF_8.name())) {
                w.write(JsonFormatter.toJson(root));
            }
        } else {
            List<Vertex> vs = new ArrayList<>();
            graph.traversal().V().forEachRemaining(vs::add);
            try (PrintWriter w = new PrintWriter(filePath, StandardCharsets.UTF_8.name())) {
                for (Vertex v : vs) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(v.id()).append(',').append(v.label());
                    v.properties().forEachRemaining(p -> sb.append(',').append(p.key()).append('=').append(p.value()));
                    w.println(sb);
                }
            }
        }
        System.out.println("已导出至 " + filePath + " (耗时 " + (System.currentTimeMillis() - startTime) + "ms)");
    }

    private void deleteAll(String args) {
        if (!args.contains("--force")) {
            System.out.println("警告: 将删除所有数据。再次执行并加 --force 以确认。");
            return;
        }
        JanusGraph graph = graphManager.getGraph();
        graph.tx().rollback();
        graph.traversal().V().forEachRemaining(Element::remove);
        graph.tx().commit();
        System.out.println("所有数据已删除。");
    }

    private void doCount() throws Exception {
        JanusGraph graph = graphManager.getGraph();
        long vCount = graph.traversal().V().count().next();
        long eCount = graph.traversal().E().count().next();
        graph.tx().rollback();
        System.out.println("顶点数: " + vCount);
        System.out.println("边数: " + eCount);
    }

    private void doSample(String args) {
        int n = 5;
        if (!args.trim().isEmpty()) {
            try {
                n = Integer.parseInt(args.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        JanusGraph graph = graphManager.getGraph();
        System.out.println("采样顶点:");
        graph.traversal().V().limit(n).forEachRemaining(v -> {
            StringBuilder sb = new StringBuilder();
            sb.append("  [").append(v.label()).append("] ").append(v.id());
            v.properties().forEachRemaining(p -> sb.append(' ').append(p.key()).append('=').append(p.value()));
            System.out.println(sb);
        });
        System.out.println("采样边:");
        graph.traversal().E().limit(n).forEachRemaining(e ->
                System.out.println("  [" + e.label() + "] " + e.outVertex().id() + " -> " + e.inVertex().id()));
        graph.tx().rollback();
    }
}
