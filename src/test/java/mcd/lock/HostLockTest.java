package mcd.lock;

import mcd.lock.HostLock.Acquisition;
import mcd.lock.HostLock.PushOutcome;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostLockTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration MARGIN = Duration.ofMinutes(2);
    private static final String WORLD_FILE = "world/region/r.0.0.mca";

    @TempDir
    Path tmp;

    private final MutableClock clock = new MutableClock();
    private final List<Git> opened = new ArrayList<>();
    private Path bare;
    private Git gitA;
    private Git gitB;

    @BeforeEach
    void setUp() throws Exception {
        bare = tmp.resolve("remote.git");
        try (Git remote = Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call()) {
            // CAS 경쟁 직후 background GC가 pack을 열고 있으면 Windows의 임시 폴더 삭제가 실패한다.
            var config = remote.getRepository().getConfig();
            config.setInt("gc", null, "auto", 0);
            config.setInt("gc", null, "autoPackLimit", 0);
            config.save();
        }

        Path seed = tmp.resolve("seed");
        try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(seed.toFile()).call()) {
            LockState.initial().write(seed);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setSign(false).call();
            git.push().call();
        }
        gitA = clone("A");
        gitB = clone("B");
    }

    @AfterEach
    void tearDown() {
        opened.forEach(Git::close);
    }

    @Test
    void sameDisplayNameOnAnotherWorkTreeCannotStealLiveLock() throws Exception {
        assertTrue(lock(gitA, "A").tryAcquire("a").acquired());

        assertFalse(lock(gitB, "A").tryAcquire("b").acquired(),
                "표시 이름이 같아도 다른 작업 트리는 다른 노드다");
    }

    @Test
    void healthTakeoverRequiresExactGuardedOwnerAndRejectsOldWriterPush() throws Exception {
        HostLock a = lock(gitA, "A");
        HostLock b = lock(gitB, "B");
        LockState original = a.tryAcquire("a", "a:9", null).lock();
        assertFalse(b.tryAcquire("b", "b:9", null).acquired());

        Acquisition next = b.tryAcquire("b", "b:9", original);

        assertTrue(next.acquired());
        assertEquals(original.epoch() + 1, next.lock().epoch());
        assertEquals(PushOutcome.REJECTED, a.heartbeat());
        assertFalse(a.tryAcquire("a", "a:9", original).acquired(), "과거 health 실패를 새 owner에 재사용하면 안 된다");
    }

    @Test
    void maintenanceBlocksHealthTakeoverAndIsNotReclaimedBySameName() throws Exception {
        HostLock a = lock(gitA, "A");
        LockState state = a.tryMaintain().lock();
        assertEquals(LockState.State.MAINTENANCE, state.state());
        assertFalse(lock(gitB, "A").tryMaintain().acquired());
        assertFalse(lock(gitB, "B").tryAcquire("b", "b:9", state).acquired());
        assertEquals(PushOutcome.OK, a.release());
        assertTrue(lock(gitB, "B").tryMaintain().acquired());
    }

    @Test
    void regularSnapshotDoesNotPublishManuallyDroppedPluginJar() throws Exception {
        HostLock a = lock(gitA, "A");
        a.tryAcquire("a");
        write(gitA, "plugins/local-only.jar", "unmanaged");
        a.snapshot("regular snapshot");
        assertEquals(Optional.empty(), remoteFile("main", "plugins/local-only.jar"), "JAR 설치는 검증된 관리 작업에서만 게시한다");
    }

    @Test
    void firstNodeAcquiresFreeLockAndOtherSeesItHeld() throws Exception {
        Acquisition a = lock(gitA, "A").tryAcquire("a:25565");
        Acquisition b = lock(gitB, "B").tryAcquire("b:25566");

        assertTrue(a.acquired());
        assertEquals(1, a.lock().epoch());
        assertFalse(b.acquired());
        assertEquals("A", b.lock().owner());
        assertEquals("a:25565", b.lock().address());
    }

    @Test
    void otherNodeTakesOverOnlyAfterObservingSameHeadForTtl() throws Exception {
        HostLock a = lock(gitA, "A");
        HostLock b = lock(gitB, "B");
        a.tryAcquire("a");

        assertFalse(b.tryAcquire("b").acquired());
        clock.advance(TTL.minusSeconds(1));
        assertFalse(b.tryAcquire("b").acquired());
        clock.advance(Duration.ofSeconds(1));
        Acquisition taken = b.tryAcquire("b");

        assertTrue(taken.acquired());
        assertEquals(2, taken.lock().epoch());
        assertEquals(PushOutcome.REJECTED, a.heartbeat(), "락을 잃은 호스트의 push는 거절돼야 한다");
    }

    @Test
    void heartbeatPostponesTakeover() throws Exception {
        HostLock a = lock(gitA, "A");
        HostLock b = lock(gitB, "B");
        a.tryAcquire("a");
        b.tryAcquire("b");

        clock.advance(Duration.ofMinutes(5));
        assertEquals(PushOutcome.OK, a.heartbeat());
        clock.advance(Duration.ofMinutes(5));
        assertFalse(b.tryAcquire("b").acquired(), "heartbeat 이후의 main은 새로 관찰한 것이다");
        clock.advance(TTL);
        assertTrue(b.tryAcquire("b").acquired());
    }

    @Test
    void releaseHandsOverWorldImmediately() throws Exception {
        HostLock a = lock(gitA, "A");
        a.tryAcquire("a");
        write(gitA, WORLD_FILE, "built by A");

        assertEquals(PushOutcome.OK, a.release());
        Acquisition b = lock(gitB, "B").tryAcquire("b");

        assertTrue(b.acquired());
        assertEquals("built by A", read(gitB, WORLD_FILE));
    }

    @Test
    void snapshotPushesWorldWhileKeepingLock() throws Exception {
        HostLock a = lock(gitA, "A");
        a.tryAcquire("a");
        write(gitA, WORLD_FILE, "v1");

        assertEquals(PushOutcome.OK, a.snapshot("snapshot"));

        assertEquals(Optional.of("v1"), remoteFile("main", WORLD_FILE));
        assertFalse(lock(gitB, "B").tryAcquire("b").acquired());
    }

    @Test
    void crashedHostWhoseLockWasTakenOverKeepsChangesOnlyInRecoveryBranch() throws Exception {
        lock(gitA, "A").tryAcquire("a");
        write(gitA, WORLD_FILE, "unsaved by A");
        // A가 크래시: HostLock 객체를 버리고 push하지 못한 변경만 작업 트리에 남는다.

        HostLock b = lock(gitB, "B");
        b.tryAcquire("b");
        clock.advance(TTL);
        assertTrue(b.tryAcquire("b").acquired());

        Acquisition restarted = lock(gitA, "A").tryAcquire("a");

        assertFalse(restarted.acquired());
        assertEquals("B", restarted.lock().owner());
        assertEquals(Optional.empty(), remoteFile("main", WORLD_FILE), "main은 갈라지지 않아야 한다");
        String recovery = remoteBranches().stream().filter(n -> n.startsWith("refs/heads/recovery/A-1-")).findFirst().orElseThrow();
        assertEquals(Optional.of("unsaved by A"), remoteFile(recovery, WORLD_FILE));
        assertFalse(Files.exists(gitA.getRepository().getWorkTree().toPath().resolve(WORLD_FILE)));
    }

    @Test
    void crashedHostReclaimsLockAndKeepsChangesWhenNobodyTookOver() throws Exception {
        lock(gitA, "A").tryAcquire("a");
        write(gitA, WORLD_FILE, "unsaved by A");

        Acquisition restarted = lock(gitA, "A").tryAcquire("a");

        assertTrue(restarted.acquired());
        assertEquals(2, restarted.lock().epoch());
        assertEquals(Optional.of("unsaved by A"), remoteFile("main", WORLD_FILE));
    }

    @Test
    void releaseWithoutWorldSharesOnlyLastSnapshotAndKeepsRestInRecovery() throws Exception {
        HostLock a = lock(gitA, "A");
        a.tryAcquire("a");
        write(gitA, WORLD_FILE, "snapshotted");
        a.snapshot("snapshot");
        write(gitA, WORLD_FILE, "written by crashed server");

        assertEquals(PushOutcome.OK, a.releaseWithoutWorld());
        assertEquals(Optional.of("snapshotted"), remoteFile("main", WORLD_FILE));
        assertTrue(lock(gitB, "B").tryAcquire("b").acquired(), "락은 바로 풀려야 한다");

        lock(gitA, "A").tryAcquire("a");
        String recovery = remoteBranches().stream().filter(n -> n.startsWith("refs/heads/recovery/A-")).findFirst().orElseThrow();
        assertEquals(Optional.of("written by crashed server"), remoteFile(recovery, WORLD_FILE));
        assertEquals(Optional.of("snapshotted"), remoteFile("main", WORLD_FILE));
    }

    @Test
    void dirtyTreeOfNonOwnerNeverReachesMain() throws Exception {
        lock(gitA, "A").tryAcquire("a");
        HostLock b = lock(gitB, "B");
        b.tryAcquire("b");
        write(gitB, WORLD_FILE, "stray change by B");

        assertFalse(b.tryAcquire("b").acquired());

        assertEquals(Optional.empty(), remoteFile("main", WORLD_FILE));
        assertTrue(remoteBranches().stream().anyMatch(n -> n.startsWith("refs/heads/recovery/B-")));
    }

    @Test
    void hostMustStopWhenLeaseCouldHaveExpired() throws Exception {
        HostLock a = lock(gitA, "A");
        assertTrue(a.shouldStop(), "락을 잡기 전에는 호스트가 아니다");

        a.tryAcquire("a");
        clock.advance(TTL.minus(MARGIN).minusSeconds(1));
        assertFalse(a.shouldStop());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(a.shouldStop());

        a.heartbeat();
        assertFalse(a.shouldStop());
    }

    @Test
    void exactlyOneOfConcurrentAcquirersWins() throws Exception {
        HostLock a = lock(gitA, "A");
        HostLock b = lock(gitB, "B");

        for (int round = 0; round < 20; round++) {
            CyclicBarrier start = new CyclicBarrier(2);
            Future<Acquisition> fa;
            Future<Acquisition> fb;
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                fa = pool.submit(() -> { start.await(); return a.tryAcquire("a"); });
                fb = pool.submit(() -> { start.await(); return b.tryAcquire("b"); });
            }
            boolean aWon = fa.get().acquired();
            boolean bWon = fb.get().acquired();
            assertTrue(aWon ^ bWon, "round " + round + ": A=" + aWon + " B=" + bWon);
            LockState seenByLoser = (aWon ? fb : fa).get().lock();
            assertEquals(aWon ? "A" : "B", seenByLoser.owner(), "진 쪽은 승자의 락을 봐야 한다");
            assertEquals(LockState.State.HOSTING, seenByLoser.state());
            assertEquals(PushOutcome.OK, (aWon ? a : b).release());
        }
    }

    private HostLock lock(Git git, String node) {
        return new HostLock(git, node, clock, TTL, MARGIN, null);
    }

    private Git clone(String name) throws Exception {
        Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(tmp.resolve(name).toFile()).call();
        opened.add(git);
        return git;
    }

    private static void write(Git git, String path, String content) throws Exception {
        Path file = git.getRepository().getWorkTree().toPath().resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String read(Git git, String path) throws Exception {
        return Files.readString(git.getRepository().getWorkTree().toPath().resolve(path));
    }

    private Optional<String> remoteFile(String ref, String path) throws Exception {
        try (Git remote = Git.open(bare.toFile()); RevWalk walk = new RevWalk(remote.getRepository())) {
            Repository repo = remote.getRepository();
            try (TreeWalk tree = TreeWalk.forPath(repo, path, walk.parseCommit(repo.resolve(ref)).getTree())) {
                if (tree == null) return Optional.empty();
                return Optional.of(new String(repo.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private List<String> remoteBranches() throws Exception {
        try (Git remote = Git.open(bare.toFile())) {
            return remote.getRepository().getRefDatabase().getRefsByPrefix("refs/heads/").stream().map(Ref::getName).toList();
        }
    }

    private static final class MutableClock implements InstantSource {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }
}
