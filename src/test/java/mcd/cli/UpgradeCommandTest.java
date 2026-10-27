package mcd.cli;

import mcd.lock.HostLock;
import mcd.server.FakePaperApi;
import mcd.server.PaperBuild;
import mcd.server.PaperDistribution;
import mcd.world.WorldTemplate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeCommandTest {

    private static final byte[] JAR = "jar".getBytes(StandardCharsets.UTF_8);
    private static final PaperBuild START = PaperBuild.parse("26.1.2-74");

    @TempDir
    Path tmp;

    private String remote;
    private Path bare;

    @BeforeEach
    void setUp() throws Exception {
        bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        remote = bare.toUri().toString();
        InitCommand.run(tmp.resolve("init"), remote, false, Optional.empty(), START, null);
    }

    @Test
    void refusesToTouchWorkTreeOpenedByAnotherNodeProcess() throws Exception {
        Path directory = tmp.resolve("owner");
        try (Git ignored = mcd.world.WorldRepository.openOrClone(directory, remote, null)) { }
        try (var held = mcd.world.WorldProcessLock.acquire(directory);
             FakePaperApi api = new FakePaperApi().build("26.2-7", JAR)) {
            assertThrows(java.io.IOException.class, () -> upgrade("26.2", api));
            assertEquals(START.toString(), remoteFile(WorldTemplate.PAPER_BUILD_FILE).trim());
        }
    }

    @Test
    void upgradesToLatestStableBuildOfAVersion() throws Exception {
        try (FakePaperApi api = new FakePaperApi().build("26.2-3", JAR).build("26.2-7", JAR)) {
            PaperBuild next = upgrade("26.2", api);

            assertEquals(PaperBuild.parse("26.2-7"), next);
            assertEquals("26.2-7", remoteFile(WorldTemplate.PAPER_BUILD_FILE).trim(), "월드 repo의 빌드가 바뀌어야 한다");
            assertTrue(remoteFile("mcd-lock.properties").contains("state=FREE"), "락은 반납돼야 한다");
        }
    }

    @Test
    void refusesDowngrade() throws Exception {
        try (FakePaperApi api = new FakePaperApi().build("26.1.2-70", JAR)) {
            assertThrows(IllegalStateException.class, () -> upgrade("26.1.2-70", api));
            assertEquals(START.toString(), remoteFile(WorldTemplate.PAPER_BUILD_FILE).trim());
            assertTrue(remoteFile("mcd-lock.properties").contains("state=FREE"), "실패해도 락은 돌려줘야 한다");
        }
    }

    @Test
    void refusesWhileSomeoneIsHosting() throws Exception {
        try (Git host = Git.cloneRepository().setURI(remote).setDirectory(tmp.resolve("host").toFile()).call()) {
            new HostLock(host, "friend", InstantSource.system(), Duration.ofMinutes(10), Duration.ofMinutes(2), null).tryAcquire("x");
        }
        try (FakePaperApi api = new FakePaperApi().build("26.2-7", JAR)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> upgrade("26.2", api));
            assertTrue(e.getMessage().contains("friend"), e.getMessage());
            assertEquals(START.toString(), remoteFile(WorldTemplate.PAPER_BUILD_FILE).trim());
        }
    }

    private PaperBuild upgrade(String target, FakePaperApi api) throws Exception {
        return UpgradeCommand.run(tmp.resolve("owner"), remote, "owner", target, tmp.resolve("cache"), null,
                new PaperDistribution(api.url()), m -> {});
    }

    private String remoteFile(String path) throws Exception {
        try (Git git = Git.open(bare.toFile()); RevWalk walk = new RevWalk(git.getRepository())) {
            Repository repo = git.getRepository();
            try (TreeWalk tree = TreeWalk.forPath(repo, path, walk.parseCommit(repo.resolve("main")).getTree())) {
                return new String(repo.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
