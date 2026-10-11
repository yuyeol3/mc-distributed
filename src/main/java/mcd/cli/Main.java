package mcd.cli;

import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class Main {

    private static final Set<String> FLAGS = Set.of("accept-eula", "offline");

    private static final String USAGE = """
            사용법:
              mcd init --remote <git URL> --dir <폴더> [--offline]
                  빈 원격 repo에 새 월드를 만든다. --offline이면 online-mode=false(테스트용).
              mcd play --remote <git URL> --dir <폴더> --node <이름> [--port 25565] [--address 호스트:포트] [--accept-eula]
                  월드에 들어간다. 호스트가 없으면 이 PC가 서버가 되고, 있으면 접속 주소를 안내한다.
                  --ttl-seconds <초>: 호스트가 응답 없을 때 이어받기까지의 시간(기본 600). 테스트용.
                  --cache <폴더>: Paper jar를 받아둘 곳(기본 ~/.mcd/cache).
            GitHub 토큰은 환경 변수 MCD_GITHUB_TOKEN으로 넘긴다.
            """;

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.print(USAGE);
            System.exit(2);
        }
        Map<String, String> opts = parse(args);
        switch (args[0]) {
            case "init" -> InitCommand.run(Path.of(require(opts, "dir")), require(opts, "remote"), !opts.containsKey("offline"), credentials());
            case "play" -> {
                int port = Integer.parseInt(opts.getOrDefault("port", "25565"));
                new PlayCommand(
                        require(opts, "remote"),
                        Path.of(require(opts, "dir")),
                        require(opts, "node"),
                        port,
                        opts.getOrDefault("address", "localhost:" + port),
                        opts.containsKey("accept-eula"),
                        opts.containsKey("cache") ? Path.of(opts.get("cache")) : Path.of(System.getProperty("user.home"), ".mcd", "cache"),
                        credentials(),
                        PlayCommand.Timing.fromTtl(opts.containsKey("ttl-seconds")
                                ? Duration.ofSeconds(Long.parseLong(opts.get("ttl-seconds")))
                                : PlayCommand.Timing.DEFAULT_TTL)).run();
            }
            default -> {
                System.out.print(USAGE);
                System.exit(2);
            }
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("알 수 없는 인자: " + args[i]);
            }
            String key = args[i].substring(2);
            if (FLAGS.contains(key)) {
                opts.put(key, "true");
            } else if (i + 1 < args.length) {
                opts.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("--" + key + "에 값이 없다");
            }
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null) {
            throw new IllegalArgumentException("--" + key + "가 필요하다\n" + USAGE);
        }
        return value;
    }

    private static CredentialsProvider credentials() {
        String token = System.getenv("MCD_GITHUB_TOKEN");
        return token == null ? null : new UsernamePasswordCredentialsProvider("x-access-token", token);
    }
}
