package com.chenpp.janus.commands;

import com.chenpp.janus.core.GraphManager;
import com.chenpp.janus.core.IndexManager;
import com.chenpp.janus.utils.JsonFormatter;
import com.chenpp.janus.utils.Spinner;
import com.chenpp.janus.utils.TableFormatter;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.PropertyKey;
import org.janusgraph.core.schema.JanusGraphIndex;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.janusgraph.core.schema.SchemaAction;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author April.Chen
 */
public class IndexCommand implements Command {

    private final GraphManager graphManager;

    public IndexCommand(GraphManager graphManager) {
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
                listIndexes(subArgs);
                break;
            case "create":
                createIndex(subArgs);
                break;
            case "drop":
            case "delete":
            case "rm":
                dropIndex(subArgs);
                break;
            case "status":
            case "show":
                showStatus(subArgs);
                break;
            case "enable":
                enableIndex(subArgs);
                break;
            case "disable":
                disableIndex(subArgs);
                break;
            case "reindex":
                reindex(subArgs);
                break;
            case "describe":
                describeIndex(subArgs);
                break;
            case "repair":
                repairIndex(subArgs);
                break;
            default:
                System.out.println("未知子命令: " + subCmd);
                showUsage();
        }
    }

    private void showUsage() {
        System.out.println("\n=== 索引命令 ===");
        System.out.println("  index list [--json]            - 列出所有索引");
        System.out.println("  index create <name> <key> <type> [-u] - 创建复合索引");
        System.out.println("  index drop <name> [-f]         - 删除索引");
        System.out.println("  index status <name>            - 查看索引状态");
        System.out.println("  index enable <name>            - 启用索引");
        System.out.println("  index disable <name>           - 禁用索引");
        System.out.println("  index reindex <name>           - 对已有数据重建索引");
        System.out.println("  index describe <name>          - 查看索引详情");
        System.out.println("  index repair <name>            - 修复索引问题");
        System.out.println();
    }

    private void listIndexes(String args) {
        boolean jsonOutput = args.contains("--json");
        List<Map<String, Object>> indexList = new IndexManager(graphManager).listIndexes();

        if (jsonOutput) {
            System.out.println(JsonFormatter.toJson(indexList));
            return;
        }

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"名称", "状态", "类型", "元素", "属性键", "唯一"});
        for (Map<String, Object> info : indexList) {
            rows.add(new String[]{
                    (String) info.get("name"),
                    (String) info.get("status"),
                    (String) info.get("type"),
                    (String) info.get("element"),
                    info.get("keys").toString(),
                    info.containsKey("unique") ? "是" : "否"
            });
        }
        TableFormatter.printTable(rows);
    }

    private void createIndex(String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length < 3) {
            System.out.println("用法: index create <name> <key> <dataType> [elementType] [-u]");
            System.out.println("       index create <name> <key> <elementType> [-u]");
            System.out.println("  dataType: String/Integer/Long/Float/Double/Boolean/Date");
            System.out.println("  elementType: vertex(v)/edge(e)，默认 vertex");
            System.out.println("  -u: 唯一索引");
            System.out.println("示例:");
            System.out.println("  index create byName name String");
            System.out.println("  index create byWeight weight Double edge");
            System.out.println("  index create byName name vertex -u");
            return;
        }

        String name = parts[0];
        String key = parts[1];
        boolean unique = args.contains("-u");

        java.util.List<String> posArgs = new java.util.ArrayList<>();
        for (int i = 2; i < parts.length; i++) {
            if (!parts[i].equals("-u")) {
                posArgs.add(parts[i].toLowerCase());
            }
        }

        Class<? extends Element> elementClass = Vertex.class;
        Class<?> dataType = null;

        if (posArgs.size() >= 2) {
            // JanusApp style: <dataType> [elementType]
            dataType = getDataType(posArgs.get(0));
            elementClass = parseElementType(posArgs.get(1));
        } else if (posArgs.size() == 1) {
            String arg = posArgs.get(0);
            // Try dataType first, then fall back to elementType (backward compatible)
            dataType = getDataType(arg);
            if (dataType == null) {
                elementClass = parseElementType(arg);
            }
        }

        if (dataType == null) {
            System.out.print("请输入数据类型 (String/Integer/Long/Float/Double/Boolean/Date): ");
            String dataTypeInput = graphManager.getScanner().nextLine().trim();
            dataType = getDataType(dataTypeInput);
            if (dataType == null) {
                System.out.println("数据类型无效，使用 String");
                dataType = String.class;
            }
        }

        IndexManager indexManager = new IndexManager(graphManager);
        boolean success;
        try (Spinner spinner = Spinner.start("正在创建索引 '" + name + "'")) {
            success = indexManager.createCompositeIndex(name, key, dataType, elementClass, unique);
        }

        if (success) {
            System.out.println("✓ 索引 '" + name + "' 创建成功");
        } else {
            String status = indexManager.getIndexStatus(name);
            System.out.println("✗ 创建索引 '" + name + "' 失败 (当前状态: " + status + ")");
            if ("INSTALLED".equals(status)) {
                System.out.println("  卡在 INSTALLED：注册需要集群所有实例确认。先 'graph instances' 查看，"
                        + "用 'graph close-instance <id>' 关闭陈旧实例，再 'index reindex " + name + "' 重试。");
            } else if ("DISABLED".equals(status)) {
                System.out.println("  下一步: 'index enable " + name + "'");
            } else if ("REGISTERED".equals(status)) {
                System.out.println("  下一步: 'index reindex " + name + "'");
            } else {
                System.out.println("  下一步: 'index reindex " + name + "'；如需删除: 'index drop " + name + " -f'");
            }
        }
    }

    private Class<?> getDataType(String input) {
        switch (input.toLowerCase()) {
            case "string":
                return String.class;
            case "integer":
                return Integer.class;
            case "long":
                return Long.class;
            case "float":
                return Float.class;
            case "double":
                return Double.class;
            case "boolean":
                return Boolean.class;
            case "date":
                return Date.class;
            default:
                return null;
        }
    }

    private Class<? extends Element> parseElementType(String typeName) {
        switch (typeName.toLowerCase()) {
            case "edge":
            case "e":
                return Edge.class;
            case "vertex":
            case "v":
            default:
                return Vertex.class;
        }
    }

    private void dropIndex(String args) throws Exception {
        String[] parts = args.split("\\s+");
        if (parts.length < 1) {
            System.out.println("用法: index drop <name> [-f]");
            return;
        }

        String name = parts[0];
        boolean force = args.contains("-f");

        if (!force) {
            System.out.print("确认删除索引 '" + name + "'? (y/N): ");
            String confirm = graphManager.getScanner().nextLine().trim().toLowerCase();
            if (!confirm.equals("y") && !confirm.equals("yes")) {
                System.out.println("操作已取消");
                return;
            }
        }

        IndexManager indexManager = new IndexManager(graphManager);
        boolean success;
        try (Spinner spinner = Spinner.start("正在删除索引 '" + name + "'")) {
            success = indexManager.dropIndexSafely(name);
        }

        if (success) {
            System.out.println("✓ 索引 '" + name + "' 删除成功");
        } else {
            String status = indexManager.getIndexStatus(name);
            System.out.println("✗ 删除索引 '" + name + "' 失败 (当前状态: " + status + ")");
            if ("DISABLED".equals(status)) {
                System.out.println("  索引数据已删除，但索引定义仍存在，删除定义失败；可重试 'index drop " + name + " -f'。");
            }
        }
    }

    private void showStatus(String name) {
        if (name.isEmpty()) {
            System.out.println("用法: index status <name>");
            return;
        }

        IndexManager indexManager = new IndexManager(graphManager);
        String status = indexManager.getIndexStatus(name);
        System.out.println("索引 '" + name + "' 状态: " + status);
    }

    private void enableIndex(String name) throws Exception {
        if (name.isEmpty()) {
            System.out.println("用法: index enable <name>");
            return;
        }

        IndexManager indexManager = new IndexManager(graphManager);
        boolean success;
        try (Spinner spinner = Spinner.start("正在启用索引 '" + name + "'")) {
            success = indexManager.enableIndex(name);
        }

        if (success) {
            System.out.println("✓ 索引 '" + name + "' 启用成功");
        } else {
            System.out.println("✗ 启用索引 '" + name + "' 失败");
        }
    }

    private void disableIndex(String name) throws Exception {
        if (name.isEmpty()) {
            System.out.println("用法: index disable <name>");
            return;
        }

        IndexManager indexManager = new IndexManager(graphManager);
        boolean success;
        try (Spinner spinner = Spinner.start("正在禁用索引 '" + name + "'")) {
            success = indexManager.disableIndex(name);
        }

        if (success) {
            System.out.println("✓ 索引 '" + name + "' 禁用成功");
        } else {
            System.out.println("✗ 禁用索引 '" + name + "' 失败");
        }
    }

    private void reindex(String name) throws Exception {
        if (name.isEmpty()) {
            System.out.println("用法: index reindex <name>");
            return;
        }

        System.out.print("重建索引可能耗时较长，是否继续? (y/N): ");
        String confirm = graphManager.getScanner().nextLine().trim().toLowerCase();
        if (!confirm.equals("y") && !confirm.equals("yes")) {
            System.out.println("操作已取消");
            return;
        }

        System.out.println("开始重建索引 '" + name + "'... 可能需要一段时间。");
        long startTime = System.currentTimeMillis();

        IndexManager indexManager = new IndexManager(graphManager);
        boolean success;
        try (Spinner spinner = Spinner.start("正在重建索引 '" + name + "'")) {
            success = indexManager.reindex(name);
        }

        long duration = (System.currentTimeMillis() - startTime) / 1000;

        if (success) {
            System.out.println("✓ 索引 '" + name + "' 重建成功 (耗时 " + duration + "s)");
        } else {
            System.out.println("✗ 重建索引 '" + name + "' 失败");
        }
    }

    private void describeIndex(String name) {
        if (name.isEmpty()) {
            System.out.println("用法: index describe <name>");
            return;
        }

        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            JanusGraphIndex idx = mgmt.getGraphIndex(name);
            if (idx == null) {
                System.out.println("索引 '" + name + "' 未找到");
                return;
            }

            System.out.println("\n=== 索引: " + name + " ===");
            System.out.println("  类型: " + (idx.isMixedIndex() ? "Mixed" : "Composite"));
            System.out.println("  元素: " + idx.getIndexedElement().getSimpleName());
            System.out.println("  唯一: " + idx.isUnique());
            System.out.println("  状态: " + (idx.getFieldKeys().length > 0 ? idx.getIndexStatus(idx.getFieldKeys()[0]) : "UNKNOWN"));
            System.out.println("  属性键:");
            for (PropertyKey key : idx.getFieldKeys()) {
                System.out.println("    - " + key.name() + " (" + key.dataType().getSimpleName() + ")");
            }
        } finally {
            mgmt.commit();
        }
    }

    private void repairIndex(String name) throws Exception {
        if (name.isEmpty()) {
            System.out.println("用法: index repair <name>");
            return;
        }

        System.out.println("尝试修复索引 '" + name + "'...");

        JanusGraph graph = graphManager.getGraph();
        JanusGraphManagement mgmt = graph.openManagement();
        try {
            JanusGraphIndex idx = mgmt.getGraphIndex(name);
            if (idx == null) {
                System.out.println("索引 '" + name + "' 未找到");
                return;
            }

            String status = String.valueOf(idx.getFieldKeys().length > 0 ? idx.getIndexStatus(idx.getFieldKeys()[0]) : "UNKNOWN");
            System.out.println("当前状态: " + status);

            if (status.contains("INSTALLED")) {
                System.out.println("索引状态为 INSTALLED，尝试启用...");
                mgmt.updateIndex(idx, SchemaAction.ENABLE_INDEX);
                mgmt.commit();
                System.out.println("索引已启用，等待注册...");
                Thread.sleep(5000);
            } else if (status.contains("REGISTERED")) {
                System.out.println("索引状态为 REGISTERED，尝试重建...");
                mgmt.updateIndex(idx, SchemaAction.REINDEX);
                mgmt.commit();
                System.out.println("重建索引已启动...");
            } else if (status.contains("DISABLED")) {
                System.out.println("索引状态为 DISABLED，尝试启用...");
                mgmt.updateIndex(idx, SchemaAction.ENABLE_INDEX);
                mgmt.commit();
                System.out.println("索引已启用");
            } else {
                System.out.println("索引状态 '" + status + "' 无法自动修复");
            }

            Thread.sleep(3000);

            mgmt = graph.openManagement();
            idx = mgmt.getGraphIndex(name);
            System.out.println("新状态: " + (idx.getFieldKeys().length > 0 ? idx.getIndexStatus(idx.getFieldKeys()[0]) : "UNKNOWN"));
            mgmt.commit();

        } finally {
            mgmt.commit();
        }
    }
}