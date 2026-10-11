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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** mcd만 죽었을 때 Paper가 남아서 락 없이 계속 돌지 않는지, 다시 켰을 때 플레이 기록이 이어지는지 확인한다. */
@Tag("paper")
class McdCrashTest {

    @TempDir
    Path tmp;

    @Test
    void paperStopsWhenMcdDiesAndRestartKeepsPlay() throws Exception {
        assumeTrue(Files.exists(Path.of("nodes/_cache/paper-26.1.2-74.jar")), "Paper jar가 없다");
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        InitCommand.run(tmp.resolve("init"), remote, false, java.util.Optional.empty(), Bot.PAPER_BUILD, null);
        Path dirA = tmp.resolve("A");

        try (McdNode a = McdNode.play("A", remote, dirA, 25631, 300)) {
            a.await(0, "[mcd] 준비됐다", Duration.ofMinutes(1));
            try (Bot alice = Bot.joinViaRelay("alice", 25631)) {
                assertTrue(a.printed("[mcd] 이 PC가 호스트가 됐다(epoch 1)"));
                // 스냅샷(TTL 300초 = 5분 주기) 전에 준 아이템이라 원격에는 아직 없다.
                a.sendAndAwait("give alice minecraft:diamond 7", "Gave 7");
                alice.quit();
            }
            List<ProcessHandle> paper = a.killMcdOnly();
            assertFalse(paper.isEmpty());
            for (ProcessHandle p : paper) {
                p.onExit().get(90, TimeUnit.SECONDS);
            }
            assertTrue(paper.stream().noneMatch(ProcessHandle::isAlive), "mcd가 죽으면 Paper도 내려가야 한다");
        }

        try (McdNode a2 = McdNode.play("A", remote, dirA, 25631, 300)) {
            a2.await(0, "[mcd] 준비됐다", Duration.ofMinutes(1));
            try (Bot alice = Bot.joinViaRelay("alice", 25631)) {
                assertTrue(a2.printed("[mcd] 이 PC가 호스트가 됐다(epoch 2)"), "크래시 전 자기 락을 다시 잡아야 한다");
                assertEquals("diamond:7", alice.awaitInventory("diamond:7"), "Paper가 종료하며 저장한 플레이 기록이 이어져야 한다");
                alice.quit();
            }
            int mark = a2.mark();
            a2.send("stop");
            a2.await(mark, "[mcd] 월드를 올리고", Duration.ofMinutes(2));
        }
    }
}
