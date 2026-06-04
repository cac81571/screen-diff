package com.screendiff;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 画像と同じベース名の .txt を比較する。 */
public final class TextComparator {

    public record TextResult(int diffLineCount, boolean available) {}

    public enum LineKind {
        SAME, REMOVED, ADDED, CHANGED
    }

    public record LineDiffRow(LineKind kind, String oldLine, String newLine) {}

    public record TextDiffContent(boolean available, int diffLineCount, List<LineDiffRow> rows) {
        public static TextDiffContent unavailable() {
            return new TextDiffContent(false, -1, List.of());
        }
    }

    private TextComparator() {}

    public static TextResult compare(File oldDir, File newDir, String imageFileName) throws IOException {
        Path oldTxt = textPath(oldDir, imageFileName);
        Path newTxt = textPath(newDir, imageFileName);
        if (!Files.isRegularFile(oldTxt) || !Files.isRegularFile(newTxt)) {
            return new TextResult(-1, false);
        }
        String oldNorm = normalize(Files.readString(oldTxt, StandardCharsets.UTF_8));
        String newNorm = normalize(Files.readString(newTxt, StandardCharsets.UTF_8));
        return new TextResult(countDiffDisplayRows(diffLines(oldNorm, newNorm)), true);
    }

    public static TextDiffContent loadTextDiffContent(File oldDir, File newDir, String imageFileName)
            throws IOException {
        Path oldTxt = textPath(oldDir, imageFileName);
        Path newTxt = textPath(newDir, imageFileName);
        if (!Files.isRegularFile(oldTxt) || !Files.isRegularFile(newTxt)) {
            return TextDiffContent.unavailable();
        }
        String oldNorm = normalize(Files.readString(oldTxt, StandardCharsets.UTF_8));
        String newNorm = normalize(Files.readString(newTxt, StandardCharsets.UTF_8));
        List<LineDiffRow> rows = diffLines(oldNorm, newNorm);
        return new TextDiffContent(true, countDiffDisplayRows(rows), rows);
    }

    /** 差分行数（削除・追加・変更は各1行。HTML 表示・サマリ・フィルタで共通） */
    static int countDiffDisplayRows(List<LineDiffRow> rows) {
        int count = 0;
        for (LineDiffRow row : rows) {
            if (row.kind() != LineKind.SAME) {
                count++;
            }
        }
        return count;
    }

    static List<LineDiffRow> diffLines(String oldText, String newText) {
        String[] oldLines = splitLines(oldText);
        String[] newLines = splitLines(newText);
        int n = oldLines.length;
        int m = newLines.length;
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (oldLines[i].equals(newLines[j])) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }
        List<LineDiffRow> raw = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (oldLines[i].equals(newLines[j])) {
                raw.add(new LineDiffRow(LineKind.SAME, oldLines[i], newLines[j]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                raw.add(new LineDiffRow(LineKind.REMOVED, oldLines[i], ""));
                i++;
            } else {
                raw.add(new LineDiffRow(LineKind.ADDED, "", newLines[j]));
                j++;
            }
        }
        while (i < n) {
            raw.add(new LineDiffRow(LineKind.REMOVED, oldLines[i++], ""));
        }
        while (j < m) {
            raw.add(new LineDiffRow(LineKind.ADDED, "", newLines[j++]));
        }
        return mergeAdjacentChanges(raw);
    }

    private static List<LineDiffRow> mergeAdjacentChanges(List<LineDiffRow> rows) {
        List<LineDiffRow> merged = new ArrayList<>();
        for (int k = 0; k < rows.size(); k++) {
            LineDiffRow row = rows.get(k);
            if (row.kind() == LineKind.REMOVED
                    && k + 1 < rows.size()
                    && rows.get(k + 1).kind() == LineKind.ADDED) {
                LineDiffRow next = rows.get(k + 1);
                merged.add(new LineDiffRow(LineKind.CHANGED, row.oldLine(), next.newLine()));
                k++;
            } else {
                merged.add(row);
            }
        }
        return merged;
    }

    private static String[] splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        return text.split("\n", -1);
    }

    static Path textPath(File dir, String imageFileName) {
        int dot = imageFileName.lastIndexOf('.');
        String base = dot > 0 ? imageFileName.substring(0, dot) : imageFileName;
        return dir.toPath().resolve(base + ".txt");
    }

    static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(t.replaceAll("\\s+", " "));
            }
        }
        return sb.toString();
    }

    static double similarityPercent(String a, String b) {
        if (a.equals(b)) {
            return 100.0;
        }
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 100.0;
        }
        int dist = levenshteinDistance(a, b);
        return Math.max(0.0, (1.0 - (double) dist / maxLen) * 100.0);
    }

    private static int levenshteinDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
