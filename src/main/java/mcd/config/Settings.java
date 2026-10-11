package mcd.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * GUI가 첫 실행 마법사에서 받은 설정. `~/.mcd/settings.properties`에 저장한다.
 * 토큰도 평문으로 저장된다(알려진 한계: OS 자격 증명 저장소는 나중에 다룬다).
 */
public record Settings(String nodeName, String remote, Path worldDir, String token, String address) {

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".mcd", "settings.properties");
    }

    public static Optional<Settings> load(Path file) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        for (String key : new String[] {"node", "remote", "worldDir", "address"}) {
            // 빈 worldDir은 Path.of("")가 되어 현재 폴더를 월드로 착각하게 만든다.
            if (p.getProperty(key, "").isBlank()) {
                throw new IllegalStateException(file + "에 " + key + " 값이 없다. 파일을 지우고 다시 설정한다.");
            }
        }
        return Optional.of(new Settings(
                p.getProperty("node"),
                p.getProperty("remote"),
                Path.of(p.getProperty("worldDir")),
                p.getProperty("token", ""),
                p.getProperty("address")));
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("node", nodeName);
        p.setProperty("remote", remote);
        p.setProperty("worldDir", worldDir.toString());
        p.setProperty("token", token);
        p.setProperty("address", address);
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(out, "mcd settings");
        }
    }
}
