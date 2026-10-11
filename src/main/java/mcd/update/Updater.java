package mcd.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GitHub 릴리스에서 새 mcd를 받아 설치 폴더를 바꾼다. 실행 중인 exe, jar, Java 런타임은 Windows에서 덮어쓸 수
 * 없으므로, 받은 파일을 임시 폴더에 풀어 두고 PowerShell 스크립트를 띄운 뒤 mcd는 정상 종료한다. 스크립트는 그
 * 설치 폴더의 mcd가 모두 끝나기를 기다렸다가 파일을 바꾸고, 원하면 mcd를 다시 켠다.
 *
 * <p>받은 zip은 릴리스에 함께 올린 SHA-256으로 검증한다. 같은 곳(GitHub)에서 오므로 다운로드가 깨지지 않았는지만
 * 보장하고, 누가 만든 파일인지까지는 보장하지 않는다(코드 서명은 하지 않음).
 */
public final class Updater {

    public static final String API = "https://api.github.com/repos/yuyeol3/mc-distributed";
    static final String ZIP = "mcd-windows.zip";
    static final String SHA = ZIP + ".sha256";

    public record Release(AppVersion version, String tag, String zipUrl, String shaUrl) {}

    private final String api;
    private final Path workDir;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    /** api는 테스트에서 로컬 서버로 바꾸기 위한 것이다. workDir에 받은 파일과 스크립트를 둔다. */
    public Updater(String api, Path workDir) {
        this.api = api;
        this.workDir = workDir;
    }

    /** 시스템 속성 mcd.releasesApi로 릴리스 주소를 바꿀 수 있다(배포판 시험용). */
    public static Updater official() {
        return new Updater(System.getProperty("mcd.releasesApi", API),
                Path.of(System.getProperty("java.io.tmpdir"), "mcd-update"));
    }

    /** 배포판(jpackage)으로 실행 중이면 설치 폴더(mcd.exe가 있는 곳). 개발 중 실행이면 비어 있다. */
    public static Optional<Path> installDir() {
        String exe = System.getProperty("jpackage.app-path");
        return exe == null ? Optional.empty() : Optional.of(Path.of(exe).toAbsolutePath().getParent());
    }

    /** current보다 새 릴리스. 초안은 빼고 미리보기(pre-release)는 넣는다. zip과 해시가 모두 첨부된 것만 본다. */
    public Optional<Release> newerThan(AppVersion current) throws IOException, InterruptedException {
        Release best = null;
        for (Object item : (List<?>) Json.parse(get(api + "/releases?per_page=20"))) {
            Map<?, ?> release = (Map<?, ?>) item;
            if (Boolean.TRUE.equals(release.get("draft"))) continue;
            String tag = (String) release.get("tag_name");
            Optional<AppVersion> version = AppVersion.parse(tag);
            String zip = null;
            String sha = null;
            for (Object a : (List<?>) release.get("assets")) {
                Map<?, ?> asset = (Map<?, ?>) a;
                if (ZIP.equals(asset.get("name"))) zip = (String) asset.get("browser_download_url");
                if (SHA.equals(asset.get("name"))) sha = (String) asset.get("browser_download_url");
            }
            if (version.isEmpty() || zip == null || sha == null) continue;
            if (best == null || version.get().compareTo(best.version()) > 0) {
                best = new Release(version.get(), tag, zip, sha);
            }
        }
        return best != null && best.version().compareTo(current) > 0 ? Optional.of(best) : Optional.empty();
    }

    /** 릴리스를 받아 검증하고 풀어서, 새 설치 폴더 내용(mcd.exe가 있는 폴더)을 돌려준다. */
    public Path download(Release release) throws IOException, InterruptedException {
        String expected = get(release.shaUrl()).trim().split("\\s+")[0].toLowerCase();
        Path dir = workDir.resolve("v" + release.version());
        deleteRecursively(dir);
        Files.createDirectories(dir);
        Path zip = dir.resolve(ZIP);
        HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(release.zipUrl())).build(),
                HttpResponse.BodyHandlers.ofFile(zip));
        if (response.statusCode() != 200) throw new IOException("업데이트를 받지 못했다: HTTP " + response.statusCode());
        String actual = sha256(zip);
        if (!actual.equals(expected)) {
            Files.delete(zip);
            throw new IOException("받은 업데이트의 해시가 다르다(" + actual + "). 다시 시도한다.");
        }
        Path extracted = dir.resolve("files");
        unzip(zip, extracted);
        Path app = extracted.resolve("mcd");
        if (!Files.exists(app.resolve("mcd.exe"))) throw new IOException("업데이트에 mcd.exe가 없다");
        return app;
    }

    /**
     * 설치 폴더를 바꿀 스크립트를 띄운다. 스크립트는 그 폴더의 mcd가 모두 끝나기를 기다린다. 이 메서드를 부른 뒤
     * mcd는 스스로 끝나야 한다(호스트라면 평소처럼 저장, 반납, 이관을 거친다). restart가 있으면 바꾼 뒤 다시 켠다.
     */
    public void launchReplacer(Path newApp, Path installDir, Optional<Path> restart) throws IOException {
        if (!Files.exists(installDir.resolve("mcd.exe")) || !Files.exists(installDir.resolve("app").resolve("mcd.cfg"))) {
            throw new IllegalStateException(installDir + "는 mcd 설치 폴더가 아니다");
        }
        Files.createDirectories(workDir);
        Path script = workDir.resolve("apply-update.ps1");
        Files.writeString(script, replacerScript(newApp, installDir, restart, workDir.resolve("apply-update.log")), StandardCharsets.UTF_8);
        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /**
     * 그 폴더에서 실행된 프로세스가 모두 끝나기를 기다린 뒤(최대 10분) robocopy로 새 파일을 덮어쓴다. 사용자가 같은
     * 폴더에 둔 다른 파일을 지우지 않도록 /MIR이 아니라 /E로 복사한다(옛 jar가 남아도 mcd.cfg가 정확한 목록을 쓴다).
     */
    static String replacerScript(Path newApp, Path installDir, Optional<Path> restart, Path log) {
        String install = quote(installDir.toString());
        return """
                $ErrorActionPreference = 'Continue'
                $log = %s
                "mcd 업데이트 시작 $(Get-Date)" | Out-File -FilePath $log -Encoding utf8
                $deadline = (Get-Date).AddMinutes(10)
                while ((Get-Date) -lt $deadline) {
                    $running = Get-Process | Where-Object { $_.Path -and $_.Path.StartsWith(%s, [StringComparison]::OrdinalIgnoreCase) }
                    if (-not $running) { break }
                    Start-Sleep -Milliseconds 500
                }
                Start-Sleep -Seconds 1
                & robocopy %s %s /E /R:10 /W:1 /NP /NFL /NDL | Out-File -FilePath $log -Append -Encoding utf8
                $code = $LASTEXITCODE
                "robocopy 종료 코드 $code" | Out-File -FilePath $log -Append -Encoding utf8
                if ($code -lt 8) {
                    %s
                }
                """.formatted(quote(log.toString()), install, quote(newApp.toString()), install,
                restart.map(r -> "Start-Process -FilePath " + quote(r.toString())).orElse("# 다시 켜지 않는다"));
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/vnd.github+json").build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("업데이트 정보를 받지 못했다: HTTP " + response.statusCode() + " " + url);
        return response.body();
    }

    /** zip 밖으로 빠져나가는 경로(zip slip)는 거부한다. */
    static void unzip(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("zip 안의 경로가 이상하다: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
