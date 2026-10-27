package mcd.cli;

import mcd.e2e.Bot;
import mcd.e2e.Lab;
import mcd.e2e.McdNode;
import mcd.lock.LockState;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 도커 실험실: 세 PC(A, B, C)가 tailnet으로 이어진 실제 배포 구성(모두 25565)에서 호스트가 나가면,
 * 남은 플레이어는 각자 자기 PC의 중계로 옮겨 가고 그 PC들 중 하나만 이어서 호스트가 된다.
 * 실행: ./gradlew labTest
 */
@Tag("lab")
class LabHandoffTest {

    private static final String BECAME_HOST = "[mcd] 이 PC가 호스트가 됐습니다";

    @TempDir
    Path tmp;

    @Test
    void remainingPlayersFollowOneNewHostWhenHostLeaves() throws Exception {
        try (Lab lab = Lab.start(tmp)) {
            InitCommand.run(tmp.resolve("init"), lab.bare().toUri().toString(), false, Optional.of(lab.network()), Bot.PAPER_BUILD, null);
            try (McdNode a = lab.node("A"); McdNode b = lab.node("B"); McdNode c = lab.node("C")) {
                for (McdNode n : List.of(a, b, c)) n.await(0, "[mcd] 준비됐습니다", Duration.ofMinutes(3));

                try (Bot alice = lab.join("A", "alice", Duration.ofMinutes(3))) {
                    a.await(0, BECAME_HOST + "(epoch 1)", Duration.ofSeconds(10));
                    b.await(0, "[mcd] 지금 호스트는 A입니다", Duration.ofSeconds(30));
                    c.await(0, "[mcd] 지금 호스트는 A입니다", Duration.ofSeconds(30));
                    try (Bot bob = lab.join("B", "bob", Duration.ofMinutes(1));
                         Bot carol = lab.join("C", "carol", Duration.ofMinutes(1))) {
                        a.sendAndAwait("give bob minecraft:diamond 3", "Gave 3");
                        assertEquals("diamond:3", bob.awaitInventory("diamond:3"));

                        // A의 주인이 나가고 A를 끈다. 남은 bob과 carol은 각자 자기 PC의 중계로 옮겨 간다.
                        alice.quit();
                        a.send("stop");
                        assertEquals("localhost:25565", bob.awaitTransfer());
                        assertEquals("localhost:25565", carol.awaitTransfer());
                        a.await(0, "[mcd] 월드를 올리고 서버를 넘겼습니다.", Duration.ofMinutes(1));

                        boolean bHosts = b.printed(BECAME_HOST + "(epoch 2)");
                        boolean cHosts = c.printed(BECAME_HOST + "(epoch 2)");
                        assertNotEquals(bHosts, cHosts, "B와 C 중 하나만 호스트가 되어야 한다");
                        McdNode next = bHosts ? b : c;
                        String players = next.sendAndAwait("list", "players online");
                        assertTrue(players.contains("bob") && players.contains("carol"), players);
                        assertEquals("diamond:3", bob.awaitInventory("diamond:3"), "넘긴 월드에서 인벤토리가 이어져야 한다");

                        int m = next.mark();
                        bob.quit();
                        carol.quit();
                        next.await(m, "bob left the game", Duration.ofSeconds(10));
                        next.await(m, "carol left the game", Duration.ofSeconds(10));
                        next.send("stop");
                        next.await(m, "[mcd] 월드를 올리고 락을 반납했습니다.", Duration.ofMinutes(2));

                        assertConsistentHistory(lab.bare(), bHosts ? "B" : "C", 2);
                    }
                }
            }
        }
    }

    /** 원격의 main을 처음부터 따라가며 락 epoch가 줄지 않았고, 마지막 락이 owner/epoch이며, 남은 변경 백업이 없는지 본다. */
    private static void assertConsistentHistory(Path bare, String owner, long epoch) throws Exception {
        try (Git git = Git.open(bare.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            List<String> branches = repo.getRefDatabase().getRefsByPrefix("refs/heads/").stream().map(Ref::getName).toList();
            assertTrue(branches.stream().noneMatch(n -> n.startsWith("refs/heads/recovery/")), "recovery 브랜치가 생기면 안 된다: " + branches);

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
}
