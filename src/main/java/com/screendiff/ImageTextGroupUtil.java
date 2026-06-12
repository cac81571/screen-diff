package com.screendiff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 画像 N 枚 : テキスト 1 ファイルの比較単位（末尾 _xxx のバリアント名） */
final class ImageTextGroupUtil {

    record ComparisonUnit(
            String displayName,
            String textBaseName,
            List<String> oldImagePaths,
            List<String> newImagePaths) {

        boolean isCombined() {
            return oldImagePaths.size() > 1 || newImagePaths.size() > 1;
        }
    }

    private ImageTextGroupUtil() {}

    static List<ComparisonUnit> buildComparisonUnits(List<String> oldPaths, List<String> newPaths) {
        Set<String> assignedTextBases = new HashSet<>();
        List<ComparisonUnit> units = new ArrayList<>();

        for (String path : oldPaths) {
            String textBase = resolveTextBase(path, oldPaths, newPaths);
            if (assignedTextBases.contains(textBase)) {
                continue;
            }
            assignedTextBases.add(textBase);

            String dir = directoryOf(textBase);
            String baseName = baseNameFromTextBase(textBase);
            List<String> oldMembers = collectGroupPaths(oldPaths, dir, baseName);
            List<String> newMembers = collectGroupPaths(newPaths, dir, baseName);
            if (oldMembers.isEmpty() || newMembers.isEmpty()) {
                continue;
            }
            units.add(new ComparisonUnit(
                    displayName(textBase, oldMembers, newMembers),
                    textBase,
                    List.copyOf(oldMembers),
                    List.copyOf(newMembers)));
        }
        return units;
    }

    /** 旧・新のいずれかで 2 枚以上、または合計 2 枚以上なら同一 textBase にまとめる */
    static String resolveTextBase(String path, List<String> oldPaths, List<String> newPaths) {
        String dir = directoryOf(path);
        String fileName = fileNameOf(path);
        String fullName = nameWithoutExtension(fileName);
        String stripped = stripVariantSuffix(fileName);

        if (stripped != null && groupSize(oldPaths, newPaths, dir, stripped) >= 2) {
            return textBaseRelativePath(dir, stripped);
        }
        if (groupSize(oldPaths, newPaths, dir, fullName) >= 2) {
            return textBaseRelativePath(dir, fullName);
        }
        return textBaseRelativePath(dir, fullName);
    }

    static List<String> collectGroupPaths(List<String> paths, String dir, String baseName) {
        List<String> members = new ArrayList<>();
        for (String path : paths) {
            if (!directoryOf(path).equals(dir)) {
                continue;
            }
            if (belongsToGroup(fileNameOf(path), baseName)) {
                members.add(path);
            }
        }
        Collections.sort(members);
        return members;
    }

    private static int groupSize(List<String> oldPaths, List<String> newPaths, String dir, String baseName) {
        return collectGroupPaths(oldPaths, dir, baseName).size()
                + collectGroupPaths(newPaths, dir, baseName).size();
    }

    private static boolean belongsToGroup(String fileName, String baseName) {
        String name = nameWithoutExtension(fileName);
        if (name.equals(baseName)) {
            return true;
        }
        if (!name.startsWith(baseName + "_")) {
            return false;
        }
        String stripped = stripVariantSuffix(fileName);
        return stripped != null && stripped.equals(baseName);
    }

    private static String displayName(String textBase, List<String> oldMembers, List<String> newMembers) {
        if (oldMembers.size() <= 1 && newMembers.size() <= 1) {
            return oldMembers.get(0);
        }
        return textBase + ".png";
    }

    static String baseNameFromTextBase(String textBase) {
        int slash = textBase.lastIndexOf('/');
        return slash >= 0 ? textBase.substring(slash + 1) : textBase;
    }

    static String dirFromTextBase(String textBase) {
        int slash = textBase.lastIndexOf('/');
        return slash >= 0 ? textBase.substring(0, slash) : "";
    }

    /** 001_sampleA_a.png → 001_sampleA（末尾 _xxx を除去）。該当なしなら null */
    static String stripVariantSuffix(String fileName) {
        String name = nameWithoutExtension(fileName);
        int underscore = name.lastIndexOf('_');
        if (underscore <= 0 || underscore >= name.length() - 1) {
            return null;
        }
        return name.substring(0, underscore);
    }

    static String fileNameOf(String relativePath) {
        int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        return slash >= 0 ? relativePath.substring(slash + 1) : relativePath;
    }

    static String directoryOf(String relativePath) {
        int slash = Math.max(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'));
        return slash >= 0 ? relativePath.substring(0, slash) : "";
    }

    static String nameWithoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String textBaseRelativePath(String dir, String baseName) {
        if (dir.isEmpty()) {
            return baseName;
        }
        return dir + "/" + baseName;
    }
}
