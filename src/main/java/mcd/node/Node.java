package mcd.node;

import mcd.lock.HostLock;
import mcd.lock.HostLock.Acquisition;
import mcd.lock.LockState;
import mcd.net.Relay;
import mcd.net.Tailnet;
import mcd.net.HealthCheck;
import mcd.world.WorldProcessLock;
import mcd.world.PluginManager;
import mcd.server.PaperBuild;
import mcd.server.PaperDistribution;
import mcd.server.PaperServer;
import mcd.world.NetworkConfig;
import mcd.world.WorldRepository;
import mcd.world.WorldTemplate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Optional;
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

    /**
     * 이 PC의 Paper가 기동하기를 기다리는 시간. 그동안 중계가 클라이언트를 붙잡아 두므로(Relay의 Hold) 새 폴더의
     * 첫 기동(Mojang jar 받기, 패치)도 기다릴 수 있다.
     */
    private static final Duration ROUTE_WAIT = Duration.ofMinutes(3);

    /** 노드 하나를 띄우는 데 필요한 값. CLI와 GUI가 같은 것을 채운다. */
    /**
     * handoffPort는 호스트를 넘길 때 플레이어를 보낼 각자 PC의 중계 포트다. 모두 같은 포트(기본 25565)를 쓴다고 본다.
     * 한 PC에서 노드 여러 개를 흉내 내는 테스트에서만 다른 값을 쓴다.
     */
    public record Config(String remote, Path dir, String nodeName, int listenPort, int paperPort, String address,
                         Path cacheDir, TransportConfigCallback auth, Timing timing, int handoffPort) {}

    private final Git git;
    private final HostLock lock;
    private final Path dir;
    private final Path cacheDir;
    /** 지금 준비해 둔 Paper. 월드가 정한 빌드가 바뀌면 호스트가 될 때 바꾼다(route 안에서만 바뀜). */
    private PaperBuild build;
    private Path jar;
    private final int listenPort;
    private final int paperPort;
    private final int handoffPort;
    private final String node;
    private final Timing timing;
    private final InstantSource clock;
    private final Consumer<String> log;
    private final PrintStream console;

    private final Optional<NetworkConfig> network;
    private final Path tailnetState;

    private final CountDownLatch closed = new CountDownLatch(1);
    /** 다른 노드가 이 PC의 Paper에 접속할 주소. tailnet을 쓰면 시작할 때 tailnet IP로 정해진다. */
    private volatile String address;
    private Tailnet tailnet;
    private Relay relay;
    private volatile HostSession session;
    private volatile LockState lastAnnounced;
    private volatile LockState localLock;
    private volatile LockState unhealthy;
    private HealthCheck health;
    private String healthAddress;
    private WorldProcessLock processLock;
    private volatile WriterGuard reservation;
    private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();

    private Node(Git git, HostLock lock, Config config, PaperBuild build, Path jar, Optional<NetworkConfig> network,
                 InstantSource clock, Consumer<String> log, PrintStream console) {
        this.git = git;
        this.lock = lock;
        this.dir = config.dir();
        this.cacheDir = config.cacheDir();
        this.build = build;
        this.jar = jar;
        this.listenPort = config.listenPort();
        this.paperPort = config.paperPort();
        this.handoffPort = config.handoffPort();
        this.node = config.nodeName();
        this.address = config.address();
        this.network = network;
        // 노드 키가 저장되므로 월드 폴더(git 작업 트리) 밖, 노드와 월드마다 따로 둔다.
        this.tailnetState = config.cacheDir().toAbsolutePath().resolveSibling("tailnet")
                .resolve(tailnetName(config.nodeName()) + "-" + Integer.toHexString(config.remote().hashCode()));
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
            log.accept(config.remote() + "에서 월드를 받습니다.");
        }
        Git git = WorldRepository.openOrClone(config.dir(), config.remote(), config.auth());
        WorldProcessLock processLock = null;
        try {
            // 엉뚱한 git repo(예: 경로가 비어 현재 폴더)에 파일을 쓰지 않도록, 월드 repo인지부터 확인한다.
            PaperBuild build = WorldTemplate.paperBuild(config.dir());
            processLock = WorldProcessLock.acquire(config.dir());
            PaperDistribution.checkSupported(build, log);
            if (acceptEula) {
                WorldTemplate.acceptEula(config.dir());
            }
            if (!Files.exists(config.dir().resolve("eula.txt"))) {
                throw new IllegalStateException("Minecraft EULA(https://aka.ms/MinecraftEULA)에 동의해야 서버를 띄울 수 있습니다.");
            }
            log.accept("이 월드는 마인크래프트 " + build.version() + "용입니다(Paper " + build + ").");
            Path jar = PaperDistribution.official().ensureDownloaded(config.cacheDir(), build);
            try {
                PaperServer.patch(config.dir(), jar, console);
            } catch (IOException e) {
                log.accept("Paper를 미리 준비하지 못했습니다(" + e.getMessage() + "). 호스트가 될 때 다시 준비합니다.");
            }
            InstantSource clock = InstantSource.system();
            Timing t = config.timing();
            HostLock lock = new HostLock(git, config.nodeName(), clock, t.ttl(), t.margin(), config.auth());
            Node opened = new Node(git, lock, config, build, jar, NetworkConfig.read(config.dir()), clock, log, console);
            opened.processLock = processLock;
            return opened;
        } catch (Exception e) {
            if (processLock != null) {
                try { processLock.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            git.close();
            throw e;
        }
    }

    public void start() throws Exception {
        try {
            Proxy remote = Proxy.NO_PROXY;
            health = HealthCheck.start(network.isPresent() ? paperPort + 1 : 0, network.isPresent(), this::healthState);
            if (network.isPresent()) {
                log.accept("tailnet에 붙습니다.");
                tailnet = Tailnet.join(network.get(), tailnetName(node), tailnetState, paperPort, health.port(), log);
                address = tailnet.ip() + ":" + paperPort;
                remote = tailnet.proxy();
                log.accept("tailnet 주소는 " + address + "입니다.");
            }
            healthAddress = address.substring(0, address.lastIndexOf(':') + 1) + health.port();
            Proxy healthProxy = remote;
            Thread.ofPlatform().daemon().name("node-health").start(() -> healthLoop(healthProxy));
            relay = Relay.start(listenPort, new Relay.Router() {
                @Override
                public InetSocketAddress route() throws IOException, InterruptedException {
                    return Node.this.route();
                }

                @Override
                public InetSocketAddress currentHost() {
                    return Node.this.currentHost();
                }
            }, remote);
            log.accept("준비됐습니다. 마인크래프트에서 localhost:" + relay.port() + "로 접속하면 됩니다.");
            Thread.ofPlatform().name("node-observer").start(this::observeLoop);
        } catch (Exception failure) {
            try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** 호스트라면 저장하고 락을 반납한 뒤 끝낸다. */
    @Override
    public void close() throws IOException, InterruptedException {
        if (!closing.compareAndSet(false, true)) return;
        closed.countDown();
        if (relay != null) relay.close();
        HostSession current = session;
        if (current != null && current.isRunning()) {
            current.requestStop();
            if (!current.awaitEnd(Duration.ofMinutes(3))) {
                current.forceStop();
                if (!current.awaitEnd(Duration.ofSeconds(5))) {
                    throw new IOException("호스트 작업이 끝나지 않았습니다. 로컬 월드 잠금을 유지합니다.");
                }
            }
        }
        synchronized (this) {
            git.close();
        }
        if (tailnet != null) tailnet.close();
        if (health != null) health.close();
        if (processLock != null) processLock.close();
    }

    /** 화면에 보여줄 지금 상태. */
    public enum Phase {
        /** 아직 원격 락을 확인하지 않았다. */
        CHECKING,
        /** 호스트가 없다. 접속하면 이 PC가 호스트가 된다. */
        NO_HOST,
        /** 다른 PC가 호스트다(host). */
        OTHER_HOST,
        /** 원격 호스트의 상태 확인이 연속 실패했다. */
        UNHEALTHY,
        /** 월드/플러그인을 변경하는 동안 호스팅을 막는다. */
        MAINTENANCE,
        /** 이 PC가 호스트가 되어 Paper가 기동 중이다. */
        STARTING,
        /** 이 PC가 호스트다. players는 마지막으로 센 접속 인원. */
        HOSTING
    }

    public record Status(Phase phase, String host, int players) {}

    public Status status() {
        HostSession current = session;
        if (current != null && current.isRunning()) {
            return new Status(current.isReady() ? Phase.HOSTING : Phase.STARTING, node, current.players());
        }
        LockState seen = lastAnnounced;
        if (seen == null) return new Status(Phase.CHECKING, null, 0);
        if (localLock != null && localLock.hosting()) return new Status(Phase.STARTING, node, 0);
        if (seen.state() == LockState.State.MAINTENANCE) return new Status(Phase.MAINTENANCE, seen.owner(), 0);
        if (seen.hosting()) {
            if (seen.equals(unhealthy)) return new Status(Phase.UNHEALTHY, seen.owner(), 0);
            return new Status(Phase.OTHER_HOST, seen.owner(), 0);
        }
        return new Status(Phase.NO_HOST, null, 0);
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
            PluginManager.verifyRuntime(dir);
            // tryAcquire는 가져갈 수 없으면 현재 락만 돌려주므로 peek를 먼저 하지 않는다(원격 왕복 한 번을 아낀다).
            Instant start = clock.instant();
            LockState failed = unhealthy;
            Acquisition acquisition = lock.tryAcquire(address, healthAddress, failed);
            if (acquisition.acquired()) localLock = acquisition.lock();
            announce(acquisition.lock());
            if (!acquisition.acquired()) {
                if (acquisition.lock().state() == LockState.State.MAINTENANCE) {
                    throw new IOException("월드 관리 작업 중입니다. 작업이 끝난 뒤 다시 접속하세요.");
                }
                return parse(acquisition.lock().address());
            }
            try (WriterGuard preparing = new WriterGuard(lock)) {
                reservation = preparing;
                if (failed != null && failed.hosting()) {
                    log.accept("writer 응답이 없어 락을 인수했습니다. 기존 writer 종료를 위해 30초 기다립니다.");
                    if (closed.await(WriterGuard.TAKEOVER_WAIT.toMillis(), TimeUnit.MILLISECONDS) || !preparing.valid()) {
                        throw new IOException("인수 대기 중 종료되었거나 소유권을 잃었습니다");
                    }
                }
                log.accept("이 PC가 호스트가 됐습니다(epoch " + acquisition.lock().epoch() + "). 락 획득 "
                        + HostSession.seconds(start, clock.instant()) + ". Paper를 시작합니다.");
                preparePaperForWorld();
                session = new HostSession(lock, dir, jar, paperPort, handoffPort, node, timing, clock, log, console);
                session.start();
            } finally { reservation = null; }
            return awaitLocalPaper();
        } catch (org.eclipse.jgit.api.errors.GitAPIException e) {
            throw new IOException("원격 저장소를 확인하지 못했습니다", e);
        } catch (IOException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("호스트를 시작하지 못했습니다", e);
        } finally {
            if (session == null || !session.isRunning()) localLock = null;
        }
    }

    /**
     * 락을 잡아 받은 최신 월드가 정한 Paper를 준비한다. 노드가 켜져 있는 동안 누가 upgrade-paper로 버전을 올렸을 수 있고,
     * 옛 Paper로 새 버전에서 열린 월드를 열면 월드가 망가질 수 있다. 준비하지 못하면 락을 돌려준다.
     */
    private void preparePaperForWorld() throws IOException, InterruptedException {
        try {
            PaperBuild current = WorldTemplate.paperBuild(dir);
            PluginManager.verifyRuntime(dir);
            if (current.equals(build)) return;
            PaperDistribution.checkSupported(current, log);
            log.accept("월드의 버전이 바뀌었습니다: 마인크래프트 " + current.version() + "(Paper " + current + ").");
            jar = PaperDistribution.official().ensureDownloaded(cacheDir, current);
            build = current;
        } catch (IOException | InterruptedException | RuntimeException e) {
            try {
                lock.releaseWithoutWorld();
            } catch (org.eclipse.jgit.api.errors.GitAPIException | IOException releaseFailure) {
                e.addSuppressed(releaseFailure);
            }
            throw e;
        }
    }

    /**
     * 서버 목록 확인용 현재 호스트. 서버 목록은 자주 확인하므로 git을 보지 않고 관찰해 둔 락 상태를 쓴다.
     * 호스트를 새로 띄우지 않으며, 지금 접속할 수 있는 호스트가 없으면 null.
     */
    private InetSocketAddress currentHost() {
        HostSession current = session;
        if (current != null && current.isRunning()) {
            return current.isReady() ? new InetSocketAddress(InetAddress.getLoopbackAddress(), paperPort) : null;
        }
        LockState seen = lastAnnounced;
        if (seen == null || !seen.hosting() || seen.equals(localLock) || seen.equals(unhealthy)) {
            return null;
        }
        try {
            return parse(seen.address());
        } catch (IOException e) {
            return null;
        }
    }

    private InetSocketAddress awaitLocalPaper() throws IOException {
        if (!session.awaitReady(ROUTE_WAIT)) {
            throw new IOException("Paper가 아직 기동 중입니다");
        }
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), paperPort);
    }

    private void observeLoop() {
        while (closed.getCount() > 0) {
            try {
                HostSession current = session;
                if (current == null || !current.isRunning()) {
                    synchronized (this) {
                        localLock = null;
                        PluginManager.verifyRuntime(dir);
                        announce(lock.peek().lock());
                    }
                }
                closed.await(timing.poll().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.accept("원격 저장소를 확인하지 못했습니다: " + e.getMessage());
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
        if (current.state() == LockState.State.MAINTENANCE) {
            log.accept("월드 관리 작업 중입니다(" + current.owner() + ").");
        } else if (current.hosting() && !current.equals(localLock)) {
            log.accept("지금 호스트는 " + current.owner() + "입니다. 접속하면 그쪽으로 이어집니다.");
        } else if (current.state() == LockState.State.FREE) {
            log.accept("지금은 호스트가 없습니다. 접속하면 이 PC가 호스트가 됩니다.");
        }
    }

    private void healthLoop(Proxy proxy) {
        LockState checking = null;
        int failures = 0;
        while (closed.getCount() > 0) {
            LockState seen = lastAnnounced;
            if (!java.util.Objects.equals(seen, checking)) {
                checking = seen;
                failures = 0;
                unhealthy = null;
            }
            if (seen != null && seen.state() == LockState.State.GUARDED && !seen.equals(localLock)
                    && !seen.healthAddress().isEmpty()) {
                if (HealthCheck.probe(seen.healthAddress(), seen, proxy, Duration.ofSeconds(2))) {
                    failures = 0;
                    unhealthy = null;
                } else if (++failures >= 3) {
                    if (unhealthy == null) log.accept("writer 상태 확인이 연속 실패했습니다. 재접속하면 인수를 시도합니다.");
                    unhealthy = seen;
                }
            }
            try { if (closed.await(5, TimeUnit.SECONDS)) return; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private LockState healthState() {
        HostSession current = session;
        WriterGuard preparing = reservation;
        return (current != null && current.isRunning() && current.healthy()
                || preparing != null && preparing.valid()) ? localLock : null;
    }

    /** tailnet 이름은 DNS 이름 규칙을 따라야 해서 영문 소문자, 숫자, '-'만 남긴다. */
    static String tailnetName(String nodeName) {
        String name = nodeName.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return "mcd-" + (name.isEmpty() ? Integer.toHexString(nodeName.hashCode()) : name);
    }

    private static InetSocketAddress parse(String hostPort) throws IOException {
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0) throw new IOException("호스트 주소가 없습니다");
        return new InetSocketAddress(hostPort.substring(0, colon), Integer.parseInt(hostPort.substring(colon + 1)));
    }
}
