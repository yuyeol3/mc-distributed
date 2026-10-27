package mcd.world;

import mcd.lock.HostLock;
import mcd.node.Timing;
import mcd.server.PaperBuild;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class WorldAdminTest {
    @TempDir Path tmp;

    @Test
    void managedPluginJarAndHashAreSharedAndRemovalReachesAnotherClone() throws Exception {
        Path remote = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote.toFile()).call().close();
        String url = remote.toUri().toString();
        WorldRepository.create(tmp.resolve("seed"), url, false, Optional.empty(), PaperBuild.parse("26.2-133"), null);
        WorldAdmin admin = new WorldAdmin(url, "A", null);
        Path plugin = PluginManagerTest.jar(tmp.resolve("plugin.jar"), "Example", "1.0", "");
        String backup = admin.addPlugin(plugin);
        try (Git git = WorldRepository.openOrClone(tmp.resolve("reader"), url, null)) {
            PluginManager.verifyRuntime(tmp.resolve("reader"));
            assertEquals(PluginManager.sha256(plugin), PluginManager.sha256(tmp.resolve("reader/plugins/example.jar")));
            admin.removePlugin("Example");
            git.fetch().call();
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("origin/main").call();
            assertFalse(Files.exists(tmp.resolve("reader/plugins/example.jar")));
            PluginManager.verifyRuntime(tmp.resolve("reader"));
        }
    }

    @Test
    void failedEditPublishesNoPartialWorldAndReleasesMaintenanceLock() throws Exception {
        Path remote = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote.toFile()).call().close();
        String url = remote.toUri().toString();
        WorldRepository.create(tmp.resolve("seed"), url, false, Optional.empty(), PaperBuild.parse("26.2-133"), null);
        WorldAdmin admin = new WorldAdmin(url, "A", null);
        assertThrows(java.io.IOException.class, () -> admin.edit("broken import", dir -> {
            Files.writeString(dir.resolve("partial.txt"), "must not publish");
            throw new java.io.IOException("interrupted");
        }));
        try (Git git = WorldRepository.openOrClone(tmp.resolve("reader"), url, null)) {
            assertFalse(Files.exists(tmp.resolve("reader/partial.txt")));
            assertEquals(mcd.lock.LockState.State.FREE, mcd.lock.LockState.read(tmp.resolve("reader")).state());
        }
        assertEquals(1, admin.backups().size());
    }

    @Test
    void pluginChangeIsBackedUpAndRestoreDoesNotResetMainHistory() throws Exception {
        Path remote = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote.toFile()).call().close();
        String url = remote.toUri().toString();
        WorldRepository.create(tmp.resolve("seed"), url, false, Optional.empty(), PaperBuild.parse("26.2-133"), null);
        WorldAdmin admin = new WorldAdmin(url, "A", null);
        String backup = admin.edit("test", dir -> Files.writeString(dir.resolve("notice.txt"), "changed"));
        try (Git git = WorldRepository.openOrClone(tmp.resolve("reader"), url, null)) {
            assertEquals("changed", Files.readString(tmp.resolve("reader/notice.txt")));
            String changedHead = git.getRepository().resolve("HEAD").name();
            admin.restore(backup);
            git.fetch().call();
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                assertTrue(walk.isMergedInto(walk.parseCommit(git.getRepository().resolve(changedHead)),
                        walk.parseCommit(git.getRepository().resolve("origin/main"))));
            }
            git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef("origin/main").call();
            assertFalse(Files.exists(tmp.resolve("reader/notice.txt")));
        }
    }
}
