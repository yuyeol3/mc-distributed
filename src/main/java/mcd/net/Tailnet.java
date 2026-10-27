package mcd.net;

import mcd.world.NetworkConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * mcd-net(Go, tsnet) 보조 프로그램을 띄워 tailnet에 붙는다. 친구는 Tailscale을 설치하거나 로그인하지 않는다.
 * 다른 PC로 나가는 연결은 보조 프로그램의 SOCKS5 프록시로, 들어오는 연결은 tailnet의 같은 포트에서
 * 이 PC의 로컬 포트로 넘어온다. mcd가 끝나면 보조 프로그램의 stdin이 닫혀 같이 끝난다.
 */
public final class Tailnet implements AutoCloseable {

    private final Process process;
    private final String ip;
    private final InetSocketAddress socks;

    private Tailnet(Process process, String ip, InetSocketAddress socks) {
        this.process = process;
        this.ip = ip;
        this.socks = socks;
    }

    /**
     * tailnet에 붙고 exposedPort를 이 PC의 127.0.0.1:exposedPort로 넘기도록 한다.
     * stateDir에는 노드 키가 저장되므로 월드 폴더(git 작업 트리) 밖이어야 한다.
     */
    public static Tailnet join(NetworkConfig config, String hostname, Path stateDir, int exposedPort, Consumer<String> log)
            throws IOException, InterruptedException, TimeoutException {
        return join(config, hostname, stateDir, exposedPort, 0, log);
    }

    public static Tailnet join(NetworkConfig config, String hostname, Path stateDir, int exposedPort, int healthPort, Consumer<String> log)
            throws IOException, InterruptedException, TimeoutException {
        Files.createDirectories(stateDir);
        List<String> cmd = new ArrayList<>(List.of(helperExecutable().toString(),
                "--auth-key", config.authKey(),
                "--hostname", hostname,
                "--state-dir", stateDir.toAbsolutePath().toString(),
                "--expose", exposedPort + "=127.0.0.1:" + exposedPort
                        + (healthPort == 0 ? "" : "," + healthPort + "=127.0.0.1:" + healthPort)));
        if (!config.controlUrl().isEmpty()) {
            cmd.addAll(List.of("--control-url", config.controlUrl()));
        }
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        CompletableFuture<String> ready = new CompletableFuture<>();
        // 프로세스 출력 읽기는 파일 I/O라 가상 스레드가 캐리어를 붙잡는다(CPU 2개인 VM에서 중계가 멈춘 원인). 플랫폼 스레드로 읽는다.
        Thread.ofPlatform().daemon().name("mcd-net-output").start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("READY ")) {
                        ready.complete(line);
                    } else if (!ready.isDone()) {
                        // 붙기 전의 출력(실패 원인 등)만 보여준다. 붙은 뒤의 tsnet 로그는 버린다.
                        log.accept("tailnet: " + line);
                    }
                }
            } catch (IOException ignored) {
                // 프로세스가 끝나면 스트림이 닫힌다.
            }
            ready.completeExceptionally(new IOException("mcd-net이 준비되기 전에 끝났습니다"));
        });
        try {
            String[] parts = ready.get(60, TimeUnit.SECONDS).split(" ");
            String socksAddr = parts[2];
            int colon = socksAddr.lastIndexOf(':');
            return new Tailnet(process, parts[1],
                    new InetSocketAddress(socksAddr.substring(0, colon), Integer.parseInt(socksAddr.substring(colon + 1))));
        } catch (java.util.concurrent.ExecutionException e) {
            process.destroyForcibly();
            throw new IOException("tailnet에 붙지 못했습니다", e.getCause());
        } catch (TimeoutException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    /** 이 PC의 tailnet IPv4. 다른 PC는 이 주소로 접속한다. */
    public String ip() {
        return ip;
    }

    /** 다른 PC의 tailnet 주소로 나가는 연결에 쓰는 프록시. */
    public Proxy proxy() {
        return new Proxy(Proxy.Type.SOCKS, socks);
    }

    @Override
    public void close() {
        process.destroy();
    }

    /**
     * 보조 프로그램 위치. 배포판과 installDist에서는 mcd.jar 옆에 있다. 개발 중에는 시스템 속성 mcd.netHelper로 지정한다.
     */
    static Path helperExecutable() throws IOException {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "mcd-net.exe" : "mcd-net";
        String override = System.getProperty("mcd.netHelper");
        if (override != null) return Path.of(override);
        try {
            Path codeSource = Path.of(Tailnet.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path candidate = (Files.isDirectory(codeSource) ? codeSource : codeSource.getParent()).resolve(name);
            if (Files.exists(candidate)) return candidate;
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        throw new IOException(name + "를 찾지 못했습니다. mcd.jar 옆에 두거나 -Dmcd.netHelper로 지정하세요.");
    }
}
