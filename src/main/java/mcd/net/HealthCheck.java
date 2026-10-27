package mcd.net;

import mcd.lock.LockState;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 읽기 전용 상태 확인. Paper 기동이나 Git 작업을 기다리지 않는다. */
public final class HealthCheck implements AutoCloseable {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("health-deadlines").factory());
    private final ServerSocket server;

    private HealthCheck(ServerSocket server, Supplier<LockState> state) {
        this.server = server;
        Thread.ofPlatform().daemon().name("health-server").start(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(1000);
                    LockState current = state.get();
                    if (current != null) socket.getOutputStream().write(reply(current));
                } catch (IOException ignored) {
                    // 종료하거나 읽는 쪽이 먼저 끊은 경우 다음 요청을 받는다.
                }
            }
        });
    }

    public static HealthCheck start(int port, boolean loopback, Supplier<LockState> state) throws IOException {
        return new HealthCheck(new ServerSocket(port, 16, loopback ? InetAddress.getLoopbackAddress() : null), state);
    }

    public int port() { return server.getLocalPort(); }

    public static boolean probe(String address, LockState expected, Proxy proxy, Duration timeout) {
        try (Socket socket = new Socket(proxy)) {
            var deadline = DEADLINES.schedule(() -> { try { socket.close(); } catch (IOException ignored) {} },
                    timeout.toMillis(), TimeUnit.MILLISECONDS);
            try {
                socket.setSoTimeout(Math.toIntExact(timeout.toMillis()));
                socket.connect(parse(address), Math.toIntExact(timeout.toMillis()));
                byte[] bytes = socket.getInputStream().readNBytes(reply(expected).length);
                return java.util.Arrays.equals(bytes, reply(expected));
            } finally {
                deadline.cancel(false);
            }
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] reply(LockState state) {
        return ("MCD1 " + state.session() + " " + state.epoch() + " " + state.state() + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static InetSocketAddress parse(String address) {
        int colon = address.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("잘못된 주소: " + address);
        return new InetSocketAddress(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)));
    }

    @Override public void close() throws IOException { server.close(); }
}
