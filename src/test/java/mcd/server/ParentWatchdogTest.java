package mcd.server;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ParentWatchdogTest {
    @Test
    void childStopsWhenPermitServerDisappearsEvenWhileParentIsAlive() throws Exception {
        try (ServerSocket permits = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            Thread.ofPlatform().daemon().start(() -> {
                while (!permits.isClosed()) {
                    try (Socket socket = permits.accept()) { socket.getOutputStream().write("1000\n".getBytes()); }
                    catch (Exception ignored) { }
                }
            });
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-javaagent:" + ParentWatchdog.createAgentJar() + "=" + permits.getLocalPort(),
                    "-cp", System.getProperty("java.class.path"), WaitingChild.class.getName())
                    .redirectErrorStream(true).start();
            try {
                assertEquals("ready", child.inputReader().readLine());
                permits.close();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS), "부모가 살아 있어도 허가가 끊기면 자식은 종료돼야 한다");
                assertEquals(75, child.exitValue());
            } finally { child.destroyForcibly(); }
        }
    }

    public static class WaitingChild {
        public static void main(String[] args) throws Exception {
            System.out.println("ready");
            new java.util.concurrent.CountDownLatch(1).await();
        }
    }
}
