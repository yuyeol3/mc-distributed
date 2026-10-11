package mcd.lock;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * 월드 repo의 main 브랜치를 락으로 쓰는 호스트 소유권 프로토콜.
 *
 * <p>모든 상태 변경은 원격 main 위에 커밋 하나를 쌓고 force 없이 push하는 것이다(실험 E2로 CAS 동작 확인).
 * 다른 노드가 락을 가져가면 main이 앞으로 나가므로, 이전 호스트의 push는 git이 거절한다.
 *
 * <p>작업 트리는 Paper 서버 폴더 자체다. 월드 파일은 {@link #snapshot}과 {@link #release}에서만 stage되고,
 * heartbeat는 index를 건드리지 않는 빈 커밋이다.
 */
public final class HostLock {

    private static final String MAIN = "refs/heads/main";
    private static final String REMOTE_MAIN = "refs/remotes/origin/main";

    public enum PushOutcome {
        OK,
        /** 원격 main이 앞서 있음. 다른 노드가 락을 가져갔으므로 즉시 서버를 내려야 한다. */
        REJECTED,
        /** 네트워크 오류 등. {@link #shouldStop}이 참이 될 때까지는 계속 시도할 수 있다. */
        FAILED
    }

    public record Acquisition(boolean acquired, LockState lock) {}

    private final Git git;
    private final String node;
    private final InstantSource clock;
    private final Duration ttl;
    private final Duration margin;
    private final CredentialsProvider credentials;

    private ObjectId observedHead;
    private Instant observedSince;
    private Instant lastPushStart;

    public HostLock(Git git, String node, InstantSource clock, Duration ttl, Duration margin, CredentialsProvider credentials) {
        this.git = git;
        this.node = node;
        this.clock = clock;
        this.ttl = ttl;
        this.margin = margin;
        this.credentials = credentials;
    }

    /**
     * 락 획득을 시도한다. 성공하면 작업 트리가 최신 월드가 된다.
     * 실패하면 현재 락 상태를 돌려준다. 다른 노드의 lease 만료는 이 메서드를 반복 호출하며 같은 main을
     * 자기 시계로 ttl 이상 관찰했을 때만 판정하므로, 다른 PC와 시계가 달라도 영향이 없다.
     */
    public Acquisition tryAcquire(String address) throws GitAPIException, IOException {
        fetch();
        reconcileLocalChanges();
        ObjectId remoteHead = remoteHead();
        LockState current = lockAt(remoteHead);

        if (!canTake(current, remoteHead)) {
            return new Acquisition(false, current);
        }

        git.reset().setMode(ResetType.HARD).setRef(remoteHead.name()).call();
        LockState next = current.hostedBy(node, address);
        next.write(workTree());
        git.add().addFilepattern(LockState.FILE_NAME).call();
        git.commit().setMessage("acquire " + node + " epoch " + next.epoch()).setSign(false).call();

        if (push(MAIN) == PushOutcome.OK) {
            return new Acquisition(true, next);
        }
        // 동시에 획득하려던 다른 노드가 이겼을 수 있으므로, 시도 전 상태가 아니라 지금 원격 상태를 돌려준다.
        fetch();
        ObjectId latest = remoteHead();
        git.reset().setMode(ResetType.HARD).setRef(latest.name()).call();
        return new Acquisition(false, lockAt(latest));
    }

    /** 락이 살아 있음을 알리는 빈 커밋을 push한다. 작업 중인 월드 파일은 커밋하지 않는다. */
    public PushOutcome heartbeat() throws GitAPIException {
        git.commit().setMessage("heartbeat " + node).setAllowEmpty(true).setSign(false).call();
        return push(MAIN);
    }

    /** 현재 월드 파일 전체를 커밋하고 push한다. 호출 전에 서버 저장이 끝나 있어야 한다. */
    public PushOutcome snapshot(String message) throws GitAPIException {
        commitWorld(message);
        return pushCommitted();
    }

    /**
     * 현재 월드 파일 전체를 로컬에만 커밋한다. 파일이 고정돼 있어야 하는 건 이 단계뿐이라, 서버 저장을 멈춘 시간을
     * 줄이려면 이것만 저장을 멈춘 동안 하고 {@link #pushCommitted}는 저장을 다시 켠 뒤에 한다(실험 E3).
     */
    public void commitWorld(String message) throws GitAPIException {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        git.commit().setMessage(message).setAllowEmpty(true).setSign(false).call();
    }

    public PushOutcome pushCommitted() {
        return push(MAIN);
    }

    /** 월드와 FREE 상태를 커밋 하나로 push한다. 전부 반영되거나 전부 실패한다. */
    public PushOutcome release() throws GitAPIException, IOException {
        LockState.read(workTree()).freed().write(workTree());
        return snapshot("release " + node);
    }

    /**
     * 락만 반납하고 작업 트리의 월드 변경은 올리지 않는다. Paper가 비정상 종료해서 월드 파일을 믿을 수 없을 때 쓴다.
     * 남은 변경은 다음 {@link #tryAcquire}에서 recovery 브랜치로 간다.
     */
    public PushOutcome releaseWithoutWorld() throws GitAPIException, IOException {
        LockState.read(workTree()).freed().write(workTree());
        git.add().addFilepattern(LockState.FILE_NAME).call();
        git.commit().setMessage("release " + node + " without world").setSign(false).call();
        return push(MAIN);
    }

    /** 마지막으로 성공한 push를 시작한 뒤 ttl - margin이 지나면, 다른 노드가 이미 락을 가져갔을 수 있다. */
    public boolean shouldStop() {
        return lastPushStart == null || !clock.instant().isBefore(lastPushStart.plus(ttl).minus(margin));
    }

    private boolean canTake(LockState current, ObjectId remoteHead) {
        if (current.state() == LockState.State.FREE || current.owner().equals(node)) {
            return true;
        }
        Instant now = clock.instant();
        if (!remoteHead.equals(observedHead)) {
            observedHead = remoteHead;
            observedSince = now;
            return false;
        }
        return !now.isBefore(observedSince.plus(ttl));
    }

    /**
     * 이전 실행이 남긴 커밋이나 커밋하지 않은 월드 변경을 정리한다.
     * 원격 락의 주인이 나이고 원격이 그대로면 이어서 push한다(그동안 아무도 락을 가져가지 않았다는 뜻).
     * 그 밖의 경우는 recovery 브랜치에만 올리고 main은 원격을 따른다.
     */
    private void reconcileLocalChanges() throws GitAPIException, IOException {
        Repository repo = git.getRepository();
        ObjectId remoteHead = remoteHead();
        ObjectId localHead = repo.resolve("HEAD");

        if (!git.status().call().isClean()) {
            commitWorld("unsaved changes found at startup by " + node);
            localHead = repo.resolve("HEAD");
        }
        if (localHead == null || localHead.equals(remoteHead)) {
            git.reset().setMode(ResetType.HARD).setRef(remoteHead.name()).call();
            return;
        }

        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit local = walk.parseCommit(localHead);
            RevCommit remote = walk.parseCommit(remoteHead);
            if (walk.isMergedInto(local, remote)) {
                git.reset().setMode(ResetType.HARD).setRef(remoteHead.name()).call();
                return;
            }
            LockState remoteLock = lockAt(remoteHead);
            boolean stillOwner = remoteLock.state() == LockState.State.HOSTING && remoteLock.owner().equals(node);
            if (stillOwner && walk.isMergedInto(remote, local) && push(MAIN) == PushOutcome.OK) {
                return;
            }
        }

        long epoch = lockAt(localHead).epoch();
        String branch = "refs/heads/recovery/" + node + "-" + epoch + "-" + localHead.abbreviate(7).name();
        push(branch);
        fetch();
        git.reset().setMode(ResetType.HARD).setRef(remoteHead().name()).call();
    }

    private void fetch() throws GitAPIException {
        git.fetch().setRemote("origin").setCredentialsProvider(credentials).call();
    }

    private PushOutcome push(String targetRef) {
        Instant start = clock.instant();
        try {
            Iterable<PushResult> results = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("HEAD:" + targetRef))
                    .setCredentialsProvider(credentials)
                    .call();
            RemoteRefUpdate.Status status = results.iterator().next().getRemoteUpdate(targetRef).getStatus();
            if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                return PushOutcome.REJECTED;
            }
            if (targetRef.equals(MAIN)) {
                lastPushStart = start;
            }
            return PushOutcome.OK;
        } catch (GitAPIException e) {
            return PushOutcome.FAILED;
        }
    }

    private ObjectId remoteHead() throws IOException {
        ObjectId id = git.getRepository().resolve(REMOTE_MAIN);
        if (id == null) {
            throw new IllegalStateException("원격에 main이 없다. 월드 repo가 초기화되지 않았다.");
        }
        return id;
    }

    private LockState lockAt(ObjectId commitId) throws IOException {
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo);
             TreeWalk tree = TreeWalk.forPath(repo, LockState.FILE_NAME, walk.parseCommit(commitId).getTree())) {
            if (tree == null) {
                return LockState.initial();
            }
            ObjectLoader loader = repo.open(tree.getObjectId(0));
            return LockState.parse(loader.getBytes());
        }
    }

    private Path workTree() {
        return git.getRepository().getWorkTree().toPath();
    }
}
