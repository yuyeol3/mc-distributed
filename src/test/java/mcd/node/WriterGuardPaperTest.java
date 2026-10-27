package mcd.node;

import mcd.lock.HostLock;
import mcd.server.*;
import mcd.world.*;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

@Tag("paper")
class WriterGuardPaperTest {
    @TempDir Path tmp;

    @Test
    void oldPaperStopsAfterAnotherNodeWinsEvenWhenOldMcdIsAlive() throws Exception {
        PaperBuild build = PaperBuild.parse("26.1.2-74");
        Path jar = PaperDistribution.official().ensureDownloaded(Path.of("nodes/_cache"), build);
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        WorldRepository.create(tmp.resolve("A"), remote, false, Optional.empty(), build, null);
        WorldTemplate.acceptEula(tmp.resolve("A"));
        try (Git gitA = Git.open(tmp.resolve("A").toFile());
             Git gitB = WorldRepository.openOrClone(tmp.resolve("B"), remote, null)) {
            Timing timing = Timing.fromTtl(Timing.DEFAULT_TTL);
            HostLock a = new HostLock(gitA, "A", InstantSource.system(), timing.ttl(), timing.margin(), null);
            HostLock b = new HostLock(gitB, "B", InstantSource.system(), timing.ttl(), timing.margin(), null);
            var original = a.tryAcquire("a:1", "a:2", null).lock();
            try (WriterGuard guard = new WriterGuard(a);
                 PaperServer paper = PaperServer.launch(tmp.resolve("A"), jar, 25893, null, guard.port())) {
                assertTrue(paper.awaitReady(Duration.ofMinutes(3)));
                assertTrue(b.tryAcquire("b:1", "b:2", original).acquired());
                long start = System.nanoTime();
                long deadline = start + Duration.ofSeconds(15).toNanos();
                while (paper.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
                assertFalse(paper.isAlive(), "다른 epoch가 공개되면 기존 Paper는 새 writer 대기 30초 안에 멈춰야 한다");
                assertFalse(guard.valid());
                System.out.println("OLD_WRITER_STOP_MS=" + Duration.ofNanos(System.nanoTime() - start).toMillis());
            }
        }
    }
}
