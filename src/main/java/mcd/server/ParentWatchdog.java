package mcd.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Paper JVM에 붙는 Java 에이전트. 부모(mcd)가 죽으면 Paper를 정상 종료시킨다.
 * Paper는 stdin이 닫혀도 계속 돌기 때문에(직접 확인), 이것이 없으면 mcd가 크래시했을 때 락 갱신 없이 서버만 남는다.
 * 이 클래스는 Paper JVM에서 혼자 로드되므로 다른 클래스에 의존하면 안 된다.
 */
public final class ParentWatchdog {

    private static final String CLASS_FILE = "mcd/server/ParentWatchdog.class";

    private ParentWatchdog() {}

    public static void premain(String args) {
        ProcessHandle.current().parent().ifPresent(parent -> parent.onExit().thenRun(() -> {
            System.out.println("[mcd] mcd 프로세스가 끝나서 서버를 내립니다.");
            terminate(0);
        }));
        if (args != null && !args.isBlank()) {
            int port = Integer.parseInt(args);
            long first = permitDeadline(port);
            if (first - System.nanoTime() <= 0) terminate(75);
            Thread.ofPlatform().daemon().name("mcd-permit-watchdog").start(() -> {
                long deadline = first;
                while (true) {
                    long next = permitDeadline(port);
                    if (next != 0) deadline = Math.min(next, System.nanoTime() + 20_000_000_000L);
                    if (deadline - System.nanoTime() <= 0) {
                        System.out.println("[mcd] writer 실행 허가가 만료되어 서버를 내립니다.");
                        terminate(75);
                        return;
                    }
                    try { Thread.sleep(250); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            });
        }
    }

    private static long permitDeadline(int port) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            socket.setSoTimeout(500);
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < 32; i++) {
                int b = socket.getInputStream().read();
                if (b == '\n') return start + Math.clamp(Long.parseLong(value.toString()), 0, 20_000) * 1_000_000L;
                if (b < 0) return 0;
                value.append((char) b);
            }
        } catch (IOException | NumberFormatException ignored) { }
        return 0;
    }

    private static void terminate(int code) {
        Thread.ofPlatform().daemon().name("mcd-force-exit").start(() -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
            Runtime.getRuntime().halt(code);
        });
        System.exit(code);
    }

    /** 이 클래스 하나만 담은 에이전트 jar를 임시 파일로 만든다. */
    static Path createAgentJar() throws IOException {
        Path jar = Files.createTempFile("mcd-watchdog", ".jar");
        jar.toFile().deleteOnExit();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), ParentWatchdog.class.getName());
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out, manifest);
             InputStream classBytes = ParentWatchdog.class.getClassLoader().getResourceAsStream(CLASS_FILE)) {
            if (classBytes == null) {
                throw new IOException(CLASS_FILE + "를 찾지 못했습니다");
            }
            jarOut.putNextEntry(new JarEntry(CLASS_FILE));
            classBytes.transferTo(jarOut);
            jarOut.closeEntry();
        }
        return jar;
    }
}
