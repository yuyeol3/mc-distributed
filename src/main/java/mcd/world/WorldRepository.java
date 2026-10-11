package mcd.world;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** 월드 repo의 로컬 작업 트리를 만들거나 연다. 작업 트리가 곧 Paper 서버 폴더다. */
public final class WorldRepository {

    private WorldRepository() {}

    /**
     * 빈 원격 repo에 새 월드를 올린다. dir은 그 월드의 작업 트리로 남아 그대로 플레이에 쓸 수 있다.
     * network가 있으면 노드들이 그 tailnet에 붙도록 월드에 함께 기록한다.
     */
    public static void create(Path dir, String remote, boolean onlineMode, Optional<NetworkConfig> network,
                              CredentialsProvider credentials) throws Exception {
        if (Files.exists(dir)) {
            throw new IllegalStateException(dir + "가 이미 있다. 새 폴더를 지정해야 한다.");
        }
        try (Git git = Git.init().setInitialBranch("main").setDirectory(dir.toFile()).call()) {
            WorldTemplate.writeInitialFiles(dir, onlineMode);
            if (network.isPresent()) network.get().write(dir);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init world").setSign(false).call();
            git.remoteAdd().setName("origin").setUri(new URIish(remote)).call();

            Iterable<PushResult> results = git.push().setRemote("origin").add("main").setCredentialsProvider(credentials).call();
            RemoteRefUpdate.Status status = results.iterator().next().getRemoteUpdate("refs/heads/main").getStatus();
            if (status != RemoteRefUpdate.Status.OK) {
                throw new IllegalStateException("원격에 올리지 못했다(" + status + "). 원격 repo가 비어 있어야 한다.");
            }
            // 이후 fetch가 원격 main을 refs/remotes/origin/main으로 받도록 추적 설정을 맞춘다.
            git.fetch().setRemote("origin").setCredentialsProvider(credentials).call();
        }
    }

    public static boolean exists(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }

    /** 작업 트리가 있으면 열고, 없으면 원격에서 받는다. */
    public static Git openOrClone(Path dir, String remote, CredentialsProvider credentials) throws Exception {
        if (exists(dir)) {
            return Git.open(dir.toFile());
        }
        return Git.cloneRepository().setURI(remote).setDirectory(dir.toFile()).setCredentialsProvider(credentials).call();
    }
}
