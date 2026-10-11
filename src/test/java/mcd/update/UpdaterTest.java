package mcd.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    @TempDir
    Path tmp;

    private HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private String releasesJson = "[]";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void picksNewestNonDraftReleaseThatHasBothAssets() throws Exception {
        releasesJson = "[" + release("v0.4.0", true, true) + "," + release("v0.3.0", false, false) + ","
                + release("v0.2.1", false, true) + "," + release("v0.1.0", false, true) + "]";
        Updater updater = new Updater(url(), tmp);

        Optional<Updater.Release> newer = updater.newerThan(AppVersion.parse("0.2.0").get());
        assertEquals("v0.2.1", newer.orElseThrow().tag(), "초안(0.4.0)과 첨부가 빠진 릴리스(0.3.0)는 건너뛴다");
        assertEquals(Optional.empty(), updater.newerThan(AppVersion.parse("0.2.1").get()));
    }

    @Test
    void downloadsVerifiesAndExtractsTheApp() throws Exception {
        byte[] zip = appZip("new");
        files.put("/download/v0.2.1/mcd-windows.zip", zip);
        files.put("/download/v0.2.1/mcd-windows.zip.sha256", (sha256(zip) + "  mcd-windows.zip\n").getBytes(StandardCharsets.UTF_8));
        releasesJson = "[" + release("v0.2.1", false, true) + "]";
        Updater updater = new Updater(url(), tmp.resolve("work"));

        Path app = updater.download(updater.newerThan(AppVersion.parse("0.2.0").get()).orElseThrow());
        assertEquals("new", Files.readString(app.resolve("app").resolve("version.txt")));
        assertTrue(Files.exists(app.resolve("mcd.exe")));
    }

    @Test
    void rejectsDownloadWhoseHashDoesNotMatch() throws Exception {
        files.put("/download/v0.2.1/mcd-windows.zip", appZip("new"));
        files.put("/download/v0.2.1/mcd-windows.zip.sha256", ("0".repeat(64) + "  mcd-windows.zip").getBytes(StandardCharsets.UTF_8));
        releasesJson = "[" + release("v0.2.1", false, true) + "]";
        Updater updater = new Updater(url(), tmp.resolve("work"));

        Updater.Release r = updater.newerThan(AppVersion.parse("0.2.0").get()).orElseThrow();
        assertThrows(IOException.class, () -> updater.download(r));
    }

    @Test
    void rejectsZipEntriesThatEscapeTheTarget() throws Exception {
        Path zip = tmp.resolve("evil.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../outside.txt"));
            out.write(1);
        }
        assertThrows(IOException.class, () -> Updater.unzip(zip, tmp.resolve("target")));
        assertFalse(Files.exists(tmp.resolve("outside.txt")));
    }

    /**
     * 교체 스크립트를 실제로 돌린다. 설치 폴더에서 실행 중인 프로세스가 끝나기 전에는 바꾸지 않고, 끝나면 바꾼 뒤
     * 새 설치 폴더의 프로그램을 다시 켠다. 사용자가 그 폴더에 둔 파일은 지우지 않는다.
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void replacerWaitsForRunningAppThenReplacesFilesAndRestarts() throws Exception {
        Path install = tmp.resolve("install");
        writeApp(install, "old");
        Files.writeString(install.resolve("notes.txt"), "사용자 파일");
        // 설치 폴더에서 실행 중인 mcd 대신: ping을 복사해 7초쯤 돌린다. 스크립트가 뜨고 복사하는 데 걸리는 시간(약 2초)보다
        // 충분히 길어야, 기다리지 않고 바꾸는 버그를 잡을 수 있다.
        Files.copy(Path.of(System.getenv("SystemRoot"), "System32", "PING.EXE"), install.resolve("running.exe"));
        Process running = new ProcessBuilder(install.resolve("running.exe").toString(), "-n", "8", "127.0.0.1")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();

        Path newApp = tmp.resolve("staged").resolve("mcd");
        writeApp(newApp, "new");
        Files.writeString(newApp.resolve("restart.cmd"), "@echo restarted> \"%~dp0restarted.txt\"\r\n");

        new Updater(url(), tmp.resolve("work")).launchReplacer(newApp, install, Optional.of(install.resolve("restart.cmd")));

        Thread.sleep(4000);
        assertTrue(running.isAlive());
        assertEquals("old", Files.readString(install.resolve("app").resolve("version.txt")), "실행 중에는 바꾸면 안 된다");

        running.waitFor(30, TimeUnit.SECONDS);
        Path restarted = install.resolve("restarted.txt");
        for (int i = 0; i < 60 && !Files.exists(restarted); i++) Thread.sleep(500);
        assertEquals("new", Files.readString(install.resolve("app").resolve("version.txt")));
        assertTrue(Files.exists(restarted), "바꾼 뒤 다시 켜야 한다");
        assertEquals("사용자 파일", Files.readString(install.resolve("notes.txt")));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body = path.equals("/releases") ? releasesJson.getBytes(StandardCharsets.UTF_8) : files.get(path);
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String release(String tag, boolean draft, boolean withAssets) {
        String assets = withAssets
                ? "{\"name\":\"mcd-windows.zip\",\"browser_download_url\":\"" + url() + "/download/" + tag + "/mcd-windows.zip\"},"
                + "{\"name\":\"mcd-windows.zip.sha256\",\"browser_download_url\":\"" + url() + "/download/" + tag + "/mcd-windows.zip.sha256\"}"
                : "";
        return "{\"tag_name\":\"" + tag + "\",\"draft\":" + draft + ",\"prerelease\":true,\"assets\":[" + assets + "]}";
    }

    private static void writeApp(Path dir, String version) throws IOException {
        Files.createDirectories(dir.resolve("app"));
        Files.writeString(dir.resolve("mcd.exe"), "exe " + version);
        Files.writeString(dir.resolve("app").resolve("mcd.cfg"), "cfg");
        Files.writeString(dir.resolve("app").resolve("version.txt"), version);
    }

    private static byte[] appZip(String version) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String[] e : new String[][] {{"mcd/mcd.exe", "exe"}, {"mcd/app/mcd.cfg", "cfg"}, {"mcd/app/version.txt", version}}) {
                zip.putNextEntry(new ZipEntry(e[0]));
                zip.write(e[1].getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
