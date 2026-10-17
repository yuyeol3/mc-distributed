package mcd.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 설정에 넣은 GitHub 토큰 확인(예전 방식, collaborator로 초대받은 월드에만 쓴다). */
public final class GitHubClient {

    public static final String API = "https://api.github.com";

    private final String baseUrl;
    private final String token;
    private final HttpClient http = HttpClient.newHttpClient();

    /** baseUrl은 테스트에서 로컬 서버로 바꾸기 위한 것이다. 보통은 {@link #API}. */
    public GitHubClient(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    /** 토큰 주인의 로그인 이름. 토큰이 틀리면 예외. */
    public String login() throws IOException, InterruptedException {
        HttpResponse<String> response = send(request("/user").GET().build());
        expect(response, 200, "토큰을 확인하지 못했습니다");
        return field(response.body(), "login");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28");
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void expect(HttpResponse<String> response, int status, String message) throws IOException {
        if (response.statusCode() != status) {
            String detail = response.body().contains("\"message\"") ? field(response.body(), "message") : response.body();
            throw new IOException(message + " (HTTP " + response.statusCode() + ": " + detail + ")");
        }
    }

    /** 응답에서 최상위 문자열 필드 하나를 꺼낸다. 필요한 필드가 두세 개뿐이라 JSON 라이브러리는 쓰지 않는다. */
    static String field(String json, String name) throws IOException {
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) throw new IOException("응답에 " + name + "이 없습니다");
        return m.group(1).replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
