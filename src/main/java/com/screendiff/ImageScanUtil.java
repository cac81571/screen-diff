package com.screendiff;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** 比較対象画像の列挙（直下のみ / サブフォルダ含む） */
final class ImageScanUtil {

    private static final String IMAGE_PATTERN = "(?i).*\\.(png|jpg|jpeg|bmp)";

    private ImageScanUtil() {}

    static List<String> listRelativeImagePaths(File root, boolean includeSubfolders) throws IOException {
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        List<String> paths = new ArrayList<>();
        if (includeSubfolders) {
            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().matches(IMAGE_PATTERN))
                        .map(p -> toRelativePath(rootPath, p))
                        .forEach(paths::add);
            }
        } else {
            File[] files = root.listFiles((d, name) -> name.matches(IMAGE_PATTERN));
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        paths.add(file.getName());
                    }
                }
            }
        }
        Collections.sort(paths);
        return paths;
    }

    static File resolve(File root, String relativePath) {
        return root.toPath().resolve(relativePath.replace('/', File.separatorChar)).toFile();
    }

    private static String toRelativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
