package mcd.cli;

import mcd.e2e.Bot;
import mcd.e2e.McdNode;
import mcd.world.NetworkConfig;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실험 E5: 월드에 tailnet 설정이 있으면 노드들이 tailnet에 붙고, 다른 노드의 중계는 호스트의 tailnet 주소로
 * 이어준다. 조정 서버는 테스트용 Headscale을 쓴다(계정 불필요).
 * 실행: MCD_TEST_CONTROL_URL=http://127.0.0.1:8080 MCD_TEST_AUTH_KEY=<키> ./gradlew paperTest --tests '*TailnetScenarioTest*'
 */
@Tag("paper")
@EnabledIfEnvironmentVariable(named = "MCD_TEST_AUTH_KEY", matches = ".+")
class TailnetScenarioTest {

    @TempDir
    Path tmp;

    @Test
    void otherNodeRelaysToHostOverTailnet() throws Exception {
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        NetworkConfig network = new NetworkConfig(System.getenv().getOrDefault("MCD_TEST_CONTROL_URL", ""), System.getenv("MCD_TEST_AUTH_KEY"));
        InitCommand.run(tmp.resolve("init"), remote, false, Optional.of(network), Bot.PAPER_BUILD, null);

        try (McdNode a = McdNode.play("A", remote, tmp.resolve("A"), 25711, 60);
             McdNode b = McdNode.play("B", remote, tmp.resolve("B"), 25721, 60)) {
            String addressA = a.await(0, "[mcd] tailnet 주소는", Duration.ofMinutes(1));
            b.await(0, "[mcd] tailnet 주소는", Duration.ofMinutes(1));
            a.await(0, "[mcd] 준비됐습니다", Duration.ofMinutes(1));
            b.await(0, "[mcd] 준비됐습니다", Duration.ofMinutes(1));
            assertTrue(addressA.contains("100."), "tailnet IP여야 한다: " + addressA);

            try (Bot alice = Bot.joinViaRelay("alice", 25711)) {
                assertTrue(a.printed("[mcd] 이 PC가 호스트가 됐습니다"));
                b.await(0, "[mcd] 지금 호스트는 A입니다", Duration.ofSeconds(30));
                // B는 tailnet을 거쳐 A의 health 포트를 5초마다 확인하고 3번 연속 실패하면 경고한다. 그보다 오래 지켜본다.
                Thread.sleep(Duration.ofSeconds(20));
                assertFalse(b.printed("writer 상태 확인이 연속 실패"), "B가 tailnet으로 A의 상태를 확인할 수 있어야 한다");
                assertFalse(a.printed("writer 상태 확인이 연속 실패"), "호스트가 자기 자신을 실패로 보면 안 된다");
                try (Bot bob = Bot.joinViaRelay("bob", 25721)) {
                    String players = a.sendAndAwait("list", "players online");
                    assertTrue(players.contains("alice") && players.contains("bob"), "B의 중계가 tailnet으로 A에 이어줘야 한다: " + players);
                    bob.quit();
                }
                alice.quit();
            }
            int m = a.mark();
            a.send("stop");
            b.send("stop");
            a.await(m, "[mcd] 월드를 올리고 락을 반납했습니다.", Duration.ofMinutes(2));
        }
    }
}
