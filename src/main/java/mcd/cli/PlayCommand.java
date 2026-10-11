package mcd.cli;

import mcd.lock.HostLock;
import mcd.lock.HostLock.Acquisition;
import mcd.lock.HostLock.PushOutcome;
import mcd.lock.LockState;
import mcd.server.PaperDistribution;
import mcd.server.PaperServer;
import mcd.world.WorldTemplate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 월드에 들어간다. 락을 잡으면 이 PC가 Paper 호스트가 되고, 못 잡으면 현재 호스트의 주소를 안내하면서
 * 락이 풀리거나 만료되기를 기다린다.
 */
final class PlayCommand {

    /**
     * 소유권 프로토콜의 시간 값. 모두 TTL에 비례한다. 기본 TTL 10분이면 heartbeat 2분, 스냅샷 10분, 확인 주기 30초로,
     * GitHub 이용 정책을 고려해 push 빈도를 낮게 잡은 값이다. 테스트에서는 TTL을 줄여서 쓴다.
     */
    record Timing(Duration ttl, Duration margin, Duration poll, Duration heartbeat, Duration snapshot) {
        static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

        static Timing fromTtl(Duration ttl) {
            return new Timing(ttl, ttl.dividedBy(5), ttl.dividedBy(20), ttl.dividedBy(5), ttl);
        }
    }

    private static final Duration PAPER_START_TIMEOUT = Duration.ofMinutes(5);

    private final String remote;
    private final Path dir;
    private final String node;
    private final int port;
    private final String address;
    private final boolean acceptEula;
    private final Path cacheDir;
    private final CredentialsProvider credentials;
    private final Timing timing;
    private final InstantSource clock = InstantSource.system();

    private final CountDownLatch stopRequested = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private volatile PaperServer server;

    PlayCommand(String remote, Path dir, String node, int port, String address, boolean acceptEula, Path cacheDir,
                CredentialsProvider credentials, Timing timing) {
        this.remote = remote;
        this.dir = dir;
        this.node = node;
        this.port = port;
        this.address = address;
        this.acceptEula = acceptEula;
        this.cacheDir = cacheDir;
        this.credentials = credentials;
        this.timing = timing;
    }

