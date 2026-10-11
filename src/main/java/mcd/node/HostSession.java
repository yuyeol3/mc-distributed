package mcd.node;

import mcd.lock.HostLock;
import mcd.lock.HostLock.PushOutcome;
import mcd.server.PaperServer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
    private final String node;
    private final Timing timing;
    private final InstantSource clock;
    private final Consumer<String> log;
    private final PrintStream console;

    private final CountDownLatch stopRequested = new CountDownLatch(1);
    private final CountDownLatch ended = new CountDownLatch(1);
    private volatile PaperServer paper;

    HostSession(HostLock lock, Path dir, Path jar, int paperPort, String node, Timing timing, InstantSource clock,
                Consumer<String> log, PrintStream console) {
        this.lock = lock;
        this.dir = dir;
        this.jar = jar;
        this.paperPort = paperPort;
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

    boolean isRunning() {
        return ended.getCount() > 0;
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
            log.accept("호스트 관리 중 오류: " + e + ". 락은 TTL이 지나면 다른 노드가 이어받는다.");
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
                log.accept("락을 갱신하지 못한 채 lease가 끝나간다. 다른 노드가 이어받을 수 있으니 서버를 내린다.");
                paper.stop(Duration.ofMinutes(2));
                return false;
            }
            Instant now = clock.instant();
            if (!paper.isReady()) {
                if (!now.isBefore(started.plus(PAPER_START_TIMEOUT))) {
                    throw new IllegalStateException("Paper가 " + PAPER_START_TIMEOUT + " 안에 기동하지 않았다");
                }
            } else {
                if (!announced) {
                    log.accept("Paper가 준비됐다.");
                    announced = true;
                    lastPlayerSeen = now;
                }
                if (!now.isBefore(lastPlayerCheck.plus(timing.poll()))) {
                    lastPlayerCheck = now;
                    if (paper.onlinePlayers() > 0) {
                        lastPlayerSeen = now;
                    } else if (!now.isBefore(lastPlayerSeen.plus(timing.idle()))) {
                        log.accept("아무도 접속하지 않은 지 " + timing.idle().toSeconds() + "초가 지나 서버를 내린다.");
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
                log.accept("다른 노드가 락을 가져갔다. 월드가 갈라지지 않도록 서버를 내린다.");
                paper.stop(Duration.ofMinutes(2));
                return false;
            }
            if (outcome == PushOutcome.FAILED) {
                log.accept("원격 저장소에 올리지 못했다. 다음 주기에 다시 시도한다.");
            }
            stopRequested.await(1, TimeUnit.SECONDS);
        }

        // Windows에서는 Ctrl+C가 Paper에도 전달된다. Paper는 저장하고 끝나지만 종료 코드가 0이 아니므로, 종료를 요청한 경우는 정상으로 본다.
        if (!paper.isAlive() && !stopped() && !paper.exitedCleanly()) {
            // 정상 종료(stop)를 거치지 않았으므로 쓰다 만 월드 파일이 있을 수 있다. 마지막 스냅샷까지만 공유한다.
            log.accept("Paper가 예기치 않게 종료됐다. 마지막 스냅샷으로 락을 반납하고, 남은 변경은 다음 실행 때 recovery 브랜치에 보관한다.");
            lock.releaseWithoutWorld();
            return false;
        }
        if (paper.isAlive()) {
            paper.stop(Duration.ofMinutes(2));
        }
        return true;
    }

    private void release() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            PushOutcome outcome = lock.release();
            if (outcome == PushOutcome.OK) {
                log.accept("월드를 올리고 락을 반납했다.");
                return;
            }
            if (outcome == PushOutcome.REJECTED) {
                log.accept("그새 다른 노드가 락을 가져갔다. 이번 변경은 다음 실행 때 recovery 브랜치에 보관된다.");
                return;
            }
            Thread.sleep(5000);
        }
        log.accept("월드를 올리지 못했다. 다음에 mcd play를 실행하면 다시 올린다.");
    }

    private boolean stopped() {
        return stopRequested.getCount() == 0;
    }
}
