package mcd.node;

import mcd.lock.HostLock;
import mcd.lock.HostLock.PushOutcome;
import mcd.server.PaperServer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 락을 잡은 뒤 이 PC에서 Paper를 돌리는 한 번의 호스트 기간. 자기 스레드에서 heartbeat, 주기 스냅샷, 펜싱,
 * 빈 서버 종료를 처리하고, 끝나면 월드를 올리고 락을 반납한다.
 */
final class HostSession {

    private static final Duration PAPER_START_TIMEOUT = Duration.ofMinutes(5);

    private final HostLock lock;
    private final Path dir;
    private final Path jar;
    private final int paperPort;
    private final int handoffPort;
    private final String node;
    private final Timing timing;
    private final InstantSource clock;
    private final Consumer<String> log;
    private final PrintStream console;

    private final CountDownLatch stopRequested = new CountDownLatch(1);
    private final CountDownLatch ended = new CountDownLatch(1);
    private volatile PaperServer paper;
    /** 마지막으로 센 접속 인원(관찰 주기마다 갱신). */
    private volatile int players;

    HostSession(HostLock lock, Path dir, Path jar, int paperPort, int handoffPort, String node, Timing timing,
                InstantSource clock, Consumer<String> log, PrintStream console) {
        this.lock = lock;
        this.dir = dir;
        this.jar = jar;
        this.paperPort = paperPort;
        this.handoffPort = handoffPort;
        this.node = node;
        this.timing = timing;
        this.clock = clock;
        this.log = log;
        this.console = console;
    }

    /** Paper를 띄우고 관리 스레드를 시작한다. 기동을 기다리지 않는다(기다리는 동안에도 heartbeat가 필요하므로). */
    void start() throws IOException {
        paper = PaperServer.launch(dir, jar, paperPort, console);
        Thread.ofPlatform().name("host-session").start(this::run);
    }

    boolean awaitReady(Duration timeout) {
        return paper.awaitReady(timeout);
    }

    boolean isReady() {
        return paper.isReady();
    }

    boolean isRunning() {
        return ended.getCount() > 0;
    }

    int players() {
        return players;
    }

    void requestStop() {
        stopRequested.countDown();
    }

