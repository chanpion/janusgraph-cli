package com.chenpp.janus.utils;

/**
 * 单行原地刷新的加载指示器。包裹耗时阻塞调用，让等待期间有活动反馈；
 * 用 try-with-resources 使用，close 时清行，之后才打印结果。
 *
 * <pre>
 * try (Spinner s = Spinner.start("正在创建索引")) {
 *     doSlowWork();
 * }
 * </pre>
 *
 * @author April.Chen
 */
public class Spinner implements AutoCloseable {

    private static final char[] FRAMES = {'|', '/', '-', '\\'};
    private static final int CLEAR_WIDTH = 40;
    private static final long INTERVAL_MS = 150;

    private final Thread thread;

    private Spinner(String message) {
        thread = new Thread(() -> {
            int i = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    System.out.print("\r" + message + " " + FRAMES[i++ % FRAMES.length]);
                    System.out.flush();
                    Thread.sleep(INTERVAL_MS);
                }
            } catch (InterruptedException ignored) {
                // 正常停止
            }
        }, "spinner");
        thread.setDaemon(true);
        thread.start();
    }

    public static Spinner start(String message) {
        return new Spinner(message);
    }

    @Override
    public void close() {
        thread.interrupt();
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.print("\r" + spaces(CLEAR_WIDTH) + "\r");
        System.out.flush();
    }

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
