package mcd.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 이 프로그램이 쓰는 Paper 빌드. 모든 호스트가 같은 빌드를 써야 하므로(26.1+ 월드는 다운그레이드 불가)
 * 빌드를 하나로 고정하고 월드 repo에도 기록한다.
 */
public final class PaperDistribution {

    public static final String BUILD = "26.1.2-74";
    private static final String URL =
            "https://fill-data.papermc.io/v1/objects/1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7/paper-26.1.2-74.jar";
    private static final String SHA256 = "1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7";

    private PaperDistribution() {}

    /** cacheDir에 jar가 없으면 내려받고, 해시를 확인한 경로를 돌려준다. */
    public static Path ensureDownloaded(Path cacheDir) throws IOException, InterruptedException {
        Path jar = cacheDir.resolve("paper-" + BUILD + ".jar");
        if (Files.exists(jar) && sha256(jar).equals(SHA256)) {
            return jar;
        }
        Files.createDirectories(cacheDir);
        Path partial = cacheDir.resolve(jar.getFileName() + ".part");
        try (HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(URL)).build(), HttpResponse.BodyHandlers.ofFile(partial));
            if (response.statusCode() != 200) {
                throw new IOException("Paper 다운로드 실패: HTTP " + response.statusCode());
            }
        }
        String actual = sha256(partial);
        if (!actual.equals(SHA256)) {
            Files.delete(partial);
            throw new IOException("Paper jar 해시가 다르다: " + actual);
        }
        return Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
