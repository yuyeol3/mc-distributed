package mcd.server;

import mcd.e2e.Bot;
import mcd.lock.HostLock;
import mcd.world.WorldTemplate;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실험 E1′의 입력: 실제 Paper와 봇으로 플레이한 세션을 스냅샷 20개짜리 bare repo로 남긴다.
 * 실행: ./gradlew paperTest --tests '*RecordSessionExperiment*' -Dmcd.session=tuning (또는 validation)
 * 결과: nodes/e1/<session>.git. 같은 이름이 이미 있으면 실패한다.
 */
@Tag("paper")
@EnabledIfSystemProperty(named = "mcd.session", matches = "tuning|validation")
class RecordSessionExperiment {

    private static final Path PAPER_JAR = Path.of("nodes/_cache/paper-26.1.2-74.jar");

    @Test
    void recordSession() throws Exception {
        String session = System.getProperty("mcd.session");
        // 조정용과 검증용은 이동 방향, 간격, 건축 크기를 다르게 해서 같은 패턴에 맞춘 결론이 되지 않게 한다.
        boolean tuning = session.equals("tuning");
        int step = tuning ? 400 : 250;
        int build = tuning ? 3 : 6;

        Path base = Path.of("nodes/e1").toAbsolutePath();
        Path bare = base.resolve(session + ".git");
        assertTrue(!Files.exists(bare), bare + "가 이미 있다");
        Files.createDirectories(base);
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        Path seed = base.resolve(session + "-seed");
        try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(seed.toFile()).call()) {
            WorldTemplate.writeInitialFiles(seed, false);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setSign(false).call();
            git.push().call();
        }

        Path hostDir = base.resolve(session + "-host");
        try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(hostDir.toFile()).call()) {
            WorldTemplate.acceptEula(hostDir);
            HostLock lock = new HostLock(git, "rec", InstantSource.system(), Duration.ofMinutes(10), Duration.ofMinutes(2), null);
            assertTrue(lock.tryAcquire("localhost:25631").acquired());
            try (PaperServer server = PaperServer.start(hostDir, PAPER_JAR, 25631, Duration.ofMinutes(3), null);
                 Bot bot = Bot.join("recbot", 25631)) {
                server.command("gamemode creative recbot");
                for (int i = 0; i < 20; i++) {
                    int p = (i + 1) * step;
                    server.command(tuning ? "tp recbot " + p + " 150 0" : "tp recbot 0 150 " + p);
                    Thread.sleep(5000);
                    String at = tuning ? p + " 140 0" : "0 140 " + p;
                    server.command("execute positioned " + at + " run fill ~-" + build + " ~ ~-" + build + " ~" + build + " ~" + build + " ~" + build + " minecraft:stone");
                    Thread.sleep(1000);

                    server.pauseSavingAndFlush();
                    long t0 = System.nanoTime();
                    lock.commitWorld(session + " snapshot " + i);
                    long commitMs = (System.nanoTime() - t0) / 1_000_000;
                    server.resumeSaving();
                    lock.pushCommitted();
                    System.out.println("record " + session + " round " + i + ": live commit " + commitMs + "ms");
                }
                bot.quit();
                server.stop(Duration.ofMinutes(2));
            }
            lock.release();
        }
    }
}
