package mcd.net;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RelayTest {

    /** 반복하는 이유: Windows에서 가상 스레드로 중계하면 가끔 멈췄다(40회 중 3~4회). 한 번으로는 잡히지 않는다. */
    @RepeatedTest(20)
    void relaysBytesBothWaysToCurrentTarget() throws Exception {
        try (EchoServer echo = new EchoServer(); Relay relay = Relay.start(0, echo::address)) {
            byte[] payload = new byte[1 << 20];
            new Random(1).nextBytes(payload);
            assertArrayEquals(payload, roundTrip(relay.port(), payload));
        }
    }

    @Test
    void eachNewConnectionAsksRouterForCurrentHost() throws Exception {
        try (EchoServer first = new EchoServer(); EchoServer second = new EchoServer()) {
            AtomicReference<InetSocketAddress> current = new AtomicReference<>(first.address());
            try (Relay relay = Relay.start(0, current::get)) {
                roundTrip(relay.port(), new byte[] {1});
                current.set(second.address());
                roundTrip(relay.port(), new byte[] {2});
            }
            assertEquals(1, first.connections);
            assertEquals(1, second.connections);
        }
    }

    @Test
    void connectionIsClosedWhenThereIsNoHost() throws Exception {
        try (Relay relay = Relay.start(0, () -> { throw new IOException("no host"); });
             Socket s = new Socket(InetAddress.getLoopbackAddress(), relay.port())) {
            s.setSoTimeout(5000);
            assertEquals(-1, s.getInputStream().read(), "목적지가 없으면 연결을 닫아야 한다");
        }
    }

    private static byte[] roundTrip(int port, byte[] payload) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(5000);
            Thread writer = Thread.ofVirtual().start(() -> {
                try {
                    s.getOutputStream().write(payload);
                    s.shutdownOutput();
                } catch (IOException ignored) {
                    // 읽는 쪽에서 실패로 드러난다.
                }
            });
            byte[] received = new byte[payload.length];
            new DataInputStream(s.getInputStream()).readFully(received);
            try {
                writer.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Arrays.copyOf(received, received.length);
        }
    }

    /** 받은 바이트를 그대로 돌려주는 서버. */
    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private volatile int connections;

        EchoServer() throws IOException {
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        connections++;
                        Thread.ofVirtual().start(() -> {
                            try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                                in.transferTo(out);
                            } catch (IOException ignored) {
                                // 연결이 끝났다.
                            }
                        });
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        InetSocketAddress address() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
