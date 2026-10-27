package mcd.node;

import mcd.lock.HostLock;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Git 갱신/월드 저장과 독립된 소유권 확인, Paper watchdog의 읽기 전용 허가 서버. */
final class WriterGuard implements AutoCloseable {
    static final Duration LIFETIME = Duration.ofSeconds(20);
    static final Duration TAKEOVER_WAIT = Duration.ofSeconds(30);
    private final ExecutionPermit permit = new ExecutionPermit(LIFETIME, System::nanoTime);
    private final ServerSocket server;
    private final CountDownLatch closed = new CountDownLatch(1);

    WriterGuard(HostLock lock) throws Exception {
        long start = System.nanoTime();
        if (!lock.confirmsOwnership() || !permit.confirm(start)) throw new IOException("현재 소유권을 확인하지 못했습니다");
        server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("writer-permit").start(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write((permit.remainingMillis() + "\n").getBytes(StandardCharsets.US_ASCII));
                } catch (IOException ignored) { }
            }
        });
        Thread.ofPlatform().daemon().name("writer-ownership").start(() -> {
            try {
                while (!closed.await(5, TimeUnit.SECONDS)) {
                    long requestStart = System.nanoTime();
                    try {
                        if (lock.confirmsOwnership()) permit.confirm(requestStart);
                        else permit.revoke();
                    } catch (Exception ignored) {
                        // 실패한 조회는 허가를 갱신하지 않는다. 기존 허가까지만 실행한다.
                    }
                }
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
    }

    int port() { return server.getLocalPort(); }
    boolean valid() { return permit.remainingMillis() > 0; }

    @Override public void close() throws IOException {
        closed.countDown();
        permit.revoke();
        server.close();
    }
}
