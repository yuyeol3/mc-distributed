package mcd.cli;

import mcd.world.WorldTemplate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;

import java.nio.file.Files;
import java.nio.file.Path;

final class InitCommand {

    private InitCommand() {}

    static void run(Path dir, String remote, boolean onlineMode, CredentialsProvider credentials) throws Exception {
        if (Files.exists(dir)) {
            throw new IllegalStateException(dir + "가 이미 있다. 새 폴더를 지정해야 한다.");
        }
        try (Git git = Git.init().setInitialBranch("main").setDirectory(dir.toFile()).call()) {
            WorldTemplate.writeInitialFiles(dir, onlineMode);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init world").setSign(false).call();
            git.remoteAdd().setName("origin").setUri(new URIish(remote)).call();

            Iterable<PushResult> results = git.push().setRemote("origin").add("main").setCredentialsProvider(credentials).call();
            RemoteRefUpdate.Status status = results.iterator().next().getRemoteUpdate("refs/heads/main").getStatus();
            if (status != RemoteRefUpdate.Status.OK) {
                throw new IllegalStateException("원격에 올리지 못했다(" + status + "). 원격 repo가 비어 있어야 한다.");
            }
        }
        System.out.println("[mcd] 새 월드를 " + remote + "에 만들었다. 친구들은 mcd play로 들어오면 된다.");
    }
}
