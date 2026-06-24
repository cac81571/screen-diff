package com.screendiff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** テキスト比較前に適用する正規表現置換。 */
public final class TextTransformUtil {

    private static final Pattern JS_REGEX_REPLACE = Pattern.compile(
            "\\.replace\\(\\s*/((?:\\\\.|[^/])*)(/[gims]*)?\\s*,\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\)");

    public record ReplaceRule(Pattern pattern, String replacement) {}

    public record TextTransformOptions(boolean enabled, List<ReplaceRule> rules) {
        public static TextTransformOptions disabled() {
            return new TextTransformOptions(false, List.of());
        }
    }

    private TextTransformUtil() {}

    public static TextTransformOptions parse(boolean enabled, String definition) {
        if (!enabled || definition == null || definition.isBlank()) {
            return TextTransformOptions.disabled();
        }
        List<ReplaceRule> rules = parseRules(definition);
        if (rules.isEmpty()) {
            return TextTransformOptions.disabled();
        }
        return new TextTransformOptions(true, List.copyOf(rules));
    }

    public static String apply(String text, TextTransformOptions options) {
        if (!options.enabled() || options.rules().isEmpty()) {
            return text;
        }
        String result = text;
        for (ReplaceRule rule : options.rules()) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    private static List<ReplaceRule> parseRules(String definition) {
        String trimmed = definition.trim();
        if (trimmed.contains(".replace(")) {
            return parseJsReplaceChain(trimmed);
        }
        List<ReplaceRule> rules = new ArrayList<>();
        for (String line : definition.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            rules.add(parseLineRule(line));
        }
        return rules;
    }

    private static List<ReplaceRule> parseJsReplaceChain(String text) {
        List<ReplaceRule> rules = new ArrayList<>();
        Matcher matcher = JS_REGEX_REPLACE.matcher(text);
        while (matcher.find()) {
            String flags = matcher.group(2);
            rules.add(new ReplaceRule(
                    compileSlashBody(matcher.group(1), flags == null ? "" : flags.substring(1)),
                    unescapeJavaString(matcher.group(3))));
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("変換ルールを解釈できません: " + text);
        }
        return rules;
    }

    private static ReplaceRule parseLineRule(String line) {
        int splitAt = line.indexOf('\t');
        int splitLen = 1;
        if (splitAt < 0) {
            splitAt = line.indexOf(" → ");
            splitLen = 3;
        }
        if (splitAt < 0) {
            throw new IllegalArgumentException(
                    "ルール形式: /pattern/flags<TAB>置換後 または /pattern/flags → 置換後: " + line);
        }
        String patternPart = line.substring(0, splitAt).trim();
        String replacementPart = line.substring(splitAt + splitLen).trim();
        if (!patternPart.startsWith("/")) {
            throw new IllegalArgumentException("パターンは /正規表現/フラグ 形式にしてください: " + line);
        }
        int lastSlash = patternPart.lastIndexOf('/');
        if (lastSlash <= 0) {
            throw new IllegalArgumentException("パターンは /正規表現/フラグ 形式にしてください: " + line);
        }
        String body = patternPart.substring(1, lastSlash);
        String flags = patternPart.substring(lastSlash + 1);
        return new ReplaceRule(
                compileSlashBody(body, flags),
                unescapeJavaString(replacementPart));
    }

    private static Pattern compileSlashBody(String body, String flags) {
        int javaFlags = 0;
        if (flags.contains("i")) {
            javaFlags |= Pattern.CASE_INSENSITIVE;
        }
        if (flags.contains("m")) {
            javaFlags |= Pattern.MULTILINE;
        }
        if (flags.contains("s")) {
            javaFlags |= Pattern.DOTALL;
        }
        return Pattern.compile(unescapeJavaString(body), javaFlags);
    }

    static String unescapeJavaString(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                sb.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case '\\' -> sb.append('\\');
                case '"' -> sb.append('"');
                default -> {
                    sb.append('\\');
                    sb.append(next);
                }
            }
        }
        return sb.toString();
    }

    static Path backupPath(Path source) {
        return Path.of(source.toString() + ".bak");
    }

    /**
     * 変換 ON かつ .bak 未作成 … 元内容を .bak へ保存し、変換結果を .txt へ書き込む。
     * 変換 ON かつ .bak あり … 変換済みとみなし .txt をそのまま読む。
     */
    public static String loadTextForComparison(Path path, TextTransformOptions transform) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        if (!transform.enabled()) {
            return raw;
        }
        Path backup = backupPath(path);
        if (Files.isRegularFile(backup)) {
            return raw;
        }
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        String transformed = apply(raw, transform);
        Files.writeString(path, transformed, StandardCharsets.UTF_8);
        return transformed;
    }
}
