package mcd.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * GUI 설정. 모든 월드에 공통인 이름, GitHub 토큰, 서버 주소와 월드 목록, 지금 고른 월드(current, 원격 주소)를 둔다.
 * `~/.mcd/settings.properties`에 저장한다. 토큰도 평문으로 저장된다(알려진 한계: OS 자격 증명 저장소는 나중에 다룬다).
 */
public record Settings(String nodeName, String token, String address, List<World> worlds, String current) {

    /** 월드 하나: 원격 repo와 그 작업 트리(Paper 서버 폴더). */
    public record World(String remote, Path dir) {
        public String name() {
            return dir.getFileName().toString();
        }
    }

    public Settings {
        worlds = List.copyOf(worlds);
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".mcd", "settings.properties");
    }

    /** 고른 월드. 고른 월드가 목록에 없으면 첫 월드, 목록이 비었으면 없음. */
    public Optional<World> currentWorld() {
        return worlds.stream().filter(w -> w.remote().equals(current)).findFirst().or(() -> worlds.stream().findFirst());
    }

    /** 월드를 목록에 넣고(같은 원격이면 바꿈) 고른다. */
    public Settings withWorld(World world) {
        List<World> next = new ArrayList<>(worlds);
        next.removeIf(w -> w.remote().equals(world.remote()));
        next.add(world);
        return new Settings(nodeName, token, address, next, world.remote());
    }

    public Settings withoutWorld(String remote) {
        List<World> next = new ArrayList<>(worlds);
        next.removeIf(w -> w.remote().equals(remote));
        String nextCurrent = remote.equals(current) ? next.stream().findFirst().map(World::remote).orElse("") : current;
        return new Settings(nodeName, token, address, next, nextCurrent);
    }

    public Settings withCurrent(String remote) {
        return new Settings(nodeName, token, address, worlds, remote);
    }

    public Settings withAccount(String nodeName, String token, String address) {
        return new Settings(nodeName, token, address, worlds, current);
    }

    public static Optional<Settings> load(Path file) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        require(file, p, "node");
        require(file, p, "address");
        List<World> worlds = new ArrayList<>();
        if (p.containsKey("remote")) {
            // v0.3.1까지의 형식: 월드 하나(remote, worldDir).
            worlds.add(world(file, p, "remote", "worldDir"));
        }
        for (int i = 0; p.containsKey("world." + i + ".remote"); i++) {
            worlds.add(world(file, p, "world." + i + ".remote", "world." + i + ".dir"));
        }
        String current = p.getProperty("current", p.getProperty("remote", ""));
        return Optional.of(new Settings(p.getProperty("node"), p.getProperty("token", ""), p.getProperty("address"), worlds, current));
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("node", nodeName);
        p.setProperty("token", token);
        p.setProperty("address", address);
        for (int i = 0; i < worlds.size(); i++) {
            p.setProperty("world." + i + ".remote", worlds.get(i).remote());
            p.setProperty("world." + i + ".dir", worlds.get(i).dir().toString());
        }
        p.setProperty("current", current);
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(out, "mcd settings");
        }
    }

    private static World world(Path file, Properties p, String remoteKey, String dirKey) {
        require(file, p, remoteKey);
        // 빈 폴더 값은 Path.of("")가 되어 현재 폴더를 월드로 착각하게 만든다.
        require(file, p, dirKey);
        return new World(p.getProperty(remoteKey), Path.of(p.getProperty(dirKey)));
    }

    private static void require(Path file, Properties p, String key) {
        if (p.getProperty(key, "").isBlank()) {
            throw new IllegalStateException(file + "에 " + key + " 값이 없다. 파일을 지우고 다시 설정한다.");
        }
    }
}
