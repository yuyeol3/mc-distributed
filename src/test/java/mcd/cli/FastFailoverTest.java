package mcd.cli;

import mcd.e2e.Bot;
import mcd.e2e.McdNode;
import mcd.lock.LockState;
import mcd.net.HealthCheck;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.Proxy;
import java.nio.file.*;
import java.time.Duration;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("paper")
class FastFailoverTest {
    @TempDir Path tmp;

    @Test
    void healthFailureReplacesWriterWithoutWaitingTenMinuteLease() throws Exception {
        assumeTrue(Files.exists(Path.of("nodes/_cache/paper-26.1.2-74.jar")));
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        InitCommand.run(tmp.resolve("seed"), remote, false, Optional.empty(), Bot.PAPER_BUILD, null);
        try (McdNode a = McdNode.play("A", remote, tmp.resolve("A"), 25711, 600);
             McdNode b = McdNode.play("B", remote, tmp.resolve("B"), 25721, 600)) {
            a.await(0, "[mcd] 준비됐습니다", Duration.ofMinutes(2));
            b.await(0, "[mcd] 준비됐습니다", Duration.ofMinutes(2));
            try (Bot alice = Bot.joinViaRelay("alice", 25711)) {
                // B도 A의 소유권을 관찰하고 접속 경로를 확인한다.
                try (Bot bob = Bot.joinViaRelay("bob", 25721)) { bob.quit(); }
                a.killHard();
                long failure = System.nanoTime();
                try (Bot replacement = Bot.join("bob", 25721, Duration.ofMinutes(2))) {
                    long millis = Duration.ofNanos(System.nanoTime() - failure).toMillis();
                    assertTrue(b.printed("writer 응답이 없어 락을 인수했습니다"));
                    assertTrue(millis >= 30_000 && millis < 120_000, "복구 시간: " + millis + "ms");
                    System.out.println("FAST_FAILOVER_READY_MS=" + millis + " (TTL=600000ms)");
                    replacement.quit();
                }
                try (Git reader = Git.cloneRepository().setURI(remote).setDirectory(tmp.resolve("reader").toFile()).call()) {
                    assertEquals("B", LockState.read(tmp.resolve("reader")).owner());
                    LockState taken = LockState.read(tmp.resolve("reader"));
                    assertEquals(2, taken.epoch());
                    // 인수 대기(30초)가 관찰 주기(30초)보다 길어서, 그동안 돈 관찰 루프가 호스트 상태를 지우면 안 된다.
                    assertTrue(HealthCheck.probe(taken.healthAddress(), taken, Proxy.NO_PROXY, Duration.ofSeconds(2)),
                            "인수한 호스트도 상태 확인에 응답해야 한다");
                }
            }
        }
    }

}
