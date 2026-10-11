package mcd.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperDistributionTest {

    private static final byte[] JAR = "fake paper jar".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path cache;

    @Test
    void downloadsTheWorldsBuildAndVerifiesItsHash() throws Exception {
        try (FakePaperApi api = new FakePaperApi().build("26.1.2-80", JAR)) {
            Path jar = new PaperDistribution(api.url()).ensureDownloaded(cache, PaperBuild.parse("26.1.2-80"));

            assertEquals("paper-26.1.2-80.jar", jar.getFileName().toString());
            assertArrayEquals(JAR, Files.readAllBytes(jar));
        }
    }

    @Test
    void cachedJarIsUsedWithoutNetwork() throws Exception {
        PaperBuild build = PaperBuild.parse("26.1.2-80");
        try (FakePaperApi api = new FakePaperApi().build("26.1.2-80", JAR)) {
            new PaperDistribution(api.url()).ensureDownloaded(cache, build);
        }
        // API 서버를 닫은 뒤에도 남겨 둔 해시로 확인하고 쓴다.
        Path jar = new PaperDistribution("http://127.0.0.1:9").ensureDownloaded(cache, build);
        assertArrayEquals(JAR, Files.readAllBytes(jar));
    }

    /** 한 PC의 mcd 두 개(예: GUI와 CLI)가 같은 캐시에 같은 빌드를 동시에 받아도 둘 다 성공해야 한다. */
    @Test
    void concurrentDownloadsOfTheSameBuildBothSucceed() throws Exception {
        byte[] big = new byte[8 * 1024 * 1024];
        new java.util.Random(1).nextBytes(big);
        PaperBuild build = PaperBuild.parse("26.2-133");
        try (FakePaperApi api = new FakePaperApi().build("26.2-133", big);
             var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<Path>> downloads = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                downloads.add(pool.submit(() -> new PaperDistribution(api.url()).ensureDownloaded(cache, build)));
            }
            for (var download : downloads) {
                assertArrayEquals(big, Files.readAllBytes(download.get()));
            }
        }
        try (var files = Files.list(cache)) {
            assertEquals(List.of(), files.filter(f -> f.toString().endsWith(".part")).toList(), "받는 중이던 파일이 남으면 안 된다");
        }
    }

    @Test
    void rejectsJarWhoseHashDoesNotMatch() throws Exception {
        try (FakePaperApi api = new FakePaperApi().build("26.1.2-80", JAR, "0".repeat(64), "STABLE")) {
            assertThrows(IOException.class, () -> new PaperDistribution(api.url()).ensureDownloaded(cache, PaperBuild.parse("26.1.2-80")));
            assertFalse(Files.exists(cache.resolve("paper-26.1.2-80.jar")));
        }
    }

    @Test
    void refusesBuildNeedingNewerJavaThanBundled() throws Exception {
        int tooNew = Runtime.version().feature() + 1;
        try (FakePaperApi api = new FakePaperApi().build("27.1-1", JAR).javaMinimum("27.1", tooNew)) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new PaperDistribution(api.url()).ensureDownloaded(cache, PaperBuild.parse("27.1-1")));
            assertTrue(e.getMessage().contains("Java " + tooNew), e.getMessage());
        }
    }

    @Test
    void latestStableBuildOfAVersion() throws Exception {
        try (FakePaperApi api = new FakePaperApi().build("26.2-3", JAR).build("26.2-7", JAR).build("26.1.2-99", JAR)) {
            assertEquals(PaperBuild.parse("26.2-7"), new PaperDistribution(api.url()).latestStable("26.2"));
        }
    }

    @Test
    void latestBuildMustBeStable() throws Exception {
        try (FakePaperApi api = new FakePaperApi().build("26.3-1", JAR, FakePaperApi.sha256(JAR), "BETA")) {
            assertThrows(IllegalStateException.class, () -> new PaperDistribution(api.url()).latestStable("26.3"));
        }
    }

    @Test
    void tooOldVersionIsRejectedAndUntestedVersionOnlyWarns() {
        assertThrows(IllegalStateException.class, () -> PaperDistribution.checkSupported(PaperBuild.parse("1.20.4-500"), m -> {}));

        List<String> warnings = new ArrayList<>();
        PaperDistribution.checkSupported(PaperBuild.parse("26.1.2-74"), warnings::add);
        assertEquals(List.of(), warnings);
        PaperDistribution.checkSupported(PaperBuild.parse("26.2-133"), warnings::add);
        assertEquals(List.of(), warnings);
        PaperDistribution.checkSupported(PaperBuild.parse("26.3-5"), warnings::add);
        assertEquals(1, warnings.size());
    }
}
