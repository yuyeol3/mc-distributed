package mcd.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 이 PC의 localhost 포트로 들어온 마인크래프트 연결을 현재 호스트로 바이트 그대로 넘긴다.
 * 프로토콜을 해석하지 않으므로 암호화와 정품 인증은 클라이언트와 Paper 사이에서 그대로 이뤄진다.
 * 연결마다 {@link Router}에 목적지를 묻는다. Router는 호스트가 준비될 때까지 기다리게 할 수 있다.
 */
public final class Relay implements AutoCloseable {

    @FunctionalInterface
    public interface Router {
        /** 새 연결을 보낼 곳. 보낼 곳이 없으면 IOException을 던지고, 그 연결은 닫힌다. */
        InetSocketAddress route() throws IOException, InterruptedException;
    }

    private static final long CONNECT_RETRY_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(25);

    private final ServerSocket server;
    private final Router router;
    private final Proxy remoteProxy;

    private Relay(ServerSocket server, Router router, Proxy remoteProxy) {
        this.server = server;
        this.router = router;
        this.remoteProxy = remoteProxy;
    }

    /** 다른 PC에서 들어오지 못하도록 loopback 주소에만 연다. port가 0이면 빈 포트를 고른다. */
    public static Relay start(int port, Router router) throws IOException {
        return start(port, router, Proxy.NO_PROXY);
    }

    /** remoteProxy는 loopback이 아닌 목적지(다른 PC)로 나갈 때 쓴다(tailnet의 SOCKS5). */
    public static Relay start(int port, Router router, Proxy remoteProxy) throws IOException {
        ServerSocket server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        Relay relay = new Relay(server, router, remoteProxy);
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
            InetSocketAddress target = router.route();
            try (Socket upstream = connect(target)) {
                upstream.setTcpNoDelay(true);
                client.setTcpNoDelay(true);
                Thread up = Thread.ofPlatform().daemon().name("relay-up").start(() -> pipe(client, upstream));
                pipe(upstream, client);
                up.join();
            }
        } catch (IOException | InterruptedException e) {
            // 목적지가 없거나 연결이 끊긴 경우. 클라이언트에는 연결 끊김으로 보인다.
        }
    }

    /**
     * 목적지에 연결한다. 호스트가 막 바뀐 직후에는 새 호스트의 Paper가 아직 기동 중이라 연결이 거절될 수 있으므로,
     * 클라이언트의 읽기 제한(약 30초)보다 짧은 시간 동안 다시 시도한다.
     */
    private Socket connect(InetSocketAddress target) throws IOException, InterruptedException {
        boolean local = target.getAddress() != null && target.getAddress().isLoopbackAddress();
        long deadline = System.nanoTime() + CONNECT_RETRY_NANOS;
        while (true) {
            Socket upstream = local ? new Socket() : new Socket(remoteProxy);
            try {
                upstream.connect(target, 10_000);
                return upstream;
            } catch (IOException e) {
                upstream.close();
                if (System.nanoTime() > deadline) throw e;
                Thread.sleep(500);
            }
        }
    }

    /**
     * from이 끝나면 to의 쓰기 방향만 닫아 반대쪽에 끝을 알린다. 소켓 스트림을 close하면 소켓 전체가 닫혀
     * 반대 방향 전송까지 끊기므로 스트림은 닫지 않는다(소켓은 handle에서 닫음).
     */
    private static void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            in.transferTo(out);
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
