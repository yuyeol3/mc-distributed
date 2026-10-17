package mcd.cli;

import mcd.e2e.Bot;
import mcd.e2e.McdNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M3 완료 기준: 호스트가 나가도 접속 중인 플레이어는 사람 손을 거치지 않고 같은 월드에서 계속 플레이한다.
 * 한 PC 시뮬레이션이라 A의 handoff 포트를 B의 중계로 둔다(실제로는 모두 각자 PC의 25565).
 * 실행: ./gradlew paperTest --tests '*HandoffScenarioTest*'
 */
@Tag("paper")
class HandoffScenarioTest {

    private static final int RELAY_A = 25911;
    private static final int RELAY_B = 25921;
    private static final String BECAME_HOST = "[mcd] 이 PC가 호스트가 됐다";

    @TempDir
    Path tmp;

    @Test
    void playersFollowWorldToNextHostWhenHostLeaves() throws Exception {
        assumeTrue(Files.exists(Path.of("nodes/_cache/paper-26.1.2-74.jar")), "Paper jar가 없다");
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        InitCommand.run(tmp.resolve("init"), remote, false, Optional.empty(), Bot.PAPER_BUILD, null);

        try (McdNode b = McdNode.play("B", remote, tmp.resolve("B"), RELAY_B, 60)) {
            b.await(0, "[mcd] 준비됐다", Duration.ofMinutes(1));
            // B의 Paper를 한 번 띄워 둔다. 새 폴더의 첫 기동은 중계 대기 시간(25초)을 넘기기 때문이다.
            int afterWarmup;
            try (Bot warmup = Bot.joinViaRelay("alice", RELAY_B)) {
                // 첫 접속이 실패한 사이 B가 한 번 반납했다가 다시 호스트가 될 수 있으므로, 봇이 나간 뒤의 반납만 본다.
                afterWarmup = b.mark();
                warmup.quit();
            }
            b.await(afterWarmup, "[mcd] 월드를 올리고 락을 반납했다.", Duration.ofMinutes(2));

            try (McdNode a = McdNode.play("A", remote, tmp.resolve("A"), RELAY_A, 60, RELAY_B)) {
                a.await(0, "[mcd] 준비됐다", Duration.ofMinutes(1));
                try (Bot alice = Bot.joinViaRelay("alice", RELAY_A); Bot bob = Bot.joinViaRelay("bob", RELAY_A)) {
                    a.await(0, BECAME_HOST + "(epoch 2)", Duration.ofSeconds(10));
                    a.sendAndAwait("give alice minecraft:diamond 3", "Gave 3");
                    assertEquals("diamond:3", alice.awaitInventory("diamond:3"));

                    int markB = b.mark();
                    a.send("stop");
                    assertEquals("localhost:" + RELAY_B, alice.awaitTransfer());
                    assertEquals("localhost:" + RELAY_B, bob.awaitTransfer());
                    a.await(0, "[mcd] 월드를 올리고 서버를 넘겼다.", Duration.ofMinutes(1));
                    b.await(markB, BECAME_HOST + "(epoch 3)", Duration.ofSeconds(30));

                    assertEquals("diamond:3", alice.awaitInventory("diamond:3"), "넘긴 월드에서 인벤토리가 이어져야 한다");
                    assertTrue(a.printed("Block Interaction Range for entity alice set to 0.0"), "넘기는 동안 플레이어를 얼려야 한다");
                    // movement_speed는 불러올 때 걷기 속도로 다시 정해지므로, 그대로 저장되는 속성으로 확인한다.
                    String reach = b.sendAndAwait("attribute alice minecraft:block_interaction_range base get", "Block Interaction Range");
                    assertTrue(reach.contains(" 4.5"), "얼린 속성이 넘긴 월드에 남으면 안 된다: " + reach);
                    String players = b.sendAndAwait("list", "players online");
                    assertTrue(players.contains("alice") && players.contains("bob"), players);
                    assertTrue(a.waitForExit(Duration.ofSeconds(30)));
                }
            }
            assertTrue(branches(bare).stream().noneMatch(n -> n.startsWith("refs/heads/recovery/")), "넘긴 뒤의 잔여 변경이 recovery로 가면 안 된다");

            // 나갔던 A를 다시 켜면 깨끗하게 B를 따라간다.
            try (McdNode a2 = McdNode.play("A", remote, tmp.resolve("A"), RELAY_A, 60, RELAY_B)) {
                a2.await(0, "[mcd] 지금 호스트는 B이다", Duration.ofMinutes(1));
                assertTrue(branches(bare).stream().noneMatch(n -> n.startsWith("refs/heads/recovery/")));
                a2.send("stop");
            }
            int m = b.mark();
            b.send("stop");
            b.await(m, "[mcd] 월드를 올리고", Duration.ofMinutes(2));
        }
    }

    private static List<String> branches(Path bare) throws Exception {
        try (Git git = Git.open(bare.toFile())) {
            return git.getRepository().getRefDatabase().getRefsByPrefix("refs/heads/").stream().map(Ref::getName).toList();
        }
    }
}
