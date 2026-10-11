package mcd.server;

import mcd.world.WorldTemplate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcd가 기대는 Paper 동작(로그 문구, 콘솔 명령, 종료 코드)을 빌드별로 확인한다. 봇 없이 콘솔만 쓰므로 테스트 봇이
 * 지원하지 않는 새 버전도 확인할 수 있다. 새 Paper 버전을 지원 범위에 넣기 전에 돌린다.
 * 실행: ./gradlew paperTest --tests '*PaperCompatibilityTest*' -Dmcd.paperBuild=26.2-133
 */
@Tag("paper")
class PaperCompatibilityTest {

    private static final Path CACHE = Path.of("nodes/_cache");
    private static final Duration START = Duration.ofMinutes(4);
    private static final PaperBuild UPGRADE_FROM = PaperBuild.parse("26.1.2-74");

    @TempDir
    Path tmp;

    @Test
    void consoleBehaviorsMcdReliesOn() throws Exception {
        PaperBuild build = target();
        Path jar = PaperDistribution.official().ensureDownloaded(CACHE, build);
        Path dir = serverDir("fresh");

        try (PaperServer paper = PaperServer.start(dir, jar, 25951, START, null)) {
            assertEquals(0, paper.onlinePlayers(), "접속자 수 문구(There are N of a max ...)를 읽을 수 있어야 한다");
            paper.pauseSavingAndFlush(); // save-off, save-all flush의 응답 문구
            paper.resumeSaving();         // save-on의 응답 문구
            // 호스트 이관에 쓰는 transfer 명령이 있어야 한다. 대상이 없을 때의 응답으로 확인한다.
            paper.commandAndAwait("transfer localhost 25565 nobody", "No player was found");
            paper.stop(Duration.ofMinutes(2));
            assertTrue(paper.exitedCleanly(), "stop으로 끝나면 종료 코드가 0이어야 한다");
        }
        assertTrue(Files.readString(dir.resolve("bukkit.yml")).contains("autosave: 0"), "자동 저장을 끈 설정이 유지돼야 한다");
    }

    /** 이전에 쓰던 빌드(26.1.2)로 만든 월드를 대상 빌드로 열었을 때 에러 없이 업그레이드되는지. */
    @Test
    void worldCreatedOnPreviousBuildOpensOnTargetBuild() throws Exception {
        PaperBuild build = target();
        if (build.compareTo(UPGRADE_FROM) <= 0) return;
        Path dir = serverDir("upgrade");
        Path oldJar = PaperDistribution.official().ensureDownloaded(CACHE, UPGRADE_FROM);
        try (PaperServer paper = PaperServer.start(dir, oldJar, 25952, START, null)) {
            paper.stop(Duration.ofMinutes(2));
        }
        assertTrue(Files.exists(dir.resolve("world").resolve("level.dat")));

        Path newJar = PaperDistribution.official().ensureDownloaded(CACHE, build);
        try (PaperServer paper = PaperServer.start(dir, newJar, 25952, START, null)) {
            paper.pauseSavingAndFlush();
            paper.resumeSaving();
            paper.stop(Duration.ofMinutes(2));
            List<String> errors = paper.lines().stream()
                    .filter(l -> l.contains("ERROR") || l.contains("Exception") || l.toLowerCase().contains("corrupt")).toList();
            assertEquals(List.of(), errors, "업그레이드한 월드를 열 때 에러가 없어야 한다");
            assertTrue(paper.exitedCleanly());
        }
        assertFalse(Files.exists(dir.resolve("world").resolve("session.lock.broken")));
    }

    private Path serverDir(String name) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir);
        WorldTemplate.writeInitialFiles(dir, false);
        WorldTemplate.acceptEula(dir);
        return dir;
    }

    private static PaperBuild target() {
        String property = System.getProperty("mcd.paperBuild");
        return property == null ? PaperDistribution.DEFAULT_BUILD : PaperBuild.parse(property);
    }
}
