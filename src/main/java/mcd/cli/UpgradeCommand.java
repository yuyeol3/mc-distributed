package mcd.cli;

import mcd.lock.HostLock;
import mcd.lock.HostLock.Acquisition;
import mcd.lock.HostLock.PushOutcome;
import mcd.node.Timing;
import mcd.server.PaperBuild;
import mcd.server.PaperDistribution;
import mcd.world.WorldRepository;
import mcd.world.WorldTemplate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.function.Consumer;

/**
 * `mcd upgrade-paper`: 월드가 쓰는 Paper 빌드를 올린다. 락을 잡은 상태에서 버전 파일을 바꿔 반납하므로, 호스트가
 * 있는 동안에는 바꿀 수 없다. 월드는 한 번 새 버전으로 열리면 되돌릴 수 없어서 다운그레이드는 거부한다.
 */
public final class UpgradeCommand {

    private UpgradeCommand() {}

    /** target은 "버전-빌드" 또는 버전만(그 버전의 최신 안정 빌드). 바꾼 빌드를 돌려준다. */
    public static PaperBuild run(Path dir, String remote, String node, String target, Path cacheDir, TransportConfigCallback auth,
                          PaperDistribution paper, Consumer<String> log) throws Exception {
        try (Git git = WorldRepository.openOrClone(dir, remote, auth)) {
            Timing timing = Timing.fromTtl(Timing.DEFAULT_TTL);
            HostLock lock = new HostLock(git, node, InstantSource.system(), timing.ttl(), timing.margin(), auth);
            Acquisition acquisition = lock.tryAcquire("");
            if (!acquisition.acquired()) {
                throw new IllegalStateException("지금 " + acquisition.lock().owner() + "이(가) 호스트라서 바꿀 수 없다. 모두 나간 뒤에 다시 한다.");
            }
            // 락을 잡은 뒤의 작업 트리가 최신 월드다. 여기서 실패하면 바꾸지 않고 락만 돌려준다.
            PaperBuild next;
            try {
                PaperBuild current = WorldTemplate.paperBuild(dir);
                next = target.matches(".*-\\d+$") ? PaperBuild.parse(target) : paper.latestStable(target);
                if (next.compareTo(current) <= 0) {
                    throw new IllegalStateException("지금 빌드(" + current + ")보다 새 빌드만 쓸 수 있다. 월드는 옛 버전으로 되돌릴 수 없다.");
                }
                PaperDistribution.checkSupported(next, log);
                // 받을 수 있는지, 이 앱의 Java로 돌릴 수 있는지 미리 확인하고 캐시해 둔다.
                paper.ensureDownloaded(cacheDir, next);
            } catch (Exception e) {
                lock.release();
                throw e;
            }
            WorldTemplate.writePaperBuild(dir, next);
            if (lock.release() != PushOutcome.OK) {
                throw new IllegalStateException("바꾼 버전을 올리지 못했다. 다시 시도한다.");
            }
            log.accept("월드의 Paper를 " + next + "로 올렸다. 모두 마인크래프트 클라이언트를 " + next.version() + "로 맞춰야 한다.");
            return next;
        }
    }
}
