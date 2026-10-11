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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paper 빌드를 Paper 다운로드 API(fill v3)에서 받아 캐시한다. 어떤 빌드를 쓸지는 앱이 아니라 월드가 정한다
 * (월드 repo의 mcd-paper-build.txt). 한 월드의 모든 호스트가 같은 빌드를 써야 하기 때문이다(26.1+ 월드는
 * 다운그레이드 불가).
 *
 * <p>jar는 API가 알려주는 SHA-256으로 검증하고, 검증한 해시를 jar 옆(.sha256)에 남겨 두 번째부터는 네트워크 없이
 * 확인한다.
 */
public final class PaperDistribution {

    public static final String API = "https://fill.papermc.io/v3/projects/paper";

    /** 새 월드가 쓰는 빌드. 이 앱으로 E2E까지 확인한 빌드다. */
    public static final PaperBuild DEFAULT_BUILD = PaperBuild.parse("26.1.2-74");

    /** 호스트 이관에 쓰는 transfer와 accepts-transfers가 1.20.5부터 있다. */
    static final String MIN_VERSION = "1.20.5";

    /** 콘솔 명령과 로그 문구(저장, 기동 완료, 플레이어 수)를 E2E로 확인한 버전 범위. */
    static final String TESTED_VERSION_PREFIX = "26.1";

    private static final Pattern SHA256 = Pattern.compile("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"");
    private static final Pattern URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JAVA_MINIMUM = Pattern.compile("\"minimum\"\\s*:\\s*(\\d+)");
    private static final Pattern BUILD_ID = Pattern.compile("^\\s*\\{\\s*\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern CHANNEL = Pattern.compile("\"channel\"\\s*:\\s*\"([A-Z]+)\"");

    private final String api;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    /** api는 테스트에서 로컬 서버로 바꾸기 위한 것이다. 보통은 {@link #API}. */
    public PaperDistribution(String api) {
        this.api = api;
    }

    public static PaperDistribution official() {
        return new PaperDistribution(API);
    }

    /**
     * 이 앱이 이 빌드로 호스트를 할 수 있는지 확인한다(네트워크 없이). 너무 옛 버전이면 거부하고,
     * E2E로 확인하지 않은 버전이면 경고만 한다.
     */
    public static void checkSupported(PaperBuild build, Consumer<String> warn) {
        if (build.compareVersion(MIN_VERSION) < 0) {
            throw new IllegalStateException("Paper " + build + "는 너무 옛 버전이다. mcd는 " + MIN_VERSION + " 이상이 필요하다(transfer).");
        }
        if (!build.version().equals(TESTED_VERSION_PREFIX) && !build.version().startsWith(TESTED_VERSION_PREFIX + ".")) {
            warn.accept("Paper " + build + "는 이 버전의 mcd로 시험하지 않은 버전이다. 문제가 생기면 mcd를 업데이트한다.");
        }
    }

    /** cacheDir에 그 빌드의 jar가 없으면 받아서 검증하고, 경로를 돌려준다. */
    public Path ensureDownloaded(Path cacheDir, PaperBuild build) throws IOException, InterruptedException {
        Path jar = cacheDir.resolve("paper-" + build + ".jar");
        Path recordedHash = cacheDir.resolve(jar.getFileName() + ".sha256");
        if (Files.exists(jar)) {
            String expected = Files.exists(recordedHash) ? Files.readString(recordedHash).trim() : metadata(build).sha256();
            if (sha256(jar).equals(expected)) {
                Files.writeString(recordedHash, expected);
                return jar;
            }
        }
        checkJava(build);
        Metadata meta = metadata(build);
        Files.createDirectories(cacheDir);
        Path partial = cacheDir.resolve(jar.getFileName() + ".part");
        HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(meta.url())).build(), HttpResponse.BodyHandlers.ofFile(partial));
        if (response.statusCode() != 200) {
            throw new IOException("Paper " + build + "를 받지 못했다: HTTP " + response.statusCode());
        }
        String actual = sha256(partial);
        if (!actual.equals(meta.sha256())) {
            Files.delete(partial);
            throw new IOException("Paper " + build + " jar의 해시가 다르다: " + actual);
        }
        Files.move(partial, jar, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(recordedHash, meta.sha256());
        return jar;
    }

    /** 그 버전의 가장 새 빌드. 안정(STABLE) 빌드가 아니면 거부한다. */
    public PaperBuild latestStable(String version) throws IOException, InterruptedException {
        String body = get("/versions/" + version + "/builds/latest");
        Matcher id = BUILD_ID.matcher(body);
        Matcher channel = CHANNEL.matcher(body);
        if (!id.find() || !channel.find()) {
            throw new IOException("Paper " + version + "의 최신 빌드를 해석하지 못했다");
        }
        if (!channel.group(1).equals("STABLE")) {
            throw new IllegalStateException("Paper " + version + "의 최신 빌드는 안정 빌드가 아니다(" + channel.group(1) + "). 빌드 번호를 직접 지정한다.");
        }
        return new PaperBuild(version, Integer.parseInt(id.group(1)));
    }

    /** 그 버전의 Paper가 요구하는 Java가 이 앱의 런타임보다 새것이면 거부한다. Paper도 이 런타임으로 돈다. */
    private void checkJava(PaperBuild build) throws IOException, InterruptedException {
        Matcher m = JAVA_MINIMUM.matcher(get("/versions/" + build.version()));
        if (m.find() && Integer.parseInt(m.group(1)) > Runtime.version().feature()) {
            throw new IllegalStateException("Paper " + build + "는 Java " + m.group(1) + " 이상이 필요한데 이 mcd에는 Java "
                    + Runtime.version().feature() + "가 들어 있다. mcd를 업데이트한다.");
        }
    }

    private record Metadata(String url, String sha256) {}

    private Metadata metadata(PaperBuild build) throws IOException, InterruptedException {
        String body = get("/versions/" + build.version() + "/builds/" + build.build());
        // 응답에는 server:default 다운로드 하나가 있다. 필요한 값이 둘뿐이라 JSON 라이브러리는 쓰지 않는다.
        int start = body.indexOf("\"server:default\"");
        String section = start < 0 ? body : body.substring(start);
        Matcher url = URL.matcher(section);
        Matcher sha = SHA256.matcher(section);
        if (!url.find() || !sha.find()) {
            throw new IOException("Paper " + build + "의 다운로드 정보를 해석하지 못했다");
        }
        return new Metadata(url.group(1), sha.group(1));
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(api + path)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new IllegalStateException("Paper API에 없는 버전이나 빌드다: " + path);
        }
        if (response.statusCode() != 200) {
            throw new IOException("Paper API 오류: HTTP " + response.statusCode() + " " + path);
        }
        return response.body();
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
