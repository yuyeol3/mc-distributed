package mcd.world;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

final class SafeFiles {
    static final long MAX_FILE = 100_000_000L;
    static final long MAX_TOTAL = 1_500_000_000L;
    static final int MAX_FILES = 100_000;

    static Path resolve(Path root, String relative) throws IOException {
        String normalized = relative.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":")) throw new IOException("허용하지 않는 경로: " + relative);
        for (String part : normalized.split("/", -1)) {
            String lower = part.toLowerCase(Locale.ROOT);
            if (part.isBlank() || part.equals(".") || part.equals("..") || lower.equals(".git")
                    || part.endsWith(".") || part.endsWith(" ") || part.matches(".*[<>\"|?*].*")
                    || lower.matches("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
                throw new IOException("허용하지 않는 경로: " + relative);
            }
        }
        Path path = root.resolve(normalized).normalize();
        if (!path.startsWith(root.normalize())) throw new IOException("폴더 밖을 가리키는 경로");
        return path;
    }

    static void copyTree(Path source, Path target) throws IOException {
        long[] bytes = {0};
        int[] count = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) throw new IOException("링크는 가져올 수 없습니다: " + dir);
                Path next = dir.equals(source) ? target : resolve(target, source.relativize(dir).toString());
                Files.createDirectories(next);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("일반 파일만 가져올 수 있습니다: " + file);
                if (file.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                if (file.getFileName().toString().toLowerCase(Locale.ROOT).startsWith(".git")) throw new IOException("월드 안의 Git 설정은 가져올 수 없습니다: " + file);
                if (attrs.size() >= MAX_FILE) throw new IOException("파일 하나가 너무 큽니다: " + file);
                bytes[0] += attrs.size();
                if (++count[0] > MAX_FILES || bytes[0] > MAX_TOTAL) throw new IOException("가져오기 크기 제한을 넘었습니다");
                Path next = resolve(target, source.relativize(file).toString());
                Files.createDirectories(next.getParent());
                Files.copy(file, next, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getParent() == null || Files.isSymbolicLink(path)) throw new IOException("삭제 대상이 잘못되었습니다: " + path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                file.toFile().setWritable(true);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
