package mcd.world;

import mcd.lock.HostLock;
import mcd.lock.HostLock.PushOutcome;
import mcd.lock.LockState;
import mcd.node.Timing;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import java.io.IOException;
import java.nio.file.*;
import java.time.InstantSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 관리 작업은 독립 사본에서 수행한다. 중단된 사본을 일반 Node recovery로 열지 않는다. */
public final class WorldAdmin {
    private final String remote;
    private final String node;
    private final TransportConfigCallback auth;

    public WorldAdmin(String remote, String node, TransportConfigCallback auth) {
        this.remote = remote; this.node = node; this.auth = auth;
    }

    @FunctionalInterface public interface Edit { void apply(Path directory) throws Exception; }

    public String edit(String description, Edit edit) throws Exception {
        Path temporary = Files.createTempDirectory("mcd-management-");
        boolean completed = true;
        try (Git git = Git.cloneRepository().setURI(remote).setDirectory(temporary.toFile())
                .setTransportConfigCallback(auth).call()) {
            WorldTemplate.paperBuild(temporary);
            Timing timing = Timing.fromTtl(Timing.DEFAULT_TTL);
            HostLock lock = new HostLock(git, node, InstantSource.system(), timing.ttl(), timing.margin(), auth);
            var acquired = lock.tryMaintain();
            if (!acquired.acquired()) throw new IllegalStateException("지금 " + acquired.lock().owner() + "이(가) 월드를 사용하거나 관리하고 있습니다. 서버가 종료된 뒤 다시 하세요.");
            completed = false;
            String backup = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID();
            var timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("management-heartbeat").factory());
            Object monitor = new Object();
            boolean[] finishing = {false};
            timer.scheduleWithFixedDelay(() -> {
                synchronized (monitor) {
                    if (finishing[0]) return;
                    try { lock.heartbeat(); } catch (Exception ignored) { }
                }
            }, timing.heartbeat().toSeconds(), timing.heartbeat().toSeconds(), TimeUnit.SECONDS);
            try {
                if (lock.backup(backup) != PushOutcome.OK) throw new IOException("백업을 원격에 보존하지 못했습니다");
                edit.apply(temporary);
                synchronized (monitor) {
                    finishing[0] = true;
                    if (lock.shouldStop() || !lock.confirmsOwnership()) throw new IOException("관리 락을 갱신하지 못했거나 소유권을 잃었습니다");
                    WorldTemplate.paperBuild(temporary);
                    // 별도 사본의 검증된 관리 변경에서만 실행 파일의 추가/수정/삭제를 stage한다.
                    git.add().addFilepattern(".").call();
                    git.add().setUpdate(true).addFilepattern(".").call();
                    if (lock.release() != PushOutcome.OK) {
                        // 전송 결과가 유실돼도 원격 ref로 결과를 확인한다.
                        ObjectId attempted = git.getRepository().resolve("HEAD");
                        git.fetch().setTransportConfigCallback(auth).call();
                        if (!attempted.equals(git.getRepository().resolve("origin/main"))) throw new IOException("변경을 게시하지 못했습니다");
                    }
                }
                completed = true;
                return backup;
            } catch (Exception failure) {
                synchronized (monitor) {
                    finishing[0] = true;
                    try {
                        // 부분 변경은 올리지 않는다. 현재 원격 소유권이 아직 내 것일 때만 락을 반납한다.
                        git.fetch().setTransportConfigCallback(auth).call();
                        git.reset().setMode(ResetType.HARD).setRef("origin/main").call();
                        if (LockState.read(temporary).equals(acquired.lock())) lock.releaseWithoutWorld();
                    } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                }
                throw new IOException(description + " 실패. 백업: " + backup + ", 작업 사본: " + temporary + ". " + failure.getMessage(), failure);
            } finally { timer.shutdownNow(); }
        } finally {
            if (completed) SafeFiles.deleteTree(temporary);
        }
    }

    public List<String> backups() throws Exception {
        return Git.lsRemoteRepository().setRemote(remote).setHeads(true).setTransportConfigCallback(auth).call().stream()
                .map(ref -> ref.getName()).filter(name -> name.startsWith("refs/heads/backup/"))
                .map(name -> name.substring("refs/heads/backup/".length())).sorted().toList();
    }

    public String replace(WorldImport.Prepared prepared) throws Exception {
        return edit("월드 교체", directory -> prepared.apply(directory, WorldTemplate.paperBuild(directory)));
    }

    public String addPlugin(Path file) throws Exception {
        PluginManager.inspect(file);
        return edit("플러그인 추가/교체", directory -> PluginManager.install(directory, file));
    }

    public String removePlugin(String name) throws Exception {
        return edit("플러그인 제거", directory -> PluginManager.remove(directory, name));
    }

    public List<PluginManager.Plugin> plugins() throws Exception {
        Path temporary = Files.createTempDirectory("mcd-plugin-list-");
        try (Git git = Git.cloneRepository().setURI(remote).setDirectory(temporary.toFile())
                .setTransportConfigCallback(auth).call()) {
            return PluginManager.list(temporary);
        } finally { SafeFiles.deleteTree(temporary); }
    }

    /** 백업 트리를 현재 epoch 위의 새 커밋으로 적용한다. */
    public String restore(String backup) throws Exception {
        if (!backup.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("잘못된 백업 ID");
        return edit("restore " + backup, directory -> {
            try (Git git = Git.open(directory.toFile())) {
                ObjectId commit = git.getRepository().resolve("refs/remotes/origin/backup/" + backup);
                if (commit == null) throw new IOException("백업을 찾을 수 없습니다: " + backup);
                try (RevWalk walk = new RevWalk(git.getRepository()); TreeWalk tree = new TreeWalk(git.getRepository())) {
                    tree.addTree(walk.parseCommit(git.getRepository().resolve("HEAD")).getTree());
                    tree.setRecursive(true);
                    while (tree.next()) {
                        if (!tree.getPathString().equals(LockState.FILE_NAME)) Files.deleteIfExists(SafeFiles.resolve(directory, tree.getPathString()));
                    }
                    tree.reset();
                    tree.addTree(walk.parseCommit(commit).getTree());
                    tree.setRecursive(true);
                    while (tree.next()) {
                        if (tree.getPathString().equals(LockState.FILE_NAME)) continue;
                        if (!tree.getFileMode(0).equals(org.eclipse.jgit.lib.FileMode.REGULAR_FILE)) throw new IOException("일반 파일이 아닌 백업 항목");
                        Path file = SafeFiles.resolve(directory, tree.getPathString());
                        Files.createDirectories(file.getParent());
                        Files.write(file, git.getRepository().open(tree.getObjectId(0)).getBytes((int) SafeFiles.MAX_FILE));
                    }
                }
            }
        });
    }
}
