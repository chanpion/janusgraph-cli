package com.chenpp.janus.commands;

import com.chenpp.janus.core.GraphManager;
import org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;

import javax.script.Bindings;
import javax.script.SimpleBindings;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author April.Chen
 */
public class QueryCommand implements Command {

    private final GraphManager graphManager;
    private final GremlinGroovyScriptEngine engine = new GremlinGroovyScriptEngine();

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
        String script = normalize(args);

        Bindings bindings = new SimpleBindings();
        bindings.put("g", graph.traversal());
        bindings.put("graph", graph);

        long startTime = System.nanoTime();
        try {
            // materialize 会遍历惰性 Traversal，变更语句在此期间执行
            Object result = materialize(engine.eval(script, bindings));
            if (graph.tx().isOpen()) {
                graph.tx().commit();
            }
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            formatResult(result, duration);
        } catch (Exception e) {
            if (graph.tx().isOpen()) {
                graph.tx().rollback();
            }
            System.err.println("查询执行失败: " + e.getMessage());
        }
    }

    /**
     * 允许省略 g. 前缀（如 V().count()），并保留历史 count 简写。
     */
    private String normalize(String query) {
        String q = query.trim();
        if (q.matches("^[VvEe]\\(.*")) {
            return "g." + q;
        }
        if (q.equals("count") || q.equals("count()")) {
            return "g.V().count()";
        }
        return q;
    }

    /**
     * 求值结果物化：遍历 Traversal / Iterator 触发惰性执行。
     */
    private Object materialize(Object result) {
        if (result instanceof Traversal) {
            return ((Traversal<?, ?>) result).toList();
        }
        if (result instanceof Iterator) {
            List<Object> list = new ArrayList<>();
            ((Iterator<?>) result).forEachRemaining(list::add);
            return list;
        }
        return result;
    }

    private void formatResult(Object result, long duration) {
        System.out.println("结果 (耗时 " + duration + "ms):");

        if (result == null) {
            System.out.println("  成功");
            return;
        }

        if (result instanceof List) {
            List<?> list = (List<?>) result;
            // 聚合查询（如 count()）返回单元素标量列表，直接打印其值，避免与结果条数混淆
            if (list.size() == 1 && isScalar(list.get(0))) {
                System.out.println("  " + list.get(0));
                return;
            }
            System.out.println("  结果数: " + list.size());
            int max = Math.min(list.size(), 20);
            for (int i = 0; i < max; i++) {
                printItem(i, list.get(i));
            }
            if (list.size() > max) {
                System.out.println("  ... 还有 " + (list.size() - max) + " 条");
            }
        } else {
            printItem(-1, result);
        }
    }

    private boolean isScalar(Object value) {
        return value instanceof Number || value instanceof String
                || value instanceof Boolean || value instanceof Character;
    }

    private void printItem(int index, Object item) {
        String prefix = index >= 0 ? "  [" + index + "] " : "  ";
        if (item instanceof Vertex) {
            Vertex v = (Vertex) item;
            StringBuilder sb = new StringBuilder(prefix + "顶点: id=" + v.id() + ", label=" + v.label());
            v.properties().forEachRemaining(p -> sb.append(' ').append(p.key()).append('=').append(p.value()));
            System.out.println(sb);
        } else if (item instanceof Edge) {
            Edge e = (Edge) item;
            StringBuilder sb = new StringBuilder(prefix + "边: id=" + e.id() + ", label=" + e.label()
                    + " (" + e.outVertex().id() + " -> " + e.inVertex().id() + ")");
            e.properties().forEachRemaining(p -> sb.append(' ').append(p.key()).append('=').append(p.value()));
            System.out.println(sb);
        } else {
            System.out.println(prefix + item);
        }
    }

    private void showUsage() {
        System.out.println("\n=== 查询命令 ===");
        System.out.println("  query <gremlin>    - 执行 Gremlin (读写)");
        System.out.println("  q <gremlin>        - 别名");
        System.out.println("  g <gremlin>        - 别名");
        System.out.println();
        System.out.println("读示例:");
        System.out.println("  q g.V().count()");
        System.out.println("  q V().has('name','marko').limit(10)");
        System.out.println();
        System.out.println("写示例 (自动提交):");
        System.out.println("  g.addV('person').property('name','marko').property('age',29)");
        System.out.println("  g.V().has('name','marko').as('a').V().has('name','vadas').addE('knows').from('a')");
        System.out.println();
    }
}
