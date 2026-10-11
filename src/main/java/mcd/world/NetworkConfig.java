package mcd.world;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * 월드 repo의 {@value #FILE_NAME}. 이 월드의 노드들이 붙을 tailnet(조정 서버 주소와 인증 키)이다.
 * 월드 주인이 한 번 넣으면 친구들은 clone할 때 같이 받으므로 따로 설정하지 않는다. 키가 private repo의
 * collaborator에게 보인다는 점은 감수한다. 파일이 없으면 tailnet 없이 로컬 주소만 쓴다.
 */
public record NetworkConfig(String controlUrl, String authKey) {

    public static final String FILE_NAME = "mcd-network.properties";

    public static Optional<NetworkConfig> read(Path worldDir) throws IOException {
        Path file = worldDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return Optional.empty();
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        String key = p.getProperty("authKey", "").trim();
        if (key.isEmpty()) {
            throw new IllegalStateException(file + "에 authKey가 없다");
        }
        return Optional.of(new NetworkConfig(p.getProperty("controlUrl", "").trim(), key));
    }

    /** Properties.store는 시각 주석을 넣어 내용이 매번 달라지므로 직접 쓴다. */
    public void write(Path worldDir) throws IOException {
        Files.writeString(worldDir.resolve(FILE_NAME), "controlUrl=" + controlUrl + "\nauthKey=" + authKey + "\n");
    }
}
