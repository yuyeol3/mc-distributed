package mcd.e2e;

import mcd.lock.LockState;
import mcd.world.NetworkConfig;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 도커 실험실. 도커 네트워크 하나에 git 원격(git daemon), tailnet 조정 서버(Headscale), DERP 중계 서버(derper),
 * mcd 노드를 띄운다. 컨테이너 하나가 PC 하나라서 모든 노드가 실제 배포처럼 같은 포트(중계 25565, Paper 25566)를
 * 쓰고, 봇은 자기 PC(노드 컨테이너)의 네트워크를 같이 써서 localhost:25565로 접속한다. tailnet은 실험실 안에서만
 * 돈다(공개 DERP를 쓰지 않는다). 이미지는 labTest가 먼저 만든다.
 *
 * <p>노드 컨테이너는 NET_ADMIN 권한이 있어 시나리오가 iptables와 tc로 네트워크 장애를 건다. 고정 주소(원격, 조정
 * 서버, DERP)는 서브넷 앞쪽에, 노드는 뒤쪽 절반({@code .128/25})에서 주소를 받는다.
 */
public final class Lab implements AutoCloseable {

    /** 노드가 월드를 받는 원격. 테스트 쪽에서는 {@link #bare()}로 같은 repo를 직접 본다. */
    public static final String REMOTE = "git://git/world.git";
    public static final String NODE_IMAGE = "mcd-lab-node";
    private static final String BOT_IMAGE = "mcd-lab-bot";
    private static final String GIT_IMAGE = "mcd-lab-git";
    private static final String DERPER_IMAGE = "mcd-lab-derper";
    private static final String HEADSCALE_IMAGE = "headscale/headscale:v0.29.4";

    private final String id;
    private final Path bare;
    private final String prefix;
    private NetworkConfig network;

    private Lab(String id, Path bare, String prefix) {
        this.id = id;
        this.bare = bare;
        this.prefix = prefix;
    }

