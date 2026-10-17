package mcd.cli;

import mcd.e2e.Bot;
import mcd.e2e.McdNode;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 서버 목록 확인(ping)은 호스트를 띄우지 않고 중계가 답한다. 새 폴더의 첫 호스트도 중계가 기동이 끝날 때까지
 * 클라이언트를 붙잡아 두어 한 번의 접속으로 들어간다. 노드를 열 때 Paper를 미리 패치하므로 첫 기동도 평소 기동
 * 시간만 걸린다(출력의 first join 시간). 30초를 넘는 붙잡기 자체는 RelayTest가 확인한다.
 * 주의: 봇(minecraft-protocol)은 로그인 단계에 자체 시간 제한이 없어서, 바닐라 클라이언트의 30초 제한까지
 * 넘는지는 진짜 클라이언트로 확인해야 한다.
 */
@Tag("paper")
class FirstJoinHoldTest {

    private static final int RELAY = 25941;

    @TempDir
    Path tmp;

    @Test
    void pingDoesNotStartHostAndFirstJoinIsHeldThroughSlowFirstBoot() throws Exception {
        assumeTrue(Files.exists(Path.of("nodes/_cache/paper-26.1.2-74.jar")), "Paper jar가 없다");
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        InitCommand.run(tmp.resolve("init"), remote, false, Optional.empty(), Bot.PAPER_BUILD, null);

        try (McdNode a = McdNode.play("A", remote, tmp.resolve("A"), RELAY, 600)) {
            a.await(0, "[mcd] 준비됐습니다", Duration.ofMinutes(1));

            String idle = Bot.ping(RELAY);
            assertTrue(idle.startsWith("PING") && idle.contains("mcd"), "중계가 직접 답해야 한다: " + idle);
            Thread.sleep(2000);
            assertFalse(a.printed("호스트가 됐습니다"), "서버 목록 확인만으로 호스트가 되면 안 된다");

            long t0 = System.nanoTime();
            try (Bot alice = Bot.join("alice", RELAY, Duration.ofMinutes(4))) {
                long seconds = (System.nanoTime() - t0) / 1_000_000_000L;
                System.out.println("first join took " + seconds + "s");
                assertTrue(a.printed("[mcd] 이 PC가 호스트가 됐습니다"));

                String hosting = Bot.ping(RELAY);
                assertTrue(hosting.startsWith("PING") && !hosting.contains("mcd") && hosting.contains("\"online\":1"),
                        "호스트가 있으면 Paper가 답해야 한다: " + hosting);
                alice.quit();
            }
            int m = a.mark();
            a.send("stop");
            a.await(m, "[mcd] 월드를 올리고", Duration.ofMinutes(2));
        }
    }
}
