package mcd.node;

import mcd.lock.HostLock;
import mcd.lock.HostLock.Acquisition;
import mcd.lock.HostLock.Observation;
import mcd.lock.LockState;
import mcd.net.Relay;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 한 PC의 mcd. localhost에 중계를 열어 두고, 연결이 들어오면 살아 있는 호스트로 넘기거나 호스트가 없으면
 * 이 PC가 호스트가 된다. 호스트가 아닌 동안에는 락을 관찰해서 다른 호스트의 lease 만료를 판정한다.
 *
 * <p>git 작업 트리는 한 번에 한 곳에서만 다룬다: 호스트인 동안은 {@link HostSession}이, 아닌 동안은 이 클래스가
 * (route와 관찰 모두 이 객체의 모니터를 잡고) 다룬다.
 */
public final class Node implements AutoCloseable {

    /** 마인크래프트 클라이언트의 읽기 제한(약 30초)보다 짧게 기다린다. 넘으면 연결을 닫고 다시 접속하게 한다. */
    private static final Duration ROUTE_WAIT = Duration.ofSeconds(25);

    private final HostLock lock;
    private final Path dir;
    private final Path jar;
    private final int listenPort;
    private final int paperPort;
    private final String node;
    private final String address;
    private final Timing timing;
    private final InstantSource clock;
    private final Consumer<String> log;

    private final CountDownLatch closed = new CountDownLatch(1);
    private Relay relay;
    private volatile HostSession session;
    private LockState lastAnnounced;

    public Node(HostLock lock, Path dir, Path jar, int listenPort, int paperPort, String node, String address,
                Timing timing, InstantSource clock, Consumer<String> log) {
        this.lock = lock;
        this.dir = dir;
        this.jar = jar;
        this.listenPort = listenPort;
        this.paperPort = paperPort;
        this.node = node;
        this.address = address;
        this.timing = timing;
        this.clock = clock;
        this.log = log;
    }

    public void start() throws IOException {
        relay = Relay.start(listenPort, this::route);
        log.accept("준비됐다. 마인크래프트에서 localhost:" + relay.port() + "로 접속하면 된다.");
        Thread.ofPlatform().name("node-observer").start(this::observeLoop);
    }

    /** 호스트라면 저장하고 락을 반납한 뒤 끝낸다. */
    @Override
    public void close() throws IOException, InterruptedException {
        closed.countDown();
        relay.close();
        HostSession current = session;
        if (current != null && current.isRunning()) {
            current.requestStop();
            current.awaitEnd(Duration.ofMinutes(3));
        }
    }

    /** 호스트인 동안 사용자 콘솔 입력을 Paper로 넘긴다. 호스트가 아니면 false. */
    public boolean consoleCommand(String line) throws IOException {
        HostSession current = session;
        if (current == null || !current.isRunning()) return false;
        current.consoleCommand(line);
        return true;
    }

    private synchronized InetSocketAddress route() throws IOException, InterruptedException {
        if (session != null && session.isRunning()) {
            return awaitLocalPaper();
        }
        try {
            Observation observed = lock.peek();
            announce(observed.lock());
            if (!observed.takeable()) {
                return parse(observed.lock().address());
            }
            Acquisition acquisition = lock.tryAcquire(address);
            if (!acquisition.acquired()) {
                announce(acquisition.lock());
                return parse(acquisition.lock().address());
            }
            log.accept("이 PC가 호스트가 됐다(epoch " + acquisition.lock().epoch() + "). Paper를 시작한다.");
            session = new HostSession(lock, dir, jar, paperPort, node, timing, clock, log);
            session.start();
            return awaitLocalPaper();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IOException("원격 저장소를 확인하지 못했다", e);
        }
    }

    private InetSocketAddress awaitLocalPaper() throws IOException {
        if (!session.awaitReady(ROUTE_WAIT)) {
            throw new IOException("Paper가 아직 기동 중이다");
        }
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), paperPort);
    }

    private void observeLoop() {
        while (closed.getCount() > 0) {
            try {
                HostSession current = session;
                if (current == null || !current.isRunning()) {
                    synchronized (this) {
                        announce(lock.peek().lock());
                    }
                }
                closed.await(timing.poll().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.accept("원격 저장소를 확인하지 못했다: " + e.getMessage());
                try {
                    closed.await(timing.poll().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void announce(LockState current) {
        if (current.equals(lastAnnounced)) return;
        lastAnnounced = current;
        if (current.state() == LockState.State.HOSTING && !current.owner().equals(node)) {
            log.accept("지금 호스트는 " + current.owner() + "이다. 접속하면 그쪽으로 이어진다.");
        } else if (current.state() == LockState.State.FREE) {
            log.accept("지금은 호스트가 없다. 접속하면 이 PC가 호스트가 된다.");
        }
    }

    private static InetSocketAddress parse(String hostPort) throws IOException {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) throw new IOException("호스트 주소가 없다");
        return new InetSocketAddress(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)));
    }
}