    /** tmp 아래에 원격 repo를 두고 실험실을 띄운다. 월드는 {@link #bare()}에 init해서 넣는다. */
    public static Lab start(Path tmp) throws Exception {
        Path gitRoot = tmp.resolve("git").toAbsolutePath();
        String prefix = "10.123." + new Random().nextInt(256) + ".";
        Lab lab = new Lab("mcdlab-" + Long.toHexString(System.nanoTime() & 0xffffffL), gitRoot.resolve("world.git"), prefix);
        Git.init().setBare(true).setInitialBranch("main").setDirectory(lab.bare.toFile()).call().close();
        Path derpMap = tmp.resolve("derp.yaml").toAbsolutePath();
        // 실험실 derper 하나만 둔다. derper가 IP 주소용 self-signed 인증서를 직접 만들므로 검증은 끈다.
        Files.writeString(derpMap, """
                regions:
                  900:
                    regionid: 900
                    regioncode: lab
                    regionname: mcd lab
                    nodes:
                      - name: 900a
                        regionid: 900
                        hostname: %1$s
                        ipv4: %1$s
                        insecurefortests: true
                """.formatted(lab.derperIp()));
        try {
            docker("network", "create", "--subnet", prefix + "0/24", "--ip-range", prefix + "128/25", lab.id);
            docker("run", "-d", "--name", lab.id + "-git", "--label", lab.label(), "--network", lab.id, "--ip", lab.gitIp(),
                    "--network-alias", "git", "-v", gitRoot + ":/srv/git", GIT_IMAGE);
            docker("run", "-d", "--name", lab.id + "-derper", "--label", lab.label(), "--network", lab.id, "--ip", lab.derperIp(),
                    DERPER_IMAGE, "--certmode", "manual", "--certdir", "/tmp/certs", "--hostname", lab.derperIp(), "-a", ":443");
            docker("run", "-d", "--name", lab.id + "-headscale", "--label", lab.label(), "--network", lab.id, "--ip", prefix + "12",
                    "--network-alias", "headscale",
                    "-v", Path.of("lab/headscale.yaml").toAbsolutePath() + ":/etc/headscale/config.yaml:ro",
                    "-v", derpMap + ":/etc/headscale/derp.yaml:ro",
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

    /** 실험실 서브넷(예: 10.123.7.0/24). */
    public String subnet() {
        return prefix + "0/24";
    }

    public String gitIp() {
        return prefix + "10";
    }

    public String derperIp() {
        return prefix + "11";
    }

    /** PC 하나를 켜고 `mcd play`를 띄운다. 이름이 컨테이너 이름이 된다. */
    public McdNode node(String name) throws IOException {
        return node(name, NODE_IMAGE, "");
    }

    /** setup은 mcd보다 먼저 실행할 셸 명령(iptables, tc 등 네트워크 조건). image로 다른 버전의 mcd를 띄울 수 있다. */
    public McdNode node(String name, String image, String setup) throws IOException {
        return McdNode.start(name, List.of("docker", "run", "-i", "--name", container(name), "--hostname", name.toLowerCase(Locale.ROOT),
                "--label", label(), "--network", id, "--cap-add", "NET_ADMIN", "-e", "LAB_SETUP=" + setup, image,
                "play", "--remote", REMOTE, "--dir", "/world", "--node", name, "--listen", "25565", "--port", "25566",
                "--accept-eula", "--cache", "/opt/mcd/cache", "--ttl-seconds", "60", "--handoff-port", "25565"));
    }

    /** node PC에서 플레이어 하나가 localhost:25565로 접속한다. */
    public Bot join(String node, String username, Duration spawnTimeout) throws Exception {
        return Bot.start(List.of("docker", "run", "-i", "--rm", "--label", label(), "--network", "container:" + container(node),
                BOT_IMAGE, "localhost", "25565", username), spawnTimeout);
    }

    /** node PC에서 셸 명령을 실행한다(실행 중에 네트워크 조건을 바꿀 때). */
    public void sh(String node, String command) throws IOException, InterruptedException {
        docker("exec", container(node), "sh", "-c", command);
    }

    /** node PC에서 Paper 프로세스가 돌고 있는지. */
    public boolean paperRunning(String node) throws InterruptedException {
        try {
            // 패턴을 [p]aper로 써서 이 명령을 실행하는 셸 자신과는 맞지 않게 한다.
            sh(node, "pgrep -f '[p]aper-26' > /dev/null");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** PC 전체(mcd, Paper, mcd-net)를 얼린다. 전원은 켜져 있지만 아무 응답도 하지 않는 상태. */
    public void pause(String node) throws IOException, InterruptedException {
        docker("pause", container(node));
    }

    public void unpause(String node) throws IOException, InterruptedException {
        docker("unpause", container(node));
    }

    /**
     * 원격의 main을 처음부터 따라가며 락 epoch가 줄지 않았는지(옛 writer의 push가 섞이지 않았는지), 마지막 락이
     * owner/epoch로 반납됐는지 본다.
     */
    public void assertConsistentHistory(String owner, long epoch) throws Exception {
        try (Git git = Git.open(bare.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            List<LockState> locks = new ArrayList<>();
            walk.setFirstParent(true);
            walk.markStart(walk.parseCommit(repo.resolve("refs/heads/main")));
            for (RevCommit commit : walk) {
                try (TreeWalk tree = TreeWalk.forPath(repo, LockState.FILE_NAME, commit.getTree())) {
                    if (tree != null) locks.addFirst(LockState.parse(repo.open(tree.getObjectId(0)).getBytes()));
                }
            }
            for (int i = 1; i < locks.size(); i++) {
                assertTrue(locks.get(i).epoch() >= locks.get(i - 1).epoch(), "epoch가 줄었다: " + locks);
            }
            LockState last = locks.getLast();
            assertEquals(owner, last.owner());
            assertEquals(epoch, last.epoch());
            assertEquals(LockState.State.FREE, last.state());
        }
    }

    /** 소유권을 잃은 PC가 공유하지 못한 변경을 보관한 브랜치들(refs/heads/recovery/<노드>-<epoch>-<커밋>). */
    public List<String> recoveryBranches() throws IOException {
        try (Git git = Git.open(bare.toFile())) {
            return git.getRepository().getRefDatabase().getRefsByPrefix("refs/heads/recovery/").stream().map(Ref::getName).toList();
        }
    }

    @Override
    public void close() throws IOException, InterruptedException {
        printHistory();
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

    /** 실패를 진단할 수 있도록 원격의 최근 이력(누가 무엇을 올렸는지)을 남긴다. */
    private void printHistory() {
        try (Git git = Git.open(bare.toFile())) {
            System.out.println("[lab] 원격 main 최근 이력:");
            for (RevCommit c : git.log().setMaxCount(20).call()) System.out.println("[lab]   " + c.getShortMessage());
            System.out.println("[lab] 브랜치: " + git.getRepository().getRefDatabase().getRefsByPrefix("refs/heads/").stream().map(Ref::getName).toList());
        } catch (Exception e) {
            System.out.println("[lab] 원격 이력을 읽지 못했다: " + e);
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
