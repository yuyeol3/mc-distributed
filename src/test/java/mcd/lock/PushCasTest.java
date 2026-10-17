package mcd.lock;

import mcd.world.DeployKey;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 실험 E2: force 없는 push가 동시 경쟁에서 정확히 한 명만 성공시키는지 확인한다.
 * 소유권 프로토콜 전체가 이 성질에 기대므로 계속 테스트로 고정해둔다.
 */
class PushCasTest {

    private static final String MAIN = "refs/heads/main";

    @TempDir
    Path tmp;

    @Test
    void exactlyOneConcurrentPushWinsOnLocalBareRepo() throws Exception {
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String uri = bare.toUri().toString();
        seed(uri, null);

        for (int round = 0; round < 100; round++) {
            raceRound(uri, null, 4, tmp.resolve("r" + round));
        }
    }

    /**
     * GitHub에서 같은 성질을 확인한다. 빈 private repo를 하나 만들고 아래처럼 실행한다.
     * MCD_GITHUB_REMOTE=https://github.com/<user>/<repo>.git MCD_GITHUB_TOKEN=<PAT> ./gradlew test --tests '*PushCasTest*'
     * GitHub 권장 한도(repo당 분당 push 6회 이하)를 넘지 않도록 회차 사이에 30초를 쉰다.
     */
    @Test
    @Tag("github")
    @EnabledIfEnvironmentVariable(named = "MCD_GITHUB_REMOTE", matches = ".+")
    void exactlyOneConcurrentPushWinsOnGitHub() throws Exception {
        String token = System.getenv("MCD_GITHUB_TOKEN");
        githubRounds(System.getenv("MCD_GITHUB_REMOTE"),
                t -> t.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token)));
    }

    /**
     * 실험 E9: deploy key(SSH, 443 포트)로도 같은 성질인지 확인한다. 빈 private repo에 쓰기 권한 deploy key를 등록하고 실행한다.
     * MCD_SSH_REMOTE=ssh://git@ssh.github.com:443/<user>/<repo>.git MCD_DEPLOY_KEY=<DeployKey.encode()> ./gradlew test --tests '*PushCasTest*'
     */
    @Test
    @Tag("github")
    @EnabledIfEnvironmentVariable(named = "MCD_SSH_REMOTE", matches = ".+")
    void exactlyOneConcurrentPushWinsOnGitHubOverSsh() throws Exception {
        githubRounds(System.getenv("MCD_SSH_REMOTE"), DeployKey.decode(System.getenv("MCD_DEPLOY_KEY")).transport());
    }

    private void githubRounds(String uri, TransportConfigCallback auth) throws Exception {
        seed(uri, auth);
        for (int round = 0; round < 20; round++) {
            raceRound(uri, auth, 3, tmp.resolve("r" + round));
            Thread.sleep(30_000);
        }
    }

    /** main에 첫 커밋을 만든다. 이미 있으면 그대로 쓴다. */
    private void seed(String uri, TransportConfigCallback auth) throws Exception {
        Path dir = tmp.resolve("seed");
        try (Git git = Git.init().setInitialBranch("main").setDirectory(dir.toFile()).call()) {
            git.remoteAdd().setName("origin").setUri(new URIish(uri)).call();
            git.fetch().setRemote("origin").setTransportConfigCallback(auth).call();
            if (git.getRepository().findRef("refs/remotes/origin/main") != null) {
                return;
            }
            Files.writeString(dir.resolve("lock.json"), "{}");
            git.add().addFilepattern("lock.json").call();
            git.commit().setMessage("seed").setSign(false).call();
            git.push().setRemote("origin").add("main").setTransportConfigCallback(auth).call();
        }
    }

    /** 클라이언트 n개가 같은 부모에서 서로 다른 커밋을 만들고 동시에 push한다. */
    private void raceRound(String uri, TransportConfigCallback auth, int n, Path roundDir) throws Exception {
        List<Git> clients = new ArrayList<>();
        List<RevCommit> commits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Path dir = roundDir.resolve("c" + i);
            Git git = Git.cloneRepository().setURI(uri).setDirectory(dir.toFile()).setTransportConfigCallback(auth).call();
            Files.writeString(dir.resolve("lock.json"), "{\"owner\":\"c" + i + "\"}");
            git.add().addFilepattern("lock.json").call();
            commits.add(git.commit().setMessage("acquire c" + i).setSign(false).call());
            clients.add(git);
        }

        CyclicBarrier start = new CyclicBarrier(n);
        List<Future<Outcome>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (Git git : clients) {
                futures.add(pool.submit(() -> {
                    start.await();
                    long t0 = System.nanoTime();
                    Outcome o = push(git, auth);
                    return new Outcome(o.ok, o.detail, (System.nanoTime() - t0) / 1_000_000);
                }));
            }
        }

        List<Integer> winners = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Outcome o = futures.get(i).get();
            System.out.printf("%s c%d ok=%s %dms %s%n", roundDir.getFileName(), i, o.ok, o.millis, o.detail);
            if (o.ok) winners.add(i);
        }
        assertEquals(1, winners.size(), "push에 성공한 클라이언트는 정확히 하나여야 한다: " + winners);

        ObjectId remoteMain = remoteMain(clients.getFirst(), auth);
        assertEquals(commits.get(winners.getFirst()).getId(), remoteMain, "원격 main은 승자의 커밋이어야 한다");
        clients.forEach(Git::close);
    }

    private Outcome push(Git git, TransportConfigCallback auth) {
        try {
            Iterable<PushResult> results = git.push().setRemote("origin").add("main").setTransportConfigCallback(auth).call();
            RemoteRefUpdate update = results.iterator().next().getRemoteUpdate(MAIN);
            return new Outcome(update.getStatus() == RemoteRefUpdate.Status.OK,
                    update.getStatus() + (update.getMessage() == null ? "" : " " + update.getMessage()), 0);
        } catch (Exception e) {
            return new Outcome(false, e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
        }
    }

    private ObjectId remoteMain(Git git, TransportConfigCallback auth) throws Exception {
        for (Ref ref : git.lsRemote().setRemote("origin").setTransportConfigCallback(auth).call()) {
            if (ref.getName().equals(MAIN)) return ref.getObjectId();
        }
        throw new AssertionError("원격에 main이 없다");
    }

    private record Outcome(boolean ok, String detail, long millis) {}
}
