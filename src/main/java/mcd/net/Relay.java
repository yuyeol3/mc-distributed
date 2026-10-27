package mcd.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 이 PC의 localhost 포트로 들어온 마인크래프트 연결을 현재 호스트로 넘긴다.
 *
 * <p>연결 첫머리(핸드셰이크)만 읽어 의도를 나눈다. 서버 목록의 상태 확인은 호스트를 새로 띄우지 않고, 살아 있는
 * 호스트가 있으면 그쪽으로, 없으면 중계가 직접 답한다. 로그인은 {@link Router}가 호스트를 정해 줄 때까지(이 PC가
 * 막 호스트가 돼 Paper가 기동 중일 수 있다) 무해한 로그인 플러그인 질의를 주기적으로 보내 클라이언트의 30초 읽기
 * 제한에 걸리지 않게 붙잡아 둔다. 호스트에 연결되면 받아 둔 첫머리 패킷을 그대로 다시 보내고, 그 뒤로는 바이트를
 * 해석하지 않고 넘긴다. 그래서 암호화와 정품 인증은 클라이언트와 Paper 사이에서 그대로 이뤄진다.
 */
public final class Relay implements AutoCloseable {

    @FunctionalInterface
    public interface Router {
        /** 로그인 연결을 보낼 곳. 오래 걸려도 된다(그동안 클라이언트를 붙잡아 둔다). 보낼 곳이 없으면 IOException. */
        InetSocketAddress route() throws IOException, InterruptedException;

        /** 서버 목록 확인을 넘길 곳: 지금 살아 있는 호스트가 있으면 그 주소, 없으면 null. 호스트를 새로 띄우면 안 된다. */
        default InetSocketAddress currentHost() {
            return null;
        }
    }

    /** 호스트가 막 바뀌어 새 Paper가 기동 중일 때도 붙잡아 두므로, 연결 재시도를 길게 한다. */
    private static final long CONNECT_RETRY_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
    private static final Duration KEEPALIVE = Duration.ofSeconds(5);
    static final String IDLE_MOTD = "mcd: 접속하면 서버가 시작됩니다";

    private final ServerSocket server;
    private final Router router;
    private final Proxy remoteProxy;
    private final Duration keepalive;

    private Relay(ServerSocket server, Router router, Proxy remoteProxy, Duration keepalive) {
        this.server = server;
        this.router = router;
        this.remoteProxy = remoteProxy;
        this.keepalive = keepalive;
    }

    /** 다른 PC에서 들어오지 못하도록 loopback 주소에만 연다. port가 0이면 빈 포트를 고른다. */
    public static Relay start(int port, Router router) throws IOException {
        return start(port, router, Proxy.NO_PROXY);
    }

    /** remoteProxy는 loopback이 아닌 목적지(다른 PC)로 나갈 때 쓴다(tailnet의 SOCKS5). */
    public static Relay start(int port, Router router, Proxy remoteProxy) throws IOException {
        return start(port, router, remoteProxy, KEEPALIVE);
    }

    static Relay start(int port, Router router, Proxy remoteProxy, Duration keepalive) throws IOException {
        ServerSocket server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        Relay relay = new Relay(server, router, remoteProxy, keepalive);
        // 연결 수는 플레이어 수 정도라 플랫폼 스레드로 충분하다. Windows에서 가상 스레드로 소켓을 중계하면 가끔
        // 읽을 데이터가 있는데도 깨어나지 않아 연결이 멈췄다(RelayTest 40회 반복 중 3~4회, 스레드 덤프로 확인).
        Thread.ofPlatform().daemon().name("relay-accept").start(relay::acceptLoop);
        return relay;
    }

