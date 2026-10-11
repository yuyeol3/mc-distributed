package mcd.github;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 GitHub 대신 로컬 HTTP 서버로 요청 형식과 응답 처리를 확인한다. */
class GitHubClientTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/user", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + auth + " " + body);
            if (!"Bearer good".equals(auth)) {
                respond(exchange, 401, "{\"message\":\"Bad credentials\"}");
            } else if (exchange.getRequestURI().getPath().equals("/user/repos")) {
                respond(exchange, 201, "{\"name\":\"world\",\"clone_url\":\"https:\\/\\/github.com\\/me\\/world.git\"}");
            } else {
                respond(exchange, 200, "{\"login\":\"me\",\"id\":1}");
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void readsLoginWithBearerToken() throws Exception {
        assertEquals("me", client("good").login());
        assertEquals("GET /user Bearer good ", requests.getFirst());
    }

    @Test
    void createsPrivateRepoAndReturnsCloneUrl() throws Exception {
        assertEquals("https://github.com/me/world.git", client("good").createPrivateRepo("world"));
        assertEquals("POST /user/repos Bearer good {\"name\":\"world\",\"private\":true,\"auto_init\":false}", requests.getFirst());
    }

    @Test
    void badTokenGivesReadableError() {
        IOException e = assertThrows(IOException.class, () -> client("bad").login());
        assertTrue(e.getMessage().contains("401") && e.getMessage().contains("Bad credentials"), e.getMessage());
    }

    private GitHubClient client(String token) {
        return new GitHubClient("http://127.0.0.1:" + server.getAddress().getPort(), token);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
