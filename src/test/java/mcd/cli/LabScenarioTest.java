package mcd.cli;

import mcd.e2e.Bot;
import mcd.e2e.Lab;
import mcd.e2e.McdNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 도커 실험실 시나리오. 시나리오마다 새 실험실을 띄운다. PC(노드)는 tailnet으로 이어져 있고 모두 25565를 쓴다.
 * 실행: ./gradlew labTest (하나만: --tests '*LabScenarioTest.<메서드>*')
 */
@Tag("lab")
class LabScenarioTest {

    private static final String BECAME_HOST = "[mcd] 이 PC가 호스트가 됐습니다";
    private static final String HEALTH_FAILED = "writer 상태 확인이 연속 실패";
    private static final String READY = "[mcd] 준비됐습니다";

    @TempDir
    Path tmp;

    /** 호스트가 나가면 남은 플레이어는 각자 자기 PC의 중계로 옮겨 가고, 그 PC들 중 하나만 이어서 호스트가 된다. */
    @Test
    void remainingPlayersFollowOneNewHostWhenHostLeaves() throws Exception {
        try (Lab lab = startLab()) {
            handoff(lab, "");
        }
    }

    /**
     * 호스트 PC가 통째로 멈추면(전원은 켜져 있지만 응답 없음) 다른 PC가 health 실패를 보고 lease를 기다리지 않고
     * 인수한다. 멈췄던 PC가 깨어나면 옛 Paper는 소유권 확인이 만료돼 곧바로 내려가고 새 호스트를 따라가야 하며,
     * 옛 writer의 push가 공유 이력에 섞이면 안 된다.
     */
    @Test
    void frozenHostIsReplacedAndOldWriterStopsWhenItWakes() throws Exception {
        try (Lab lab = startLab(); McdNode a = lab.node("A"); McdNode b = lab.node("B")) {
            a.await(0, READY, Duration.ofMinutes(3));
            b.await(0, READY, Duration.ofMinutes(3));
            try (Bot alice = lab.join("A", "alice", Duration.ofMinutes(3))) {
                a.await(0, BECAME_HOST + "(epoch 1)", Duration.ofSeconds(10));
                b.await(0, "[mcd] 지금 호스트는 A입니다", Duration.ofSeconds(30));

                lab.pause("A");
                b.await(0, HEALTH_FAILED, Duration.ofSeconds(40));
                try (Bot bob = lab.join("B", "bob", Duration.ofMinutes(2))) {
                    assertTrue(b.printed("writer 응답이 없어 락을 인수했습니다"));
                    assertTrue(b.printed(BECAME_HOST + "(epoch 2)"));

                    int m = a.mark();
                    lab.unpause("A");
                    awaitPaperStopped(lab, "A", Duration.ofSeconds(15));
                    a.await(m, "[mcd] 지금 호스트는 B입니다", Duration.ofMinutes(1));
                    String players = b.sendAndAwait("list", "players online");
                    assertTrue(players.contains("bob"), players);

                    int mb = b.mark();
                    bob.quit();
                    b.await(mb, "bob left the game", Duration.ofSeconds(10));
                    b.send("stop");
                    b.await(mb, "[mcd] 월드를 올리고 락을 반납했습니다.", Duration.ofMinutes(2));
                }
            }
            lab.assertConsistentHistory("B", 2);
            // 멈췄던 A의 마지막 스냅샷 이후 진행분은 main에 섞이지 않고 따로 보관된다.
            List<String> recovery = lab.recoveryBranches();
            assertEquals(1, recovery.size(), recovery.toString());
            assertTrue(recovery.getFirst().startsWith("refs/heads/recovery/A-1-"), recovery.toString());
        }
    }

    private static void awaitPaperStopped(Lab lab, String node, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (lab.paperRunning(node)) {
            assertTrue(System.nanoTime() < deadline, node + "의 옛 Paper가 " + timeout.toSeconds() + "초 안에 멈춰야 한다");
            Thread.sleep(500);
        }
    }

    private Lab startLab() throws Exception {
        Lab lab = Lab.start(tmp);
        try {
            InitCommand.run(tmp.resolve("init"), lab.bare().toUri().toString(), false, Optional.of(lab.network()), Bot.PAPER_BUILD, null);
            return lab;
        } catch (Exception e) {
            lab.close();
            throw e;
        }
    }

    /**
     * A, B, C에 alice, bob, carol이 있고 A가 호스트다. alice가 나가고 A를 끄면 bob과 carol이 자기 PC의 중계로 옮겨
     * 가서 B나 C 한 곳에 모이고 인벤토리가 이어진다. setup은 모든 PC에 mcd보다 먼저 거는 네트워크 조건이다.
     * 그동안 어느 PC도 다른 PC를 죽은 writer로 오판하면 안 된다.
     */
    private static void handoff(Lab lab, String setup) throws Exception {
        try (McdNode a = lab.node("A", Lab.NODE_IMAGE, setup);
             McdNode b = lab.node("B", Lab.NODE_IMAGE, setup);
             McdNode c = lab.node("C", Lab.NODE_IMAGE, setup)) {
            for (McdNode n : List.of(a, b, c)) n.await(0, READY, Duration.ofMinutes(3));

            try (Bot alice = lab.join("A", "alice", Duration.ofMinutes(3))) {
                a.await(0, BECAME_HOST + "(epoch 1)", Duration.ofSeconds(10));
                b.await(0, "[mcd] 지금 호스트는 A입니다", Duration.ofSeconds(30));
                c.await(0, "[mcd] 지금 호스트는 A입니다", Duration.ofSeconds(30));
                try (Bot bob = lab.join("B", "bob", Duration.ofMinutes(1));
                     Bot carol = lab.join("C", "carol", Duration.ofMinutes(1))) {
                    a.sendAndAwait("give bob minecraft:diamond 3", "Gave 3");
                    assertEquals("diamond:3", bob.awaitInventory("diamond:3"));

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

                    for (McdNode n : List.of(a, b, c)) assertFalse(n.printed(HEALTH_FAILED), "살아 있는 호스트를 죽었다고 보면 안 된다");
                    lab.assertConsistentHistory(bHosts ? "B" : "C", 2);
                    assertEquals(List.of(), lab.recoveryBranches(), "넘긴 뒤의 잔여 변경이 recovery로 가면 안 된다");
                }
            }
        }
    }
}