    void run() throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            requestStop();
            awaitQuietly(finished, Duration.ofMinutes(3));
        }));
        Thread.ofVirtual().start(this::readConsole);
        try {
            play();
        } finally {
            finished.countDown();
        }
    }

    /** "stop" 입력이나 Ctrl+C와 같다. 호스트라면 저장하고 락을 반납한 뒤 끝난다. */
    void requestStop() {
        stopRequested.countDown();
    }

    private void play() throws Exception {
        try (Git git = openOrClone()) {
            prepareEula();
            Path jar = PaperDistribution.ensureDownloaded(cacheDir);
            HostLock lock = new HostLock(git, node, clock, timing.ttl(), timing.margin(), credentials);

            LockState lastSeen = null;
            while (!stopped()) {
                Acquisition acquisition = lock.tryAcquire(address);
                if (acquisition.acquired()) {
                    host(lock, jar, acquisition.lock());
                    return;
                }
                LockState current = acquisition.lock();
                if (!current.equals(lastSeen)) {
                    log("지금 호스트는 " + current.owner() + "이다. 마인크래프트에서 " + current.address()
                            + "로 접속하면 된다. 호스트가 나가면 이 PC가 이어받는다.");
                    lastSeen = current;
                }
                stopRequested.await(timing.poll().toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private void host(HostLock lock, Path jar, LockState acquired) throws Exception {
        WorldTemplate.checkPaperBuild(dir);
        log("이 PC가 호스트가 됐다(epoch " + acquired.epoch() + "). Paper를 시작한다.");
        // 기동을 기다리는 동안에도 heartbeat를 보내야 하므로 기동 완료는 루프 안에서 확인한다.
        PaperServer paper = PaperServer.launch(dir, jar, port, System.out);
        server = paper;

        Instant started = clock.instant();
        Instant lastHeartbeat = started;
        Instant lastSnapshot = started;
        boolean announced = false;
        try {
            while (!stopped() && paper.isAlive()) {
                if (lock.shouldStop()) {
                    log("락을 갱신하지 못한 채 lease가 끝나간다. 다른 노드가 이어받을 수 있으니 서버를 내린다.");
                    paper.stop(Duration.ofMinutes(2));
                    return;
                }
                Instant now = clock.instant();
                if (!paper.isReady() && !now.isBefore(started.plus(PAPER_START_TIMEOUT))) {
                    throw new IllegalStateException("Paper가 " + PAPER_START_TIMEOUT + " 안에 기동하지 않았다");
                }
                if (paper.isReady() && !announced) {
                    log("다른 사람은 " + address + "로 접속하면 된다. 끝내려면 stop을 입력한다.");
                    announced = true;
                }

                PushOutcome outcome = null;
                if (paper.isReady() && !now.isBefore(lastSnapshot.plus(timing.snapshot()))) {
                    paper.pauseSavingAndFlush();
                    try {
                        lock.commitWorld("snapshot by " + node);
                    } finally {
                        paper.resumeSaving();
                    }
                    outcome = lock.pushCommitted();
                    lastSnapshot = now;
                    lastHeartbeat = now;
                } else if (!now.isBefore(lastHeartbeat.plus(timing.heartbeat()))) {
                    outcome = lock.heartbeat();
                    lastHeartbeat = now;
                }
                if (outcome == PushOutcome.REJECTED) {
                    log("다른 노드가 락을 가져갔다. 월드가 갈라지지 않도록 서버를 내린다.");
                    paper.stop(Duration.ofMinutes(2));
                    return;
                }
                if (outcome == PushOutcome.FAILED) {
                    log("원격 저장소에 올리지 못했다. 다음 주기에 다시 시도한다.");
                }
                stopRequested.await(1, TimeUnit.SECONDS);
            }
            // Windows에서는 Ctrl+C가 Paper에도 전달된다. Paper는 저장하고 끝나지만 종료 코드가 0이 아니므로, 종료를 요청한 경우는 정상으로 본다.
            if (!paper.isAlive() && !stopped() && !paper.exitedCleanly()) {
                // 정상 종료(stop)를 거치지 않았으므로 쓰다 만 월드 파일이 있을 수 있다. 마지막 스냅샷까지만 공유한다.
                log("Paper가 예기치 않게 종료됐다. 마지막 스냅샷으로 락을 반납하고, 남은 변경은 다음 실행 때 recovery 브랜치에 보관한다.");
                lock.releaseWithoutWorld();
                return;
            }
            if (paper.isAlive()) {
                paper.stop(Duration.ofMinutes(2));
            }
        } finally {
            server = null;
            paper.close();
        }
        release(lock);
    }

    private void release(HostLock lock) throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            PushOutcome outcome = lock.release();
            if (outcome == PushOutcome.OK) {
                log("월드를 올리고 락을 반납했다.");
                return;
            }
            if (outcome == PushOutcome.REJECTED) {
                log("그새 다른 노드가 락을 가져갔다. 이번 변경은 다음 실행 때 recovery 브랜치에 보관된다.");
                return;
            }
            Thread.sleep(5000);
        }
        log("월드를 올리지 못했다. 다음에 mcd play를 실행하면 다시 올린다.");
    }

    private Git openOrClone() throws Exception {
        if (Files.exists(dir.resolve(".git"))) {
            return Git.open(dir.toFile());
        }
        log(remote + "에서 월드를 받는다.");
        return Git.cloneRepository().setURI(remote).setDirectory(dir.toFile()).setCredentialsProvider(credentials).call();
    }

    private void prepareEula() throws IOException {
        if (acceptEula) {
            WorldTemplate.acceptEula(dir);
        } else if (!Files.exists(dir.resolve("eula.txt"))) {
            throw new IllegalStateException("Minecraft EULA(https://aka.ms/MinecraftEULA)에 동의하면 --accept-eula를 붙여 다시 실행한다.");
        }
    }

    /** 사용자 입력 중 stop은 직접 처리하고, 나머지는 호스트일 때 Paper 콘솔로 넘긴다. */
    private void readConsole() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().equals("stop")) {
                    requestStop();
                } else if (server != null) {
                    server.command(line);
                }
            }
        } catch (IOException ignored) {
            // 콘솔이 닫히면 더 읽을 것이 없다.
        }
    }

    private boolean stopped() {
        return stopRequested.getCount() == 0;
    }

    private static void awaitQuietly(CountDownLatch latch, Duration timeout) {
        try {
            latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String message) {
        System.out.println("[mcd] " + message);
    }
}
