package mcd.cli;

import mcd.node.Node;
import mcd.node.Timing;
import mcd.server.PaperBuild;
import mcd.server.PaperDistribution;
import mcd.world.NetworkConfig;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class Main {

    private static final Set<String> FLAGS = Set.of("accept-eula", "offline");

    private static final String USAGE = """
            사용법:
              mcd init --remote <git URL> --dir <폴더> [--paper <버전 또는 버전-빌드>] [--offline] [--tailnet-auth-key <키> [--tailnet-control-url <주소>]]
                  빈 원격 repo에 새 월드를 만든다. --paper가 없으면 기본 빌드를 쓴다. --offline이면 online-mode=false(테스트용).
                  tailnet 키를 주면 모든 노드가 그 tailnet으로 서로 접속한다(친구는 아무것도 설치하지 않음).
                  조정 서버를 비우면 Tailscale을 쓴다. Headscale 같은 다른 조정 서버도 쓸 수 있다.
              mcd play --remote <git URL> --dir <폴더> --node <이름> [--accept-eula]
                  월드에 들어갈 준비를 하고 기다린다. 마인크래프트에서 localhost:25565로 접속하면, 호스트가 있으면
                  그쪽으로 이어지고 없으면 이 PC가 서버가 된다. 아무도 없으면 서버는 스스로 내려간다.
                  --listen <포트>: 마인크래프트가 접속할 이 PC의 포트(기본 25565)
                  --port <포트>: 이 PC가 호스트일 때 Paper가 쓸 포트(기본 25566)
                  --address <호스트:포트>: 다른 사람이 이 PC의 Paper에 접속할 주소(기본 localhost:<port>)
                  --ttl-seconds <초>: 호스트가 응답 없을 때 이어받기까지의 시간(기본 600). 테스트용.
                  --cache <폴더>: Paper jar를 받아둘 곳(기본 ~/.mcd/cache).
                  --handoff-port <포트>: 호스트를 넘길 때 플레이어를 보낼 중계 포트(기본 --listen). 테스트용.
              mcd upgrade-paper --remote <git URL> --dir <폴더> --node <이름> --paper <버전 또는 버전-빌드>
                  월드가 쓰는 Paper(= 마인크래프트 버전)를 올린다. 버전만 주면 그 버전의 최신 안정 빌드를 쓴다.
                  호스트가 있는 동안은 바꿀 수 없고, 옛 버전으로는 되돌릴 수 없다. 모두 클라이언트를 그 버전으로 맞춰야 한다.
              mcd update
                  새 버전이 있으면 받아서 검증해 두고, 이 설치 폴더의 mcd가 모두 끝나면 바꾼다.
            GitHub 토큰은 환경 변수 MCD_GITHUB_TOKEN으로 넘긴다.
            """;

    private Main() {}

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 사용자가 고칠 수 있는 문제(인자 누락, EULA 미동의 등)는 메시지만 보여준다.
            System.err.println("[mcd] " + e.getMessage());
            System.exit(2);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.print(USAGE);
            System.exit(2);
        }
        Map<String, String> opts = parse(args);
        switch (args[0]) {
            case "init" -> InitCommand.run(Path.of(require(opts, "dir")), require(opts, "remote"), !opts.containsKey("offline"),
                    opts.containsKey("tailnet-auth-key")
                            ? Optional.of(new NetworkConfig(opts.getOrDefault("tailnet-control-url", ""), opts.get("tailnet-auth-key")))
                            : Optional.empty(),
                    initialPaperBuild(opts.get("paper")),
                    credentials());
            case "play" -> {
                int paperPort = Integer.parseInt(opts.getOrDefault("port", "25566"));
                new PlayCommand(new Node.Config(
                        require(opts, "remote"),
                        Path.of(require(opts, "dir")),
                        require(opts, "node"),
                        Integer.parseInt(opts.getOrDefault("listen", "25565")),
                        paperPort,
                        opts.getOrDefault("address", "localhost:" + paperPort),
                        opts.containsKey("cache") ? Path.of(opts.get("cache")) : Path.of(System.getProperty("user.home"), ".mcd", "cache"),
                        credentials(),
                        Timing.fromTtl(opts.containsKey("ttl-seconds")
                                ? Duration.ofSeconds(Long.parseLong(opts.get("ttl-seconds")))
                                : Timing.DEFAULT_TTL),
                        Integer.parseInt(opts.getOrDefault("handoff-port", opts.getOrDefault("listen", "25565")))),
                        opts.containsKey("accept-eula")).run();
            }
            case "update" -> UpdateCommand.run(message -> System.out.println("[mcd] " + message));
            case "upgrade-paper" -> UpgradeCommand.run(
                    Path.of(require(opts, "dir")),
                    require(opts, "remote"),
                    require(opts, "node"),
                    require(opts, "paper"),
                    opts.containsKey("cache") ? Path.of(opts.get("cache")) : Path.of(System.getProperty("user.home"), ".mcd", "cache"),
                    credentials(),
                    PaperDistribution.official(),
                    message -> System.out.println("[mcd] " + message));
            default -> {
                System.out.print(USAGE);
                System.exit(2);
            }
        }
    }

    /** init --paper: 없으면 기본 빌드, 버전만 주면 그 버전의 최신 안정 빌드. */
    private static PaperBuild initialPaperBuild(String paper) throws Exception {
        if (paper == null) return PaperDistribution.DEFAULT_BUILD;
        return paper.matches(".*-\\d+$") ? PaperBuild.parse(paper) : PaperDistribution.official().latestStable(paper);
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
