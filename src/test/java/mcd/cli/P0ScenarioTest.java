package mcd.cli;

import mcd.e2e.Bot;
import mcd.e2e.McdNode;
import mcd.lock.LockState;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P0 시나리오 5개를 PC 1대에서 실행한다. 노드는 별도 JVM 프로세스, 플레이어는 Mineflayer 봇이다.
 * 봇은 자기 노드의 중계(localhost)에만 접속한다. 노드 A의 중계는 25611, B는 25621이다.
 * 실행: ./gradlew paperTest --tests '*P0ScenarioTest*'
 */
@Tag("paper")
class P0ScenarioTest {

    private static final int TTL_SECONDS = 60;
    private static final int RELAY_A = 25611;
    private static final int RELAY_B = 25621;
    private static final String READY = "[mcd] 준비됐다";
    private static final String BECAME_HOST = "[mcd] 이 PC가 호스트가 됐다";
    private static final String RELEASED = "[mcd] 월드를 올리고 락을 반납했다.";
    /** stop 직전까지 플레이어가 남아 있으면 반납 대신 넘기기가 될 수 있다. 어느 쪽이든 월드를 올리고 끝난다. */
    private static final String UPLOADED = "[mcd] 월드를 올리고";

    @TempDir
    Path tmp;

    @Test
    void p0Scenarios() throws Exception {
        assumeTrue(Files.exists(Path.of("nodes/_cache/paper-26.1.2-74.jar")), "Paper jar가 없다");
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        InitCommand.run(tmp.resolve("init"), remote, false, java.util.Optional.empty(), null);
        Path dirA = tmp.resolve("A");
        Path dirB = tmp.resolve("B");

        // 1. A가 접속하면 A가 호스트가 되고, 건축한 뒤 나가면 빈 서버가 스스로 내려가며 월드를 올린다.
        try (McdNode a = McdNode.play("A", remote, dirA, RELAY_A, TTL_SECONDS)) {
            a.await(0, READY, Duration.ofMinutes(1));
            try (Bot alice = Bot.joinViaRelay("alice", RELAY_A)) {
                assertTrue(a.printed(BECAME_HOST));
                a.send("difficulty peaceful");
                a.sendAndAwait("give alice minecraft:diamond 5", "Gave 5");
                a.sendAndAwait("execute at alice run setblock ~ ~5 ~ minecraft:gold_block", "Changed the block");
                assertEquals("diamond:5", alice.awaitInventory("diamond:5"));
                alice.quit();
            }
            a.await(0, RELEASED, Duration.ofSeconds(TTL_SECONDS * 2L));
            a.send("stop");
            assertTrue(a.waitForExit(Duration.ofSeconds(30)));
        }

        // 2. B에서 접속하면 B가 호스트가 되고 A의 건축물과 인벤토리가 그대로 있다.
        McdNode b = McdNode.play("B", remote, dirB, RELAY_B, TTL_SECONDS);
        b.await(0, READY, Duration.ofMinutes(1));
        Bot alice = Bot.joinViaRelay("alice", RELAY_B);
        b.await(0, BECAME_HOST + "(epoch 2)", Duration.ofSeconds(5));
        assertEquals("diamond:5", alice.awaitInventory("diamond:5"));
        b.sendAndAwait("execute at alice if block ~ ~5 ~ minecraft:gold_block", "Test passed");

        // 3. B가 호스트일 때 A의 중계로 접속하면 A는 서버를 띄우지 않고 B로 이어준다.
        McdNode a = McdNode.play("A", remote, dirA, RELAY_A, TTL_SECONDS);
        a.await(0, "[mcd] 지금 호스트는 B이다", Duration.ofMinutes(1));
        Bot bob = Bot.joinViaRelay("bob", RELAY_A);
        assertFalse(a.printed(BECAME_HOST));
        String list = b.sendAndAwait("list", "players online");
        assertTrue(list.contains("alice") && list.contains("bob"), list);

        // 5. 호스트(B)가 갑자기 꺼지면 TTL 뒤 A의 중계로 접속할 때 A가 이어받고, 다시 켜진 B의 변경분은 recovery로만 간다.
        b.sendAndAwait("give alice minecraft:emerald 1", "Gave 1");
        b.sendAndAwait("save-all flush", "Saved the game");
        alice.close();
        bob.close();
        b.killHard();
        Thread.sleep(Duration.ofSeconds(TTL_SECONDS + 10));
        try (Bot alice2 = Bot.joinViaRelay("alice", RELAY_A)) {
            a.await(0, BECAME_HOST + "(epoch 3)", Duration.ofSeconds(5));
            assertTrue(alice2.awaitInventory("diamond:5").contains("diamond:5"), "마지막 스냅샷까지의 인벤토리는 남아야 한다");
            try (McdNode b2 = McdNode.play("B", remote, dirB, RELAY_B, TTL_SECONDS)) {
                b2.await(0, "[mcd] 지금 호스트는 A이다", Duration.ofMinutes(1));
                assertTrue(branches(bare).stream().anyMatch(n -> n.startsWith("refs/heads/recovery/B-")), "B의 변경분은 recovery에 있어야 한다");
                assertEquals("A", remoteLock(bare).owner());
                b2.send("stop");
                assertTrue(b2.waitForExit(Duration.ofSeconds(30)));
            }
            alice2.quit();
        }
        int mark = a.mark();
        a.send("stop");
        a.await(mark, UPLOADED, Duration.ofMinutes(2));
        assertTrue(a.waitForExit(Duration.ofSeconds(30)));

        // 4. 두 사람이 각자 자기 중계로 동시에 접속해도 서버는 하나만 뜨고 둘 다 같은 서버에 들어간다.
        try (McdNode a3 = McdNode.play("A", remote, dirA, RELAY_A, TTL_SECONDS);
             McdNode b3 = McdNode.play("B", remote, dirB, RELAY_B, TTL_SECONDS)) {
            a3.await(0, READY, Duration.ofMinutes(1));
            b3.await(0, READY, Duration.ofMinutes(1));
            Bot[] bots = joinConcurrently(() -> Bot.joinViaRelay("alice", RELAY_A), () -> Bot.joinViaRelay("bob", RELAY_B));
            try (Bot alice3 = bots[0]; Bot bob3 = bots[1]) {
                assertTrue(a3.printed(BECAME_HOST) ^ b3.printed(BECAME_HOST), "호스트는 정확히 하나여야 한다");
                McdNode host = a3.printed(BECAME_HOST) ? a3 : b3;
                String players = host.sendAndAwait("list", "players online");
                assertTrue(players.contains("alice") && players.contains("bob"), players);
                alice3.quit();
                bob3.quit();
                int m = host.mark();
                a3.send("stop");
                b3.send("stop");
                host.await(m, UPLOADED, Duration.ofMinutes(2));
            }
        }
    }

    private static Bot[] joinConcurrently(Callable<Bot> first, Callable<Bot> second) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Bot> f1 = pool.submit(first);
            Future<Bot> f2 = pool.submit(second);
            return new Bot[] {f1.get(), f2.get()};
        }
    }

    private static List<String> branches(Path bare) throws Exception {
        try (Git git = Git.open(bare.toFile())) {
            return git.getRepository().getRefDatabase().getRefsByPrefix("refs/heads/").stream().map(Ref::getName).toList();
        }
    }

    private static LockState remoteLock(Path bare) throws Exception {
        try (Git git = Git.open(bare.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            try (TreeWalk tree = TreeWalk.forPath(repo, LockState.FILE_NAME, walk.parseCommit(repo.resolve("main")).getTree())) {
                return LockState.parse(repo.open(tree.getObjectId(0)).getBytes());
            }
        }
    }
}
