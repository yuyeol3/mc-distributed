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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MVP 완료 기준인 P0 시나리오 5개를 PC 1대에서 실행한다. 노드는 별도 JVM 프로세스, 플레이어는 Mineflayer 봇이다.
 * 실행: ./gradlew paperTest --tests '*P0ScenarioTest*'
 */
@Tag("paper")
class P0ScenarioTest {

    private static final int TTL_SECONDS = 60;
    private static final Duration HOST_START = Duration.ofMinutes(4);
    private static final String HOSTING = "[mcd] 다른 사람은";
    private static final String RELEASED = "[mcd] 월드를 올리고 락을 반납했다.";

    @TempDir
    Path tmp;

    @Test
    void p0Scenarios() throws Exception {
        assumeTrue(Files.exists(Path.of("nodes/_cache/paper-26.1.2-74.jar")), "Paper jar가 없다");
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        InitCommand.run(tmp.resolve("init"), remote, false, null);
        Path dirA = tmp.resolve("A");
        Path dirB = tmp.resolve("B");

        // 1. A가 혼자 플레이하며 건축하고 종료한다.
        try (McdNode a = McdNode.play("A", remote, dirA, 25611, TTL_SECONDS)) {
            a.await(0, HOSTING, HOST_START);
            a.send("difficulty peaceful");
            try (Bot alice = Bot.join("alice", 25611)) {
                a.sendAndAwait("give alice minecraft:diamond 5", "Gave 5");
                a.sendAndAwait("execute at alice run setblock ~ ~5 ~ minecraft:gold_block", "Changed the block");
                assertEquals("diamond:5", alice.inventory());
                alice.quit();
            }
            int mark = a.mark();
            a.send("stop");
            a.await(mark, RELEASED, Duration.ofMinutes(2));
            assertTrue(a.waitForExit(Duration.ofSeconds(30)));
        }

        // 2. B가 플레이하면 A의 건축물과 인벤토리가 그대로 있다.
        McdNode b = McdNode.play("B", remote, dirB, 25612, TTL_SECONDS);
        b.await(0, HOSTING, HOST_START);
        Bot alice = Bot.join("alice", 25612);
        assertEquals("diamond:5", alice.inventory());
        b.sendAndAwait("execute at alice if block ~ ~5 ~ minecraft:gold_block", "Test passed");

        // 3. B가 호스트일 때 A를 실행하면 서버를 띄우지 않고 B의 주소를 안내하고, 같이 플레이할 수 있다.
        McdNode a = McdNode.play("A", remote, dirA, 25611, TTL_SECONDS);
        a.await(0, "[mcd] 지금 호스트는 B이다. 마인크래프트에서 localhost:25612로 접속", Duration.ofMinutes(1));
        assertFalse(a.printed("호스트가 됐다"));
        Bot bob = Bot.join("bob", 25612);
        String list = b.sendAndAwait("list", "players online");
        assertTrue(list.contains("alice") && list.contains("bob"), list);

        // 5. 호스트(B)가 갑자기 꺼지면 TTL 뒤 A가 이어받고, 다시 켜진 B의 변경분은 recovery 브랜치로만 간다.
        b.sendAndAwait("give alice minecraft:emerald 1", "Gave 1");
        b.sendAndAwait("save-all flush", "Saved the game");
        alice.close();
        bob.close();
        b.killHard();
        a.await(0, "[mcd] 이 PC가 호스트가 됐다", Duration.ofSeconds(TTL_SECONDS * 3L));
        a.await(0, HOSTING, HOST_START);
        try (Bot alice2 = Bot.join("alice", 25611)) {
            assertTrue(alice2.inventory().contains("diamond:5"), "마지막 스냅샷까지의 인벤토리는 남아야 한다");
            alice2.quit();
        }
        try (McdNode b2 = McdNode.play("B", remote, dirB, 25612, TTL_SECONDS)) {
            b2.await(0, "[mcd] 지금 호스트는 A이다", Duration.ofMinutes(1));
            assertTrue(branches(bare).stream().anyMatch(n -> n.startsWith("refs/heads/recovery/B-")), "B의 변경분은 recovery에 있어야 한다");
            assertEquals("A", remoteLock(bare).owner());
            b2.send("stop");
            assertTrue(b2.waitForExit(Duration.ofSeconds(30)));
        }
        int mark = a.mark();
        a.send("stop");
        a.await(mark, RELEASED, Duration.ofMinutes(2));
        assertTrue(a.waitForExit(Duration.ofSeconds(30)));

        // 4. A와 B를 동시에 실행하면 서버는 하나만 뜬다.
        try (McdNode a3 = McdNode.play("A", remote, dirA, 25611, TTL_SECONDS);
             McdNode b3 = McdNode.play("B", remote, dirB, 25612, TTL_SECONDS)) {
            McdNode host = awaitEither(a3, b3, "[mcd] 이 PC가 호스트가 됐다");
            McdNode other = host == a3 ? b3 : a3;
            other.await(0, "[mcd] 지금 호스트는", Duration.ofMinutes(1));
            host.await(0, HOSTING, HOST_START);
            Thread.sleep(Duration.ofSeconds(TTL_SECONDS / 2));
            assertFalse(other.printed("호스트가 됐다"), "두 번째 서버가 뜨면 안 된다");
            other.send("stop");
            int m = host.mark();
            host.send("stop");
            host.await(m, RELEASED, Duration.ofMinutes(2));
        }
    }

    private static McdNode awaitEither(McdNode x, McdNode y, String text) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (x.printed(text)) return x;
            if (y.printed(text)) return y;
            Thread.sleep(200);
        }
        throw new AssertionError("둘 다 호스트가 되지 않았다");
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
