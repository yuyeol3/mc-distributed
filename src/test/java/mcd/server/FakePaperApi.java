package mcd.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Paper 다운로드 API(fill v3)를 흉내 내는 로컬 서버. 빌드마다 jar 내용, Java 최소 버전, 채널을 정할 수 있다. */
public final class FakePaperApi implements AutoCloseable {

    private record Build(byte[] jar, String advertisedSha256, String channel) {}

    private final HttpServer server;
    private final Map<String, Build> builds = new ConcurrentHashMap<>();
    private final Map<String, Integer> javaMinimum = new ConcurrentHashMap<>();
    public final AtomicInteger requests = new AtomicInteger();

    public FakePaperApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakePaperApi build(String build, byte[] jar) {
        return build(build, jar, sha256(jar), "STABLE");
    }

    /** advertisedSha256을 jar의 실제 해시와 다르게 주면 변조된 다운로드를 흉내 낸다. */
    public FakePaperApi build(String build, byte[] jar, String advertisedSha256, String channel) {
        builds.put(build, new Build(jar, advertisedSha256, channel));
        return this;
    }

    public FakePaperApi javaMinimum(String version, int minimum) {
        javaMinimum.put(version, minimum);
        return this;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String[] p = exchange.getRequestURI().getPath().split("/");
        // /versions/{v}, /versions/{v}/builds/{n|latest}, /objects/{v}-{n}.jar
        if (p.length == 3 && p[1].equals("versions")) {
            respond(exchange, 200, "{\"version\":{\"id\":\"" + p[2] + "\",\"java\":{\"version\":{\"minimum\":"
                    + javaMinimum.getOrDefault(p[2], 21) + "}}}}");
        } else if (p.length == 5 && p[1].equals("versions") && p[3].equals("builds")) {
            String key = p[4].equals("latest") ? latest(p[2]) : p[2] + "-" + p[4];
            Build b = key == null ? null : builds.get(key);
            if (b == null) {
                respond(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            int id = Integer.parseInt(key.substring(key.lastIndexOf('-') + 1));
            respond(exchange, 200, "{\"id\":" + id + ",\"channel\":\"" + b.channel() + "\",\"downloads\":{\"server:default\":{\"name\":\"paper.jar\","
                    + "\"checksums\":{\"sha256\":\"" + b.advertisedSha256() + "\"},\"url\":\"" + url() + "/objects/" + key + ".jar\"}}}");
        } else if (p.length == 3 && p[1].equals("objects")) {
            Build b = builds.get(p[2].replace(".jar", ""));
            exchange.sendResponseHeaders(200, b.jar().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(b.jar());
            }
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private String latest(String version) {
        return builds.keySet().stream().filter(k -> k.startsWith(version + "-"))
                .max((a, b) -> PaperBuild.parse(a).compareTo(PaperBuild.parse(b))).orElse(null);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
