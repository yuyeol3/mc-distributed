package mcd.net;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayTest {

    /** 반복하는 이유: Windows에서 가상 스레드로 중계하면 가끔 멈췄다(40회 중 3~4회). 한 번으로는 잡히지 않는다. */
    @RepeatedTest(20)
    void relaysLoginAndBytesBothWaysToCurrentTarget() throws Exception {
        try (EchoServer echo = new EchoServer(); Relay relay = Relay.start(0, echo::address)) {
            byte[] payload = new byte[1 << 20];
            new Random(1).nextBytes(payload);
            byte[] sent = concat(loginPreamble("alice"), payload);
            assertArrayEquals(sent, roundTrip(relay.port(), sent), "첫머리는 그대로 다시 보내고 나머지는 그대로 넘겨야 한다");
        }
    }

    @Test
    void eachNewConnectionAsksRouterForCurrentHost() throws Exception {
        try (EchoServer first = new EchoServer(); EchoServer second = new EchoServer()) {
            AtomicReference<InetSocketAddress> current = new AtomicReference<>(first.address());
            try (Relay relay = Relay.start(0, current::get)) {
                roundTrip(relay.port(), loginPreamble("a"));
                current.set(second.address());
                roundTrip(relay.port(), loginPreamble("b"));
            }
            assertEquals(1, first.connections);
            assertEquals(1, second.connections);
        }
    }

    @Test
    void serverListPingWithoutHostIsAnsweredByRelayWithoutStartingAHost() throws Exception {
        AtomicInteger routed = new AtomicInteger();
        try (Relay relay = Relay.start(0, () -> {
            routed.incrementAndGet();
            throw new IOException("호스트를 띄우면 안 된다");
        }); Socket s = connect(relay)) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(handshake(Protocol.INTENT_STATUS).raw());
            Protocol.write(out, Protocol.STATUS_REQUEST, new byte[0]);
            Protocol.Packet response = Protocol.read(in);
            String json = Protocol.readString(new ByteArrayInputStream(response.body()));
            assertTrue(json.contains(Relay.IDLE_MOTD) && json.contains("\"protocol\":775"), json);

            byte[] pingPayload = {1, 2, 3, 4, 5, 6, 7, 8};
            Protocol.write(out, Protocol.PING, pingPayload);
            assertArrayEquals(pingPayload, Protocol.read(in).body());
        }
        assertEquals(0, routed.get());
    }

    @Test
    void serverListPingIsForwardedToLiveHost() throws Exception {
        try (EchoServer host = new EchoServer();
             Relay relay = Relay.start(0, new Relay.Router() {
                 @Override
                 public InetSocketAddress route() throws IOException {
                     throw new IOException("route를 부르면 안 된다");
                 }

                 @Override
                 public InetSocketAddress currentHost() {
                     return host.address();
                 }
             })) {
            Protocol.Packet hs = handshake(Protocol.INTENT_STATUS);
            byte[] request = frame(Protocol.STATUS_REQUEST, new byte[0]);
            byte[] sent = concat(hs.raw(), request);
            assertArrayEquals(sent, roundTrip(relay.port(), sent), "살아 있는 호스트로 그대로 넘겨야 한다");
        }
    }

    /** tailnet에서 기동 중인 호스트는 연결을 받자마자 닫는다. 그때는 끊지 말고 중계가 직접 답해야 한다. */
    @Test
    void serverListPingIsAnsweredByRelayWhenHostClosesBeforeAnswering() throws Exception {
        try (EchoServer starting = new EchoServer(1);
             Relay relay = Relay.start(0, new Relay.Router() {
                 @Override
                 public InetSocketAddress route() throws IOException {
                     throw new IOException("route를 부르면 안 된다");
                 }

                 @Override
                 public InetSocketAddress currentHost() {
                     return starting.address();
                 }
             }); Socket s = connect(relay)) {
            OutputStream out = s.getOutputStream();
            out.write(handshake(Protocol.INTENT_STATUS).raw());
            Protocol.write(out, Protocol.STATUS_REQUEST, new byte[0]);
            Protocol.Packet response = Protocol.read(s.getInputStream());
            assertEquals(Protocol.STATUS_RESPONSE, response.id());
            String json = Protocol.readString(new ByteArrayInputStream(response.body()));
            assertTrue(json.contains(Relay.IDLE_MOTD), json);
        }
    }

    @Test
    void loginIsHeldWithPluginRequestsUntilHostIsReadyAndAnswersAreNotForwarded() throws Exception {
        try (EchoServer echo = new EchoServer();
             Relay relay = Relay.start(0, () -> {
                 Thread.sleep(1500); // 호스트의 Paper가 기동하는 시간
                 return echo.address();
             }, Proxy.NO_PROXY, Duration.ofMillis(100));
             Socket s = connect(relay)) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            byte[] preamble = loginPreamble("alice");
            out.write(preamble);

            int pluginRequests = 0;
            ByteArrayOutputStream fromHost = new ByteArrayOutputStream();
            while (fromHost.size() < preamble.length) {
                Protocol.Packet p = Protocol.read(in);
                if (p.id() == Protocol.LOGIN_PLUGIN_REQUEST && fromHost.size() == 0) {
                    pluginRequests++;
                    ByteArrayOutputStream answer = new ByteArrayOutputStream();
                    Protocol.writeVarInt(answer, Protocol.pluginResponseId(p));
                    answer.write(0); // 실제 클라이언트처럼 "모름"
                    Protocol.write(out, Protocol.LOGIN_PLUGIN_RESPONSE, answer.toByteArray());
                } else {
                    fromHost.write(p.raw());
                }
            }
            assertTrue(pluginRequests >= 5, "기다리는 동안 질의를 계속 보내야 한다: " + pluginRequests);
            assertArrayEquals(preamble, fromHost.toByteArray(), "호스트는 원래 첫머리만 받아야 한다");

            byte[] after = {9, 9, 9};
            out.write(after);
            assertArrayEquals(after, in.readNBytes(3), "질의에 대한 답이 호스트로 새면 안 된다");
        }
    }

    @Test
    void loginReevaluatesRouteWhenOriginalHostIsGone() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        int gone;
        try (ServerSocket reservation = new ServerSocket(0)) { gone = reservation.getLocalPort(); }
        try (EchoServer echo = new EchoServer();
             Relay relay = Relay.start(0, () -> calls.getAndIncrement() == 0
                     ? new InetSocketAddress(InetAddress.getLoopbackAddress(), gone) : echo.address(),
                     Proxy.NO_PROXY, Duration.ofHours(1)); Socket socket = connect(relay)) {
            byte[] preamble = loginPreamble("alice");
            socket.getOutputStream().write(preamble);
            assertArrayEquals(preamble, socket.getInputStream().readNBytes(preamble.length));
            assertTrue(calls.get() >= 2);
        }
    }

    /**
     * tailnet에서는 상대 PC의 mcd-net이 Paper 포트를 늘 열어 두므로, Paper가 아직 기동 중이어도 연결은 되고 곧바로 끊긴다.
     * 이것을 연결 실패로 보고 Paper가 답할 때까지 다시 시도해야 한다(진 쪽 중계가 이긴 쪽 Paper를 기다리는 handoff).
     */
    @Test
    void loginRetriesWhenHostAcceptsButClosesBeforeAnswering() throws Exception {
        try (EchoServer starting = new EchoServer(2);
             Relay relay = Relay.start(0, starting::address, Proxy.NO_PROXY, Duration.ofHours(1));
             Socket socket = connect(relay)) {
            byte[] preamble = loginPreamble("alice");
            socket.getOutputStream().write(preamble);
            assertArrayEquals(preamble, socket.getInputStream().readNBytes(preamble.length));
            assertEquals(3, starting.connections);
        }
    }

    @Test
    void loginGetsReadableDisconnectWhenThereIsNoHost() throws Exception {
        try (Relay relay = Relay.start(0, () -> { throw new IOException("no host"); }); Socket s = connect(relay)) {
            s.getOutputStream().write(loginPreamble("alice"));
            Protocol.Packet p = Protocol.read(s.getInputStream());
            assertEquals(Protocol.LOGIN_DISCONNECT, p.id());
            String reason = Protocol.readString(new ByteArrayInputStream(p.body()));
            assertTrue(reason.contains("mcd") && reason.contains("no host"), reason);
            assertEquals(-1, s.getInputStream().read(), "그다음 연결을 닫아야 한다");
        }
    }

    private static Socket connect(Relay relay) throws IOException {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), relay.port());
        s.setSoTimeout(10_000);
        return s;
    }

    private static Protocol.Packet handshake(int intent) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        Protocol.writeVarInt(body, 775);
        body.write(Protocol.string("localhost"));
        body.write(new byte[] {(byte) 0x63, (byte) 0xDD}); // 25565
        Protocol.writeVarInt(body, intent);
        return Protocol.read(new ByteArrayInputStream(frame(0x00, body.toByteArray())));
    }

    /** 핸드셰이크(로그인 의도) + 로그인 시작(이름, UUID). */
    private static byte[] loginPreamble(String name) throws IOException {
        ByteArrayOutputStream login = new ByteArrayOutputStream();
        login.write(Protocol.string(name));
        login.write(new byte[16]);
        return concat(handshake(Protocol.INTENT_LOGIN).raw(), frame(0x00, login.toByteArray()));
    }

    private static byte[] frame(int id, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Protocol.write(out, id, body);
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    private static byte[] roundTrip(int port, byte[] payload) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(5000);
            Thread writer = Thread.ofPlatform().start(() -> {
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
            return received;
        }
    }

    /** 받은 바이트를 그대로 돌려주는 서버. */
    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private volatile int connections;

        EchoServer() throws IOException {
            this(0);
        }

        /** 처음 dropFirst개 연결은 받자마자 닫는다(Paper 없이 포트만 열린 mcd-net처럼). */
        EchoServer(int dropFirst) throws IOException {
            Thread.ofPlatform().daemon().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        if (++connections <= dropFirst) {
                            s.close();
                            continue;
                        }
                        Thread.ofPlatform().daemon().start(() -> {
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