    public int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread.ofPlatform().daemon().name("relay-conn").start(() -> handle(client));
            } catch (IOException e) {
                // 닫히면 accept가 예외를 던지고 루프가 끝난다.
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Protocol.Packet handshake = Protocol.read(in);
            Protocol.Handshake hs = Protocol.handshake(handshake);
            if (hs.intent() == Protocol.INTENT_STATUS) {
                status(client, in, out, handshake, hs);
            } else {
                login(client, in, out, handshake);
            }
        } catch (IOException | InterruptedException e) {
            // 마인크래프트 연결이 아니거나 연결이 끊긴 경우.
        }
    }

    /** 서버 목록 확인. 살아 있는 호스트가 있으면 넘기고, 없으면(또는 닿지 않으면) 중계가 직접 답한다. */
    private void status(Socket client, InputStream in, OutputStream out, Protocol.Packet handshake, Protocol.Handshake hs)
            throws IOException, InterruptedException {
        InetSocketAddress host = router.currentHost();
        if (host != null) {
            Socket upstream = local(host) ? new Socket() : new Socket(remoteProxy);
            try {
                upstream.connect(host, 3000);
            } catch (IOException e) {
                upstream.close();
                upstream = null;
            }
            if (upstream != null) {
                try (Socket connected = upstream) {
                    connected.getOutputStream().write(handshake.raw());
                    splice(client, in, connected);
                }
                return;
            }
        }
        Protocol.Packet request = Protocol.read(in);
        if (request.id() != Protocol.STATUS_REQUEST) return;
        Protocol.write(out, Protocol.STATUS_RESPONSE, Protocol.statusResponse(hs.protocolVersion(), IDLE_MOTD));
        Protocol.Packet ping = Protocol.read(in);
        if (ping.id() == Protocol.PING) {
            Protocol.write(out, Protocol.PING, ping.body());
        }
    }

    /** 로그인. 호스트가 정해지고 연결될 때까지 클라이언트를 붙잡아 두고, 연결되면 첫머리를 다시 보내 이어 준다. */
    private void login(Socket client, InputStream in, OutputStream out, Protocol.Packet handshake) throws IOException, InterruptedException {
        Protocol.Packet loginStart = Protocol.read(in);
        Hold hold = new Hold(out, keepalive);
        Socket upstream;
        try {
            upstream = connect(router.route());
        } catch (IOException e) {
            hold.stop();
            Protocol.write(out, Protocol.LOGIN_DISCONNECT, Protocol.loginDisconnect("mcd: 서버에 연결하지 못했습니다. " + e.getMessage()));
            return;
        }
        try (upstream) {
            hold.stop();
            hold.drainResponses(in);
            upstream.setTcpNoDelay(true);
            OutputStream up = upstream.getOutputStream();
            up.write(handshake.raw());
            up.write(loginStart.raw());
            up.flush();
            splice(client, in, upstream);
        }
    }

    /** 양방향으로 바이트를 넘긴다. 클라이언트 쪽 스트림은 이미 첫머리를 읽은 뒤의 것이다. */
    private static void splice(Socket client, InputStream clientIn, Socket upstream) throws InterruptedException {
        Thread up = Thread.ofPlatform().daemon().name("relay-up").start(() -> pipe(clientIn, upstream));
        pipe(upstream, client);
        up.join();
    }

    /**
     * 호스트를 기다리는 동안 로그인 플러그인 질의를 보내 연결을 살려 둔다. 클라이언트는 모르는 질의에 "모름"으로
     * 답하는데, 이 답은 Paper에 넘기면 안 되므로 멈춘 뒤 보낸 만큼 읽어서 버린다.
     */
    private static final class Hold {
        private final Thread thread;
        private volatile boolean stopped;
        private int sent;

        Hold(OutputStream out, Duration interval) {
            this.thread = Thread.ofPlatform().daemon().name("relay-hold").start(() -> {
                try {
                    while (!stopped) {
                        Thread.sleep(interval);
                        synchronized (this) {
                            if (stopped) return;
                            Protocol.write(out, Protocol.LOGIN_PLUGIN_REQUEST, Protocol.loginPluginRequest(sent, "mcd:wait"));
                            sent++;
                        }
                    }
                } catch (InterruptedException | IOException e) {
                    // 멈추거나 클라이언트가 떠났다.
                }
            });
        }

        void stop() throws InterruptedException {
            synchronized (this) {
                stopped = true;
            }
            thread.interrupt();
            thread.join();
        }

        void drainResponses(InputStream in) throws IOException {
            for (int answered = 0; answered < sent; ) {
                if (Protocol.read(in).id() == Protocol.LOGIN_PLUGIN_RESPONSE) answered++;
            }
        }
    }

    private static boolean local(InetSocketAddress target) {
        return target.getAddress() != null && target.getAddress().isLoopbackAddress();
    }

    /**
     * 목적지에 연결한다. 호스트가 막 바뀐 직후에는 새 호스트의 Paper가 아직 기동 중이라 연결이 거절될 수 있으므로
     * 다시 시도한다. 그동안 클라이언트는 {@link Hold}가 붙잡아 둔다.
     */
    private Socket connect(InetSocketAddress target) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + CONNECT_RETRY_NANOS;
        long reroute = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            Socket upstream = local(target) ? new Socket() : new Socket(remoteProxy);
            try {
                upstream.connect(target, 10_000);
                return upstream;
            } catch (IOException e) {
                upstream.close();
                if (System.nanoTime() > deadline) throw e;
                Thread.sleep(500);
                if (System.nanoTime() >= reroute) {
                    target = router.route();
                    reroute = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                }
            }
        }
    }

    /**
     * in이 끝나면 to의 쓰기 방향만 닫아 반대쪽에 끝을 알린다. 소켓 스트림을 close하면 소켓 전체가 닫혀
     * 반대 방향 전송까지 끊기므로 스트림은 닫지 않는다(소켓은 handle에서 닫음).
     */
    private static void pipe(Socket from, Socket to) {
        try {
            pipe(from.getInputStream(), to);
        } catch (IOException ignored) {
            // 이미 닫혔다.
        }
    }

    private static void pipe(InputStream in, Socket to) {
        try {
            in.transferTo(to.getOutputStream());
        } catch (IOException ignored) {
            // 한쪽이 닫히면 끝난다.
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
                // 이미 닫혔다.
            }
        }
    }
}
