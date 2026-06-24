package com.screendiff;

import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import com.github.difflib.text.DiffRow.Tag;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 画像ファイル名（拡張子 .txt）に対応するテキストを比較する（java-diff-utils 使用）。 */
public final class TextComparator {

    private static final DiffRowGenerator DIFF_ROW_GENERATOR = DiffRowGenerator.create()
            .showInlineDiffs(true)
            .inlineDiffByWord(true)
            .ignoreWhiteSpaces(true)
            .oldTag((tag, open) -> open ? "<span class=\"diff-inline-old\">" : "</span>")
            .newTag((tag, open) -> open ? "<span class=\"diff-inline-new\">" : "</span>")
            .build();

    public record TextResult(int diffLineCount, boolean available) {}

    public enum LineKind {
        SAME, REMOVED, ADDED, CHANGED
    }

    /** oldLine/newLine は HTML（行内差分 span 含む。ライブラリ側でエスケープ済み） */
    public record LineDiffRow(LineKind kind, String oldLine, String newLine) {}

    public record TextDiffContent(boolean available, int diffLineCount, List<LineDiffRow> rows) {
        public static TextDiffContent unavailable() {
            return new TextDiffContent(false, -1, List.of());
        }
    }

    private TextComparator() {}

    public static TextResult compareMembers(
            File oldDir,
            File newDir,
            List<String> oldImagePaths,
            List<String> newImagePaths,
            TextTransformUtil.TextTransformOptions oldTextTransform,
            TextTransformUtil.TextTransformOptions newTextTransform) throws IOException {
        List<LineDiffRow> rows = diffMemberRows(
                oldDir, newDir, oldImagePaths, newImagePaths, oldTextTransform, newTextTransform);
        if (rows == null) {
            return new TextResult(-1, false);
        }
        return new TextResult(countDiffDisplayRows(rows), true);
    }

    public static TextDiffContent loadTextDiffContentForMembers(
            File oldDir,
            File newDir,
            List<String> oldImagePaths,
            List<String> newImagePaths,
            TextTransformUtil.TextTransformOptions oldTextTransform,
            TextTransformUtil.TextTransformOptions newTextTransform) throws IOException {
        List<LineDiffRow> rows = diffMemberRows(
                oldDir, newDir, oldImagePaths, newImagePaths, oldTextTransform, newTextTransform);
        if (rows == null) {
            return TextDiffContent.unavailable();
        }
        return new TextDiffContent(true, countDiffDisplayRows(rows), rows);
    }

    private static List<LineDiffRow> diffMemberRows(
            File oldDir,
            File newDir,
            List<String> oldImagePaths,
            List<String> newImagePaths,
            TextTransformUtil.TextTransformOptions oldTextTransform,
            TextTransformUtil.TextTransformOptions newTextTransform) throws IOException {
        int pairCount = Math.min(oldImagePaths.size(), newImagePaths.size());
        if (pairCount == 0) {
            return null;
        }

        List<LineDiffRow> merged = new ArrayList<>();
        boolean anyAvailable = false;
        for (int i = 0; i < pairCount; i++) {
            Path oldTxt = textPath(oldDir, ImageTextGroupUtil.textBaseNameForImage(oldImagePaths.get(i)));
            Path newTxt = textPath(newDir, ImageTextGroupUtil.textBaseNameForImage(newImagePaths.get(i)));
            if (!Files.isRegularFile(oldTxt) || !Files.isRegularFile(newTxt)) {
                continue;
            }
            anyAvailable = true;
            merged.addAll(diffLines(
                    readTransformedLines(oldTxt, oldTextTransform),
                    readTransformedLines(newTxt, newTextTransform)));
        }
        return anyAvailable ? merged : null;
    }

    /** 差分行数（削除・追加・変更は各1行。HTML 表示・サマリで共通） */
    static int countDiffDisplayRows(List<LineDiffRow> rows) {
        int count = 0;
        for (LineDiffRow row : rows) {
            if (row.kind() != LineKind.SAME) {
                count++;
            }
        }
        return count;
    }

    static List<LineDiffRow> diffLines(List<String> oldLines, List<String> newLines) {
        List<DiffRow> rows = DIFF_ROW_GENERATOR.generateDiffRows(oldLines, newLines);
        List<LineDiffRow> result = new ArrayList<>(rows.size());
        for (DiffRow row : rows) {
            result.add(new LineDiffRow(
                    toKind(row.getTag()),
                    row.getOldLine(),
                    row.getNewLine()));
        }
        return result;
    }

    private static LineKind toKind(Tag tag) {
        return switch (tag) {
            case EQUAL -> LineKind.SAME;
            case INSERT -> LineKind.ADDED;
            case DELETE -> LineKind.REMOVED;
            case CHANGE -> LineKind.CHANGED;
        };
    }

    private static List<String> readTransformedLines(
            Path path, TextTransformUtil.TextTransformOptions transform) throws IOException {
        String body = TextTransformUtil.loadTextForComparison(path, transform);
        if (body.isEmpty()) {
            return List.of();
        }
        return List.of(body.split("\n"));
    }

    static Path textPath(File dir, String textBaseName) {
        return dir.toPath().resolve(textBaseName.replace('/', File.separatorChar) + ".txt");
    }
}
