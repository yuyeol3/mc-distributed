package mcd.world;

import mcd.server.PaperBuild;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
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
                              PaperBuild paperBuild, TransportConfigCallback auth) throws Exception {
        if (Files.exists(dir)) {
            throw new IllegalStateException(dir + "가 이미 있다. 새 폴더를 지정해야 한다.");
        }
        try (Git git = Git.init().setInitialBranch("main").setDirectory(dir.toFile()).call()) {
            WorldTemplate.writeInitialFiles(dir, onlineMode, paperBuild);
            if (network.isPresent()) network.get().write(dir);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init world").setSign(false).call();
            git.remoteAdd().setName("origin").setUri(new URIish(remote)).call();

            Iterable<PushResult> results = git.push().setRemote("origin").add("main").setTransportConfigCallback(auth).call();
            RemoteRefUpdate.Status status = results.iterator().next().getRemoteUpdate("refs/heads/main").getStatus();
            if (status != RemoteRefUpdate.Status.OK) {
                throw new IllegalStateException("원격에 올리지 못했다(" + status + "). 원격 repo가 비어 있어야 한다.");
            }
            // 이후 fetch가 원격 main을 refs/remotes/origin/main으로 받도록 추적 설정을 맞춘다.
            git.fetch().setRemote("origin").setTransportConfigCallback(auth).call();
        }
    }

    public static boolean exists(Path dir) {
        return Files.exists(dir.resolve(".git"));
    }

    /**
     * 작업 트리가 있으면 열고, 없으면 원격에서 받는다. 이미 있는 작업 트리의 원격 주소가 다르면 remote로 바꾼다
     * (같은 repo를 https 토큰에서 SSH deploy key로 옮긴 경우).
     */
    public static Git openOrClone(Path dir, String remote, TransportConfigCallback auth) throws Exception {
        if (exists(dir)) {
            Git git = Git.open(dir.toFile());
            StoredConfig config = git.getRepository().getConfig();
            if (!remote.equals(config.getString("remote", "origin", "url"))) {
                config.setString("remote", "origin", "url", remote);
                config.save();
            }
            return git;
        }
        return Git.cloneRepository().setURI(remote).setDirectory(dir.toFile()).setTransportConfigCallback(auth).call();
    }

    /**
     * 원격에 쓸 수 있는지 접속해 확인한다(빈 repo여도 된다). 키가 등록되지 않았거나 읽기 전용이면 예외.
     * 실제로 push하지는 않고 push 연결만 열었다 닫는다.
     */
    public static void checkWriteAccess(String remote, TransportConfigCallback auth) throws Exception {
        try (Transport transport = Transport.open(new URIish(remote))) {
            if (auth != null) auth.configure(transport);
            transport.openPush().close();
        }
    }

    /** GitHub 토큰(https)으로 접속한다. 토큰이 비면 인증 없이(로컬 repo 등). */
    public static TransportConfigCallback tokenAuth(String token) {
        if (token == null || token.isBlank()) return null;
        UsernamePasswordCredentialsProvider credentials = new UsernamePasswordCredentialsProvider("x-access-token", token);
        return transport -> transport.setCredentialsProvider(credentials);
    }
}
