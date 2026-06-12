package com.screendiff;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 画像のグループ化と旧・新フォルダ間のマッチング。
 * <ul>
 *   <li>ファイル名形式 … {@code 数字(英数字)_ファイル名}</li>
 *   <li>先頭の {@code 数字(英数字)_} が同じ画像は1グループ（縦結合）</li>
 *   <li>マッチング … {@code 数字(英数字)_} の括弧部分のみ除去（例: {@code 001(a)_sampleA.png} → {@code 001_sampleA.png}）</li>
 *   <li>テキスト … {@code 数字(英数字)_} の括弧部分のみ除去（例: {@code 1(a)_login(1).png} → {@code 1_login(1).txt}）</li>
 * </ul>
 */
final class ImageTextGroupUtil {

    private static final Pattern GROUP_PREFIX = Pattern.compile("^(\\d+\\([a-zA-Z0-9]+\\)_)");
    /** テキスト名用 … {@code 1(a)_} → {@code 1_}（括弧内のみ除去） */
    private static final Pattern TEXT_GROUP_PREFIX = Pattern.compile("^(\\d+)\\([a-zA-Z0-9]+\\)_");

    record ImageGroup(String matchKey, String sortKey, List<String> memberPaths) {}

    record ComparisonUnit(
            String displayName,
            List<String> oldImagePaths,
            List<String> newImagePaths) {

        boolean isCombined() {
            return oldImagePaths.size() > 1 || newImagePaths.size() > 1;
        }
    }

    private ImageTextGroupUtil() {}

    static List<ComparisonUnit> buildComparisonUnits(List<String> oldPaths, List<String> newPaths) {
        List<ImageGroup> oldGroups = mergeGroupsByMatchKey(buildGroups(oldPaths));
        Map<String, Deque<ImageGroup>> newByMatchKey = new LinkedHashMap<>();
        for (ImageGroup group : mergeGroupsByMatchKey(buildGroups(newPaths))) {
            newByMatchKey.computeIfAbsent(group.matchKey(), k -> new ArrayDeque<>()).add(group);
        }

        List<ComparisonUnit> units = new ArrayList<>();
        for (ImageGroup oldGroup : oldGroups) {
            Deque<ImageGroup> candidates = newByMatchKey.get(oldGroup.matchKey());
            if (candidates == null || candidates.isEmpty()) {
                continue;
            }
            ImageGroup newGroup = candidates.removeFirst();
            units.add(new ComparisonUnit(
                    oldGroup.matchKey(),
                    List.copyOf(oldGroup.memberPaths()),
                    List.copyOf(newGroup.memberPaths())));
        }
        return units;
    }

    static List<String> listUnmatchedOldMatchKeys(List<String> oldPaths, List<String> newPaths) {
        List<ImageGroup> oldGroups = mergeGroupsByMatchKey(buildGroups(oldPaths));
        Map<String, Deque<ImageGroup>> newByMatchKey = new LinkedHashMap<>();
        for (ImageGroup group : mergeGroupsByMatchKey(buildGroups(newPaths))) {
            newByMatchKey.computeIfAbsent(group.matchKey(), k -> new ArrayDeque<>()).add(group);
        }

        List<String> unmatched = new ArrayList<>();
        for (ImageGroup oldGroup : oldGroups) {
            Deque<ImageGroup> candidates = newByMatchKey.get(oldGroup.matchKey());
            if (candidates == null || candidates.isEmpty()) {
                unmatched.add(oldGroup.matchKey());
                continue;
            }
            candidates.removeFirst();
        }
        return unmatched;
    }

    static List<ImageGroup> buildGroups(List<String> sortedRelativePaths) {
        Map<String, ImageGroup> groupByKey = new LinkedHashMap<>();
        List<ImageGroup> groups = new ArrayList<>();

        for (String path : sortedRelativePaths) {
            String groupKey = groupKey(path);
            ImageGroup group = groupByKey.get(groupKey);
            if (group == null) {
                group = new ImageGroup(matchKey(path), path, new ArrayList<>());
                groupByKey.put(groupKey, group);
                groups.add(group);
            }
            group.memberPaths().add(path);
        }
        return groups;
    }

    /** 同一マッチキー（{@code 001(a)_} と {@code 001(b)_} など）のグループを縦結合用に統合 */
    private static List<ImageGroup> mergeGroupsByMatchKey(List<ImageGroup> groups) {
        Map<String, ImageGroup> merged = new LinkedHashMap<>();
        for (ImageGroup group : groups) {
            merged.merge(group.matchKey(), group, ImageTextGroupUtil::combineGroups);
        }
        return new ArrayList<>(merged.values());
    }

    private static ImageGroup combineGroups(ImageGroup left, ImageGroup right) {
        List<String> paths = new ArrayList<>(left.memberPaths());
        paths.addAll(right.memberPaths());
        Collections.sort(paths);
        return new ImageGroup(left.matchKey(), paths.get(0), List.copyOf(paths));
    }

    /** マッチング用キー（{@code 001(a)_sampleA.png} → {@code 001_sampleA.png}） */
    static String matchKey(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        String dir = slash >= 0 ? relativePath.substring(0, slash + 1) : "";
        String fileName = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
        return dir + normalizeVariantPrefix(fileName);
    }

    static String textBaseNameForImage(String imageRelativePath) {
        int slash = imageRelativePath.lastIndexOf('/');
        String dir = slash >= 0 ? imageRelativePath.substring(0, slash + 1) : "";
        String fileName = slash >= 0 ? imageRelativePath.substring(slash + 1) : imageRelativePath;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return dir + normalizeVariantPrefix(base);
    }

    /** {@code 001(a)_sampleA.png} → {@code 001_sampleA.png}、{@code 001_sampleA.png} はそのまま */
    static String normalizeVariantPrefix(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return TEXT_GROUP_PREFIX.matcher(fileName).replaceFirst("$1_");
        }
        String base = fileName.substring(0, dot);
        String ext = fileName.substring(dot);
        return TEXT_GROUP_PREFIX.matcher(base).replaceFirst("$1_") + ext;
    }

    static String stripGroupPrefix(String fileName) {
        return GROUP_PREFIX.matcher(fileName).replaceFirst("");
    }

    static String extractGroupPrefix(String fileName) {
        Matcher matcher = GROUP_PREFIX.matcher(fileName);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String groupKey(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        String dir = slash >= 0 ? relativePath.substring(0, slash + 1) : "";
        String fileName = slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
        String prefix = extractGroupPrefix(fileName);
        if (prefix.isEmpty()) {
            return relativePath;
        }
        return dir + prefix;
    }
}
