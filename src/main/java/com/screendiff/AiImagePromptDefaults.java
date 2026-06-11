package com.screendiff;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** AIプロンプトの初期テンプレート（classpath リソース） */
public final class AiImagePromptDefaults {

    static final String RESOURCE_PATH = "ai-image-prompt.txt";

    private AiImagePromptDefaults() {}

    public static String loadDefault() {
        try (InputStream in = AiImagePromptDefaults.class.getResourceAsStream("/" + RESOURCE_PATH)) {
            if (in == null) {
                throw new IOException("resource not found: " + RESOURCE_PATH);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("AIプロンプトの初期値を読み込めません: " + RESOURCE_PATH, e);
        }
    }
}
