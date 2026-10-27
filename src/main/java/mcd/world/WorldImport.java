package mcd.world;

import mcd.server.PaperBuild;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipInputStream;

public final class WorldImport {
    private static final List<String> WORLDS = List.of("world", "world_nether", "world_the_end");
    private WorldImport() {}

    public static Prepared prepare(Path source) throws IOException {
        Path stage = Files.createTempDirectory("mcd-world-import-");
        try {
            Path root = source;
            if (Files.isSymbolicLink(source)) throw new IOException("링크는 가져올 수 없습니다");
            if (!Files.isDirectory(source)) {
                root = stage.resolve("unzipped");
                Files.createDirectories(root);
                unzip(source, root);
                if (!Files.exists(root.resolve("level.dat")) && !Files.exists(root.resolve("world/level.dat"))) {
                    try (var files = Files.list(root)) {
                        List<Path> contents = files.toList();
                        if (contents.size() == 1 && Files.isDirectory(contents.getFirst())) root = contents.getFirst();
                    }
                }
            }
            Path world = Files.isRegularFile(root.resolve("level.dat")) ? root : root.resolve("world");
            if (!Files.isRegularFile(world.resolve("level.dat"))) throw new IOException("선택한 폴더/ZIP에 level.dat이 없습니다");
            Path payload = stage.resolve("payload");
            copyStoppedWorld(world, payload.resolve("world"));
            LevelMetadata.Info info = LevelMetadata.read(payload.resolve("world/level.dat"));
            if (!world.equals(root)) {
                for (String dimension : WORLDS.subList(1, WORLDS.size())) {
                    if (Files.isDirectory(root.resolve(dimension))) {
                        if (new PaperBuild(info.version(), 0).compareVersion("26.1") >= 0) {
                            throw new IOException("이 버전과 맞지 않는 분리 차원 구조입니다. Vanilla 형식으로 변환한 뒤 가져오세요.");
                        }
                        copyStoppedWorld(root.resolve(dimension), payload.resolve(dimension));
                    }
                }
            }
            long bytes = 0;
            int count = 0;
            try (var files = Files.walk(payload)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    bytes += Files.size(file);
                    if (++count > SafeFiles.MAX_FILES || bytes > SafeFiles.MAX_TOTAL) throw new IOException("월드가 가져오기 크기 제한을 넘었습니다");
                }
            }
            return new Prepared(stage, payload, info.version(), info.singlePlayer(), bytes);
        } catch (IOException | RuntimeException e) {
            SafeFiles.deleteTree(stage);
            throw e;
        }
    }

    private static void copyStoppedWorld(Path source, Path target) throws IOException {
        Path session = source.resolve("session.lock");
        if (!Files.exists(session)) { SafeFiles.copyTree(source, target); return; }
        try (var channel = java.nio.channels.FileChannel.open(session, StandardOpenOption.READ);
             var lock = channel.tryLock(0, Long.MAX_VALUE, true)) {
            if (lock == null) throw new IOException("가져올 월드가 실행 중입니다. 원본 Minecraft/서버를 종료하세요.");
            SafeFiles.copyTree(source, target);
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw new IOException("가져올 월드가 실행 중입니다. 원본 Minecraft/서버를 종료하세요.", e);
        }
    }

    public record Prepared(Path stagingDirectory, Path payload, String version, boolean singlePlayer, long bytes) implements AutoCloseable {
        public String summary() {
            return "Minecraft " + version + ", 약 " + bytes / (1024 * 1024) + " MB\n오버월드·차원·플레이어 데이터를 교체합니다.\n"
                    + "서버 설정과 플러그인은 유지하며, 교체 전에 백업합니다."
                    + (singlePlayer ? "\n싱글플레이 주인의 인벤토리는 서버 플레이어 데이터로 자동 변환하지 않습니다." : "");
        }

        public void apply(Path directory, PaperBuild target) throws IOException {
            if (target.compareVersion(version) < 0) throw new IOException("현재 Paper(" + target.version() + ")보다 새 월드(" + version + ")는 가져올 수 없습니다");
            if (target.compareVersion("26.1") >= 0 && (Files.exists(payload.resolve("world_nether")) || Files.exists(payload.resolve("world_the_end")))) {
                throw new IOException("옛 Paper/Spigot의 분리 차원은 자동 변환하지 않습니다. Vanilla 형식으로 변환한 사본을 가져오세요.");
            }
            Properties settings = new Properties();
            if (Files.exists(directory.resolve("server.properties"))) {
                try (Reader reader = Files.newBufferedReader(directory.resolve("server.properties"))) { settings.load(reader); }
            }
            if (!settings.getProperty("level-name", "world").equals("world")) throw new IOException("기본 level-name=world인 서버만 지원합니다");
            for (String name : WORLDS) {
                SafeFiles.deleteTree(directory.resolve(name));
                if (Files.exists(payload.resolve(name))) SafeFiles.copyTree(payload.resolve(name), directory.resolve(name));
            }
        }

        @Override public void close() throws IOException { SafeFiles.deleteTree(stagingDirectory); }
    }

    private static void unzip(Path archive, Path target) throws IOException {
        Set<String> entries = new HashSet<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory()) name = name.substring(0, name.length() - 1);
                Path file = SafeFiles.resolve(target, name);
                if (!entries.add(name.toLowerCase(Locale.ROOT)) || entries.size() > SafeFiles.MAX_FILES) throw new IOException("중복 경로 또는 과도한 ZIP 항목");
                if (entry.isDirectory()) { Files.createDirectories(file); continue; }
                Files.createDirectories(file.getParent());
                long size = 0;
                try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        size += read; total += read;
                        if (size >= SafeFiles.MAX_FILE || total > SafeFiles.MAX_TOTAL) throw new IOException("ZIP 해제 크기 제한을 넘었습니다");
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }
}
