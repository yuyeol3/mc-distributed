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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

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
    /** 느린 네트워크 시나리오에서도 접속 직후의 청크 데이터 뒤에 밀린 인벤토리 변경을 기다릴 수 있게. */
    private static final Duration INVENTORY_WAIT = Duration.ofMinutes(1);
    /** build.gradle.kts의 labOldImage가 만드는 옛 버전 노드(-Plab.oldRef). */
    private static final String OLD_NODE_IMAGE = "mcd-lab-node:old";

    @TempDir
    Path tmp;

    /** 호스트가 나가면 남은 플레이어는 각자 자기 PC의 중계로 옮겨 가고, 그 PC들 중 하나만 이어서 호스트가 된다. */
    @Test
    void remainingPlayersFollowOneNewHostWhenHostLeaves() throws Exception {
        try (Lab lab = startLab()) {
            handoff(lab, "");
        }
    }

    /** 모든 PC의 네트워크가 느리고 패킷이 빠져도 handoff가 되고, 살아 있는 호스트를 죽었다고 오판하지 않는다. */
    @Test
    void remainingPlayersFollowOneNewHostOnSlowLossyNetwork() throws Exception {
        try (Lab lab = startLab()) {
            // 실험실 안(노드, git, 조정 서버, DERP)으로 가는 트래픽에만 건다. 처음 기동할 때 인터넷에서 받는 Mojang jar는 시험 대상이 아니다.
            handoff(lab, "tc qdisc add dev eth0 root handle 1: prio"
                    + " && tc qdisc add dev eth0 parent 1:3 handle 30: netem delay 60ms 10ms loss 0.5%"
                    + " && tc filter add dev eth0 parent 1:0 protocol ip u32 match ip dst " + lab.subnet() + " flowid 1:3");
        }
    }

    /**
     * PC끼리 직접 연결이 안 되는 경우(NAT 뒤). 노드 사이의 UDP를 막으면 tailnet은 실험실 DERP 서버를 거친다.
     * 그래도 handoff가 되고 health 확인도 오판하지 않아야 한다.
     */
    @Test
    void remainingPlayersFollowOneNewHostWhenOnlyDerpConnects() throws Exception {
        try (Lab lab = startLab()) {
            handoff(lab, "iptables -A OUTPUT -p udp -d " + lab.derperIp() + " -j ACCEPT"
                    + " && iptables -A OUTPUT -p udp -d " + lab.subnet() + " -j DROP");
        }
    }

    /**
     * 친구들이 동시에 업데이트하지 않는다. 옛 버전(A, C)과 지금 버전(B)이 섞여 있어도 월드를 서로 넘길 수 있어야
     * 한다: 옛 A가 호스트일 때 B의 플레이어가 붙고, A가 나가면 B가 이어받고, 옛 C의 플레이어가 B에 붙었다가 B가 나가면
     * 옛 C가 이어받는다.
     */
    @Test
    void worldMovesBothWaysBetweenPreviousAndCurrentRelease() throws Exception {
        try (Lab lab = startLab();
             McdNode a = lab.node("A", OLD_NODE_IMAGE, "");
             McdNode b = lab.node("B");
             McdNode c = lab.node("C", OLD_NODE_IMAGE, "")) {
            for (McdNode n : List.of(a, b, c)) n.await(0, READY, Duration.ofMinutes(3));
            try (Bot alice = lab.join("A", "alice", Duration.ofMinutes(3))) {
                a.await(0, BECAME_HOST + "(epoch 1)", Duration.ofSeconds(10));
                b.await(0, "[mcd] 지금 호스트는 A입니다", Duration.ofSeconds(30));
                try (Bot bob = lab.join("B", "bob", Duration.ofMinutes(1))) {
                    a.sendAndAwait("give bob minecraft:diamond 3", "Gave 3");
                    assertEquals("diamond:3", bob.awaitInventory("diamond:3"));

                    // 옛 A -> 지금 B
                    alice.quit();
                    a.send("stop");
                    assertEquals("localhost:25565", bob.awaitTransfer());
                    b.await(0, BECAME_HOST + "(epoch 2)", Duration.ofSeconds(30));
                    assertEquals("diamond:3", bob.awaitInventory("diamond:3"));
                    assertTrue(a.waitForExit(Duration.ofSeconds(30)));

                    // 지금 B -> 옛 C
                    c.await(0, "[mcd] 지금 호스트는 B입니다", Duration.ofSeconds(30));
                    try (Bot carol = lab.join("C", "carol", Duration.ofMinutes(1))) {
                        b.sendAndAwait("give carol minecraft:emerald 2", "Gave 2");
                        assertEquals("emerald:2", carol.awaitInventory("emerald:2"));
                        int mb = b.mark();
                        bob.quit();
                        b.await(mb, "bob left the game", Duration.ofSeconds(10));
                        b.send("stop");
                        assertEquals("localhost:25565", carol.awaitTransfer());
                        c.await(0, BECAME_HOST + "(epoch 3)", Duration.ofSeconds(30));
                        assertEquals("emerald:2", carol.awaitInventory("emerald:2"));
                        stopLastHost(c, carol);
                    }
                }
            }
            lab.assertConsistentHistory("C", 3);
            assertEquals(List.of(), lab.recoveryBranches());
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

    /**
     * 호스트가 git 원격과만 끊기면(tailnet은 됨) 소유권을 확인할 수 없으므로 실행 허가가 끝날 때 스스로 Paper를 내린다.
     * 그 뒤 health가 끊긴 것을 본 다른 PC가 인수하고, 두 Paper가 동시에 writer인 구간은 없어야 한다.
     */
    @Test
    void hostCutOffFromGitStopsItselfBeforeAnotherTakesOver() throws Exception {
        try (Lab lab = startLab(); McdNode a = lab.node("A"); McdNode b = lab.node("B")) {
            a.await(0, READY, Duration.ofMinutes(3));
            b.await(0, READY, Duration.ofMinutes(3));
            try (Bot alice = lab.join("A", "alice", Duration.ofMinutes(3))) {
                a.await(0, BECAME_HOST + "(epoch 1)", Duration.ofSeconds(10));
                b.await(0, "[mcd] 지금 호스트는 A입니다", Duration.ofSeconds(30));
                int m = a.mark();
                try (Bot bob = lab.join("B", "bob", Duration.ofMinutes(1))) {
                    lab.sh("A", "iptables -A OUTPUT -d " + lab.gitIp() + " -j DROP");
                    awaitPaperStopped(lab, "A", Duration.ofSeconds(40));
                }
                // git이 끊긴 채로도 A의 mcd는 호스트 상태에서 빠져나와 알려야 한다(git 작업이 무한정 멈추면 안 된다).
                a.await(m, "원격 저장소를 확인하지 못했습니다", Duration.ofMinutes(2));
                b.await(0, HEALTH_FAILED, Duration.ofSeconds(40));
                try (Bot bob = joinNewHost(lab, b, "B", "bob", 2, "A")) {
                    lab.sh("A", "iptables -D OUTPUT -d " + lab.gitIp() + " -j DROP");
                    a.await(m, "[mcd] 지금 호스트는 B입니다", Duration.ofMinutes(1));
                    stopLastHost(b, bob);
                }
            }
            lab.assertConsistentHistory("B", 2);
            assertOnlyRecoveryOf(lab, "A-1-");
        }
    }

    /**
     * 호스트가 tailnet과 끊기면(git은 됨) 호스트는 멀쩡하지만 다른 PC에서는 죽은 writer로 보인다. 그 PC에서 접속하면
     * 인수가 일어나고, 옛 호스트는 git에서 소유권이 넘어간 것을 보고 인수 대기(30초) 안에 Paper를 내려야 한다.
     * 옛 호스트의 플레이어는 튕기지만 writer가 둘이 되거나 월드가 갈라지면 안 된다.
     */
    @Test
    void hostCutOffFromTailnetIsReplacedWithoutTwoWriters() throws Exception {
        try (Lab lab = startLab(); McdNode a = lab.node("A"); McdNode b = lab.node("B")) {
            a.await(0, READY, Duration.ofMinutes(3));
            b.await(0, READY, Duration.ofMinutes(3));
            try (Bot alice = lab.join("A", "alice", Duration.ofMinutes(3))) {
                a.await(0, BECAME_HOST + "(epoch 1)", Duration.ofSeconds(10));
                b.await(0, "[mcd] 지금 호스트는 A입니다", Duration.ofSeconds(30));
                // A에서 나가는 것은 자기 자신(alice 포함)과 git 원격만 허용한다.
                String isolate = "iptables -A OUTPUT -o lo -j ACCEPT && iptables -A OUTPUT -d " + lab.gitIp()
                        + " -j ACCEPT && iptables -A OUTPUT -j DROP";
                int m = a.mark();
                lab.sh("A", isolate);
                b.await(0, HEALTH_FAILED, Duration.ofSeconds(40));
                assertTrue(lab.paperRunning("A"), "A는 git이 되므로 계속 호스트여야 한다");
                try (Bot bob = joinNewHost(lab, b, "B", "bob", 2, "A")) {
                    lab.sh("A", "iptables -F OUTPUT");
                    a.await(m, "[mcd] 지금 호스트는 B입니다", Duration.ofMinutes(1));
                    stopLastHost(b, bob);
                }
            }
            lab.assertConsistentHistory("B", 2);
            assertOnlyRecoveryOf(lab, "A-1-");
        }
    }

    /**
     * node PC에서 user가 접속해 그 PC가 epoch번째 호스트가 되게 한다. 새 호스트가 Paper를 시작하는 순간 옛 호스트 old의
     * Paper가 남아 있으면 writer가 둘이 되므로, 그 순간을 지켜본다.
     */
    private static Bot joinNewHost(Lab lab, McdNode node, String name, String user, long epoch, String old) throws Exception {
        int m = node.mark();
        CompletableFuture<Bot> join = CompletableFuture.supplyAsync(() -> {
            try {
                return lab.join(name, user, Duration.ofMinutes(3));
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        node.await(m, BECAME_HOST + "(epoch " + epoch + ")", Duration.ofMinutes(2));
        assertFalse(lab.paperRunning(old), "새 writer가 Paper를 시작할 때 옛 writer(" + old + ")의 Paper가 남아 있으면 안 된다");
        return join.get(3, TimeUnit.MINUTES);
    }

    /** 마지막 플레이어가 나간 뒤 호스트를 꺼서 락을 반납한다. */
    private static void stopLastHost(McdNode host, Bot last) throws Exception {
        int m = host.mark();
        last.quit();
        host.await(m, "left the game", Duration.ofSeconds(10));
        host.send("stop");
        host.await(m, "[mcd] 월드를 올리고 락을 반납했습니다.", Duration.ofMinutes(2));
    }

    /** 옛 writer의 마지막 스냅샷 이후 진행분만 main 밖에 따로 보관돼야 한다. */
    private static void assertOnlyRecoveryOf(Lab lab, String prefix) throws Exception {
        List<String> recovery = lab.recoveryBranches();
        assertTrue(recovery.stream().allMatch(r -> r.startsWith("refs/heads/recovery/" + prefix)), recovery.toString());
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
                    assertEquals("diamond:3", bob.awaitInventory("diamond:3", INVENTORY_WAIT));

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
                    assertEquals("diamond:3", bob.awaitInventory("diamond:3", INVENTORY_WAIT), "넘긴 월드에서 인벤토리가 이어져야 한다");

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
