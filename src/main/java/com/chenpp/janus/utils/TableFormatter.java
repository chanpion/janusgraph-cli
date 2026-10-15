package com.chenpp.janus.utils;

import java.util.List;

/**
 * @author April.Chen
 */
public class TableFormatter {

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    public static void printTable(List<String[]> rows) {
        if (rows == null || rows.isEmpty()) {
            System.out.println("(空)");
            return;
        }

        int colCount = rows.get(0).length;
        int[] colWidths = new int[colCount];

        for (String[] row : rows) {
            for (int i = 0; i < Math.min(row.length, colCount); i++) {
                if (row[i] != null) {
                    colWidths[i] = Math.max(colWidths[i], displayWidth(row[i]));
                }
            }
        }

        printSeparator(colWidths);
        printRow(rows.get(0), colWidths);
        printSeparator(colWidths);

        for (int i = 1; i < rows.size(); i++) {
            printRow(rows.get(i), colWidths);
        }
        printSeparator(colWidths);
    }

    private static void printSeparator(int[] colWidths) {
        System.out.print("+");
        for (int width : colWidths) {
            System.out.print(repeat("-", width + 2) + "+");
        }
        System.out.println();
    }

    private static void printRow(String[] row, int[] colWidths) {
        System.out.print("|");
        for (int i = 0; i < colWidths.length; i++) {
            String cell = (i < row.length && row[i] != null) ? row[i] : "";
            System.out.print(" " + cell + repeat(" ", colWidths[i] - displayWidth(cell)) + " |");
        }
        System.out.println();
    }

    private static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            width += isWide(s.charAt(i)) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE4F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }
}