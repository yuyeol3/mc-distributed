package mcd.node;

import mcd.lock.HostLock;
import mcd.lock.HostLock.Acquisition;
import mcd.lock.HostLock.Observation;
import mcd.lock.LockState;
import mcd.net.Relay;
import mcd.server.PaperDistribution;
import mcd.world.WorldRepository;
import mcd.world.WorldTemplate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
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

    /** 노드 하나를 띄우는 데 필요한 값. CLI와 GUI가 같은 것을 채운다. */
    public record Config(String remote, Path dir, String nodeName, int listenPort, int paperPort, String address,
                         Path cacheDir, CredentialsProvider credentials, Timing timing) {}

    private final Git git;
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
    private final PrintStream console;

    private final CountDownLatch closed = new CountDownLatch(1);
    private Relay relay;
    private volatile HostSession session;
    private LockState lastAnnounced;

    private Node(Git git, HostLock lock, Config config, Path jar, InstantSource clock, Consumer<String> log, PrintStream console) {
        this.git = git;
        this.lock = lock;
        this.dir = config.dir();
        this.jar = jar;
        this.listenPort = config.listenPort();
        this.paperPort = config.paperPort();
        this.node = config.nodeName();
        this.address = config.address();
        this.timing = config.timing();
        this.clock = clock;
        this.log = log;
        this.console = console;
    }

    /**
     * 월드 작업 트리를 열거나 받고, Paper jar를 준비해서 노드를 만든다(아직 시작하지 않음).
     * log는 mcd의 안내 메시지, console은 호스트일 때 Paper 콘솔 출력이 가는 곳이다.
     * acceptEula가 참이면 EULA 동의를 기록한다(clone은 빈 폴더에만 되므로 받은 뒤에 쓴다).
     */
    public static Node open(Config config, boolean acceptEula, Consumer<String> log, PrintStream console) throws Exception {
        if (!WorldRepository.exists(config.dir())) {
            log.accept(config.remote() + "에서 월드를 받는다.");
        }
        Git git = WorldRepository.openOrClone(config.dir(), config.remote(), config.credentials());
        try {
            // 엉뚱한 git repo(예: 경로가 비어 현재 폴더)에 파일을 쓰지 않도록, 월드 repo인지부터 확인한다.
            WorldTemplate.checkPaperBuild(config.dir());
            if (acceptEula) {
                WorldTemplate.acceptEula(config.dir());
            }
            if (!Files.exists(config.dir().resolve("eula.txt"))) {
                throw new IllegalStateException("Minecraft EULA(https://aka.ms/MinecraftEULA)에 동의해야 서버를 띄울 수 있다.");
            }
            Path jar = PaperDistribution.ensureDownloaded(config.cacheDir());
            InstantSource clock = InstantSource.system();
            Timing t = config.timing();
            HostLock lock = new HostLock(git, config.nodeName(), clock, t.ttl(), t.margin(), config.credentials());
            return new Node(git, lock, config, jar, clock, log, console);
        } catch (Exception e) {
            git.close();
            throw e;
        }
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
        if (relay != null) relay.close();
        HostSession current = session;
        if (current != null && current.isRunning()) {
            current.requestStop();
            current.awaitEnd(Duration.ofMinutes(3));
        }
        synchronized (this) {
            git.close();
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
            session = new HostSession(lock, dir, jar, paperPort, node, timing, clock, log, console);
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
