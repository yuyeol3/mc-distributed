package mcd.server;

import mcd.e2e.Bot;
import mcd.lock.HostLock;
import mcd.lock.HostLock.PushOutcome;
import mcd.world.WorldTemplate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실험 E3: 플레이 중에 save-off + save-all flush 상태로 커밋한 스냅샷이 일관되고 기동 가능한지 확인한다.
 * 실행: ./gradlew paperTest (nodes/_cache에 Paper jar가 있어야 함)
 */
@Tag("paper")
class LiveSnapshotTest {

    private static final Path PAPER_JAR = Path.of("nodes/_cache/paper-26.1.2-74.jar");
    private static final Duration START_TIMEOUT = Duration.ofMinutes(3);
    private static final int ROUNDS = 20;

    @TempDir
    Path tmp;

    @Test
    void snapshotsTakenWhilePlayingAreConsistentAndBootable() throws Exception {
        assumeTrue(Files.exists(PAPER_JAR), "Paper jar가 없다: " + PAPER_JAR);
        Path bare = tmp.resolve("remote.git");
        seedRemote(bare);

        Path hostDir = tmp.resolve("A");
        List<ObjectId> snapshots = new ArrayList<>();
        List<String> report = new ArrayList<>();
        List<List<String>> rounds = new ArrayList<>();
        try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(hostDir.toFile()).call()) {
            WorldTemplate.acceptEula(hostDir);
            HostLock lock = new HostLock(git, "A", InstantSource.system(), Duration.ofMinutes(10), Duration.ofMinutes(2), null);
            assertTrue(lock.tryAcquire("localhost:25601").acquired());

            try (PaperServer server = PaperServer.start(hostDir, PAPER_JAR, 25601, START_TIMEOUT, null);
                 Bot bot = Bot.join("e3bot", 25601)) {
                server.command("gamemode creative e3bot");
                Files.readAllLines(hostDir.resolve("bukkit.yml")).stream()
                        .filter(l -> l.contains("autosave")).forEach(l -> report.add("bukkit.yml after boot: " + l.trim()));

                for (int i = 0; i < ROUNDS; i++) {
                    int x = (i + 1) * 400;
                    server.command("tp e3bot " + x + " 150 0");
                    Thread.sleep(4000);
                    server.command("fill " + (x - 3) + " 140 -3 " + (x + 3) + " 146 3 minecraft:glass");
                    Thread.sleep(1000);

                    long t0 = System.nanoTime();
                    server.pauseSavingAndFlush();
                    long t1 = System.nanoTime();
                    Map<String, String> before = checksums(hostDir.resolve("world"));
                    // 저장이 멈춘 동안 플레이어를 멀리 보내 청크 언로드를 일으킨다. 이때 파일이 써지면 스냅샷이 깨질 수 있다.
                    server.command("tp e3bot " + (x + 200) + " 150 0");
                    long t2 = System.nanoTime();
                    lock.commitWorld("e3 snapshot " + i);
                    long t3 = System.nanoTime();
                    Thread.sleep(5000);
                    Map<String, String> after = checksums(hostDir.resolve("world"));
                    server.resumeSaving();
                    long t4 = System.nanoTime();
                    assertEquals(PushOutcome.OK, lock.pushCommitted());
                    long t5 = System.nanoTime();

                    snapshots.add(git.getRepository().resolve("HEAD"));
                    List<String> changed = changedFiles(before, after);
                    rounds.add(changed);
                    report.add("round %d: flush %dms, commit %dms, push %dms, world files %d, changed during pause %s".formatted(
                            i, ms(t1 - t0), ms(t3 - t2), ms(t5 - t4), after.size(), changed));
                }
                bot.quit();
                server.stop(Duration.ofMinutes(2));
            }
            assertEquals(PushOutcome.OK, lock.release());
        }
        report.forEach(System.out::println);

        List<String> bootProblems = bootEachSnapshot(bare, snapshots);
        bootProblems.forEach(System.out::println);

        assertTrue(rounds.stream().allMatch(List::isEmpty), "저장을 멈춘 동안 월드 파일이 바뀌면 안 된다");
        assertEquals(List.of(), bootProblems, "모든 스냅샷이 에러 없이 기동돼야 한다");
    }

    /** 스냅샷마다 작업 트리를 되돌리고 Paper를 기동한다. 무시 파일(libraries 등)은 남겨서 재사용한다. */
    private List<String> bootEachSnapshot(Path bare, List<ObjectId> snapshots) throws Exception {
        List<String> problems = new ArrayList<>();
        Path dir = tmp.resolve("verify");
        try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(dir.toFile()).call()) {
            WorldTemplate.acceptEula(dir);
            for (int i = 0; i < snapshots.size(); i++) {
                git.reset().setMode(ResetType.HARD).setRef(snapshots.get(i).name()).call();
                git.clean().setCleanDirectories(true).call();
                try (PaperServer server = PaperServer.start(dir, PAPER_JAR, 25602, START_TIMEOUT, null)) {
                    server.stop(Duration.ofMinutes(2));
                    for (String line : server.lines()) {
                        if (line.contains("ERROR") || line.contains("Exception") || line.toLowerCase().contains("corrupt")) {
                            problems.add("snapshot " + i + ": " + line);
                        }
                    }
                } catch (Exception e) {
                    problems.add("snapshot " + i + ": " + e);
                }
            }
        }
        return problems;
    }

    private static void seedRemote(Path bare) throws Exception {
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        Path seed = bare.resolveSibling("seed");
        try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(seed.toFile()).call()) {
            WorldTemplate.writeInitialFiles(seed, false);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setSign(false).call();
            git.push().call();
        }
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }

    private static Map<String, String> checksums(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).filter(f -> !f.endsWith("session.lock")).toList()) {
                result.put(root.relativize(f).toString(), sha256(f));
            }
        }
        return result;
    }

    private static List<String> changedFiles(Map<String, String> before, Map<String, String> after) {
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        return keys.stream().filter(k -> !Objects.equals(before.get(k), after.get(k))).toList();
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
