package mcd.e2e;

import mcd.world.NetworkConfig;
import org.eclipse.jgit.api.Git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 도커 실험실. 도커 네트워크 하나에 git 원격(git daemon), tailnet 조정 서버(Headscale), mcd 노드를 띄운다.
 * 컨테이너 하나가 PC 하나라서 모든 노드가 실제 배포처럼 같은 포트(중계 25565, Paper 25566)를 쓰고,
 * 봇은 자기 PC(노드 컨테이너)의 네트워크를 같이 써서 localhost:25565로 접속한다.
 * 노드와 원격 이미지는 ./gradlew labImage labGitImage로 만든다(labTest가 먼저 만든다).
 */
public final class Lab implements AutoCloseable {

    /** 노드가 월드를 받는 원격. 테스트 쪽에서는 {@link #bare()}로 같은 repo를 직접 본다. */
    public static final String REMOTE = "git://git/world.git";
    private static final String NODE_IMAGE = "mcd-lab-node";
    private static final String BOT_IMAGE = "node:22-slim";
    private static final String GIT_IMAGE = "mcd-lab-git";
    private static final String HEADSCALE_IMAGE = "headscale/headscale:v0.29.4";

    private final String id;
    private final Path bare;
    private NetworkConfig network;

    private Lab(String id, Path bare) {
        this.id = id;
        this.bare = bare;
    }

    /** tmp 아래에 원격 repo를 두고 실험실을 띄운다. 월드는 {@link #bare()}에 init해서 넣는다. */
    public static Lab start(Path tmp) throws Exception {
        Path gitRoot = tmp.resolve("git").toAbsolutePath();
        Lab lab = new Lab("mcdlab-" + Long.toHexString(System.nanoTime() & 0xffffffL), gitRoot.resolve("world.git"));
        Git.init().setBare(true).setInitialBranch("main").setDirectory(lab.bare.toFile()).call().close();
        try {
            docker("network", "create", lab.id);
            docker("run", "-d", "--name", lab.id + "-git", "--label", lab.label(), "--network", lab.id,
                    "--network-alias", "git", "-v", gitRoot + ":/srv/git", GIT_IMAGE);
            docker("run", "-d", "--name", lab.id + "-headscale", "--label", lab.label(), "--network", lab.id,
                    "--network-alias", "headscale",
                    "-v", Path.of("lab/headscale.yaml").toAbsolutePath() + ":/etc/headscale/config.yaml:ro",
                    HEADSCALE_IMAGE, "serve");
            lab.network = new NetworkConfig("http://headscale:8080", lab.createAuthKey());
            return lab;
        } catch (Exception e) {
            lab.close();
            throw e;
        }
    }

    public Path bare() {
        return bare;
    }

    /** 월드에 기록할 tailnet 설정. */
    public NetworkConfig network() {
        return network;
    }

    /** PC 하나를 켜고 `mcd play`를 띄운다. 이름이 컨테이너 이름이 된다. */
    public McdNode node(String name) throws IOException {
        return McdNode.start(name, List.of("docker", "run", "-i", "--name", container(name), "--hostname", name.toLowerCase(Locale.ROOT),
                "--label", label(), "--network", id, NODE_IMAGE,
                "play", "--remote", REMOTE, "--dir", "/world", "--node", name, "--listen", "25565", "--port", "25566",
                "--accept-eula", "--cache", "/opt/mcd/cache", "--ttl-seconds", "60", "--handoff-port", "25565"));
    }

    /** node PC에서 플레이어 하나가 localhost:25565로 접속한다. */
    public Bot join(String node, String username, Duration spawnTimeout) throws Exception {
        return Bot.start(List.of("docker", "run", "-i", "--rm", "--label", label(), "--network", "container:" + container(node),
                "-v", Path.of("e2e").toAbsolutePath() + ":/e2e:ro", BOT_IMAGE,
                "node", "/e2e/bot.js", "localhost", "25565", username), spawnTimeout);
    }

    @Override
    public void close() throws IOException, InterruptedException {
        String containers = docker("ps", "-aq", "--filter", "label=" + label());
        if (!containers.isBlank()) {
            List<String> rm = new ArrayList<>(List.of("rm", "-f"));
            rm.addAll(containers.lines().toList());
            docker(rm.toArray(String[]::new));
        }
        try {
            docker("network", "rm", id);
        } catch (IOException ignored) {
            // 만들기 전에 실패한 경우.
        }
    }

    private String createAuthKey() throws Exception {
        String headscale = id + "-headscale";
        // Headscale이 뜰 때까지 사용자 만들기를 다시 시도한다.
        for (int attempt = 0; ; attempt++) {
            try {
                docker("exec", headscale, "headscale", "users", "create", "lab");
                break;
            } catch (IOException e) {
                if (attempt >= 30) throw e;
                Thread.sleep(1000);
            }
        }
        String out = docker("exec", headscale, "headscale", "preauthkeys", "create", "--user", "1",
                "--reusable", "--ephemeral", "--expiration", "1h");
        return out.lines().reduce((first, second) -> second).orElseThrow().trim();
    }

    private String container(String node) {
        return id + "-" + node.toLowerCase(Locale.ROOT);
    }

    private String label() {
        return "mcdlab=" + id;
    }

    private static String docker(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd) + " 실패: " + out);
        return out;
    }
}
