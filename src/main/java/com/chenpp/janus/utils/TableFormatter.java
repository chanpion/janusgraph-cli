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
            System.out.println("(empty)");
            return;
        }

        int colCount = rows.get(0).length;
        int[] colWidths = new int[colCount];

        for (String[] row : rows) {
            for (int i = 0; i < Math.min(row.length, colCount); i++) {
                if (row[i] != null) {
                    colWidths[i] = Math.max(colWidths[i], row[i].length());
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
            System.out.print(" " + String.format("%-" + colWidths[i] + "s", cell) + " |");
        }
        System.out.println();
    }
}