    void awaitEnd(Duration timeout) throws InterruptedException {
        ended.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 호스트인 동안 사용자 콘솔 입력을 Paper로 넘긴다. */
    void consoleCommand(String line) throws IOException {
        paper.command(line);
    }

    private void run() {
        try {
            if (host()) {
                release();
            }
        } catch (Exception e) {
            log.accept("호스트 관리 중 오류: " + e + ". 락은 TTL이 지나면 다른 노드가 이어받습니다.");
        } finally {
            paper.close();
            ended.countDown();
        }
    }

    /** 반납해도 되는 정상 종료면 true. 락을 잃었거나 잃었을 수 있으면 false(반납하지 않는다). */
    private boolean host() throws Exception {
        Instant started = clock.instant();
        Instant lastHeartbeat = started;
        Instant lastSnapshot = started;
        Instant lastPlayerSeen = started;
        Instant lastPlayerCheck = started;
        boolean announced = false;

        while (!stopped() && paper.isAlive()) {
            if (lock.shouldStop()) {
                log.accept("락을 갱신하지 못한 채 lease가 끝나 갑니다. 다른 노드가 이어받을 수 있으니 서버를 내립니다.");
                paper.stop(Duration.ofMinutes(2));
                return false;
            }
            Instant now = clock.instant();
            if (!paper.isReady()) {
                if (!now.isBefore(started.plus(PAPER_START_TIMEOUT))) {
                    throw new IllegalStateException("Paper가 " + PAPER_START_TIMEOUT + " 안에 기동하지 않았습니다");
                }
            } else {
                if (!announced) {
                    log.accept("Paper가 준비됐습니다(기동 " + seconds(started, now) + ").");
                    announced = true;
                    lastPlayerSeen = now;
                }
                if (!now.isBefore(lastPlayerCheck.plus(timing.poll()))) {
                    lastPlayerCheck = now;
                    players = paper.onlinePlayers();
                    if (players > 0) {
                        lastPlayerSeen = now;
                    } else if (!now.isBefore(lastPlayerSeen.plus(timing.idle()))) {
                        log.accept("아무도 접속하지 않은 지 " + timing.idle().toSeconds() + "초가 지나 서버를 내립니다.");
                        break;
                    }
                }
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
                log.accept("다른 노드가 락을 가져갔습니다. 월드가 갈라지지 않도록 서버를 내립니다.");
                paper.stop(Duration.ofMinutes(2));
                return false;
            }
            if (outcome == PushOutcome.FAILED) {
                log.accept("원격 저장소에 올리지 못했습니다. 다음 주기에 다시 시도합니다.");
            }
            stopRequested.await(1, TimeUnit.SECONDS);
        }

        // Windows에서는 Ctrl+C가 Paper에도 전달된다. Paper는 저장하고 끝나지만 종료 코드가 0이 아니므로, 종료를 요청한 경우는 정상으로 본다.
        if (!paper.isAlive() && !stopped() && !paper.exitedCleanly()) {
            // 정상 종료(stop)를 거치지 않았으므로 쓰다 만 월드 파일이 있을 수 있다. 마지막 스냅샷까지만 공유한다.
            log.accept("Paper가 예기치 않게 종료됐습니다. 마지막 스냅샷으로 락을 반납하고, 남은 변경은 다음 실행 때 recovery 브랜치에 보관합니다.");
            lock.releaseWithoutWorld();
            return false;
        }
        if (stopped() && paper.isAlive() && paper.isReady() && paper.onlinePlayers() > 0 && handOver()) {
            return false;
        }
        if (paper.isAlive()) {
            paper.stop(Duration.ofMinutes(2));
        }
        return true;
    }

    /**
     * 다른 사람이 접속해 있는데 호스트가 나가면 서버를 넘긴다. 마지막 스냅샷과 함께 락을 반납하고 곧바로 모두를
     * transfer로 각자 PC의 mcd 중계(localhost:handoffPort)로 보낸다. 중계들은 비어 있는 락을 두고 겨루고(하나만
     * 이긴다), 진 쪽 중계는 이긴 쪽 Paper가 열릴 때까지 연결을 다시 시도한다. 기존 서버에서 바로 빠지므로 읽기
     * 전용 구간이 필요 없다. 반납에 실패하면 false를 돌려주고 평소처럼 종료한다.
     */
    private boolean handOver() throws Exception {
        log.accept("다른 사람이 접속해 있어서 서버를 넘깁니다.");
        paper.command("say 호스트가 나가서 서버를 옮깁니다. 잠시 뒤 자동으로 다시 접속됩니다.");
        Instant start = clock.instant();
        paper.pauseSavingAndFlush();
        // 반납 push가 끝날 때까지(5~7초, 실험 E7)의 플레이는 넘긴 월드에 들어가지 않으므로 그동안 얼려 둔다.
        paper.freezePlayers("서버를 옮기는 중");
        if (lock.release() != PushOutcome.OK) {
            log.accept("락을 반납하지 못해서 서버를 넘기지 못했습니다.");
            paper.unfreezePlayers();
            paper.resumeSaving();
            return false;
        }
        log.accept("월드와 락을 올렸습니다(저장과 push " + seconds(start, clock.instant()) + "). 플레이어를 보냅니다.");
        paper.command("transfer localhost " + handoffPort + " @a");
        Thread.sleep(2000);
        paper.stop(Duration.ofMinutes(2));
        lock.discardLocalChanges();
        log.accept("월드를 올리고 서버를 넘겼습니다.");
        logUsage();
        return true;
    }

    private void release() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            PushOutcome outcome = lock.release();
            if (outcome == PushOutcome.OK) {
                log.accept("월드를 올리고 락을 반납했습니다.");
                logUsage();
                return;
            }
            if (outcome == PushOutcome.REJECTED) {
                log.accept("그새 다른 노드가 락을 가져갔습니다. 이번 변경은 다음 실행 때 recovery 브랜치에 보관됩니다.");
                return;
            }
            Thread.sleep(5000);
        }
        log.accept("월드를 올리지 못했습니다. 다음에 mcd play를 실행하면 다시 올립니다.");
    }

    /** GitHub 사용량을 수치로 보기 위해 호스트 기간이 끝날 때 push 횟수와 로컬 저장소 크기를 남긴다. */
    private void logUsage() {
        long bytes;
        try (Stream<Path> files = Files.walk(dir.resolve(".git"))) {
            bytes = files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException e) {
            bytes = -1;
        }
        log.accept("이번 호스트 기간: push " + lock.mainPushes() + "회, 로컬 저장소 " + bytes / (1024 * 1024) + "MB");
    }

    private boolean stopped() {
        return stopRequested.getCount() == 0;
    }

    /** 이관 시간을 구간별로 보기 위해 로그에 남기는 경과 시간. */
    static String seconds(Instant from, Instant to) {
        return String.format(java.util.Locale.ROOT, "%.1f초", Duration.between(from, to).toMillis() / 1000.0);
    }
}
