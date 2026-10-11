package mcd.server;

import mcd.lock.HostLock;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실험 E1′: 기록한 세션(nodes/e1/<session>.git)의 스냅샷을 새 repo에 차례로 재생하면서
 * 스냅샷마다 바뀐 파일과 압축 설정별 커밋 시간을 잰다. Paper 없이 반복할 수 있다.
 * 실행: ./gradlew paperTest --tests '*SnapshotCostAnalysis*' -Dmcd.session=tuning
 */
@Tag("paper")
@EnabledIfSystemProperty(named = "mcd.session", matches = "tuning|validation")
class SnapshotCostAnalysis {

    /** core.compression 값. -1은 JGit 기본(zlib 기본 수준). */
    private static final int[] COMPRESSION_LEVELS = {Integer.getInteger("mcd.level", -1)};

    @TempDir
    Path tmp;

    @Test
    void analyze() throws Exception {
        Path bare = Path.of("nodes/e1", System.getProperty("mcd.session") + ".git").toAbsolutePath();
        assertTrue(Files.exists(bare), bare + "가 없다. RecordSessionExperiment를 먼저 실행한다.");

        try (Git source = Git.open(bare.toFile())) {
            List<RevCommit> snapshots = snapshots(source.getRepository());
            System.out.println("snapshots: " + snapshots.size());
            printChanges(source.getRepository(), snapshots);
            for (int level : COMPRESSION_LEVELS) {
                replay(source.getRepository(), snapshots, level);
            }
        }
    }

    /** 스냅샷마다 바뀐 파일 수와 새 내용의 크기(region과 나머지). */
    private static void printChanges(Repository repo, List<RevCommit> snapshots) throws Exception {
        try (ObjectReader reader = repo.newObjectReader()) {
            for (int i = 1; i < snapshots.size(); i++) {
                long regionFiles = 0, regionBytes = 0, otherFiles = 0, otherBytes = 0;
                for (DiffEntry d : diff(repo, reader, snapshots.get(i - 1), snapshots.get(i))) {
                    if (d.getChangeType() == DiffEntry.ChangeType.DELETE) continue;
                    long size = reader.getObjectSize(d.getNewId().toObjectId(), org.eclipse.jgit.lib.Constants.OBJ_BLOB);
                    if (d.getNewPath().endsWith(".mca")) {
                        regionFiles++;
                        regionBytes += size;
                    } else {
                        otherFiles++;
                        otherBytes += size;
                    }
                }
                System.out.printf("change %d: region %d files %d KB, other %d files %d KB%n",
                        i, regionFiles, regionBytes / 1024, otherFiles, otherBytes / 1024);
            }
        }
    }

    /**
     * 스냅샷을 차례로 작업 트리에 써서(바뀐 파일만) HostLock.commitWorld로 커밋하고 시간을 잰다.
     * 실제 서버처럼 내용이 바뀐 파일만 다시 쓰므로, 커밋 비용은 실제 스냅샷과 같은 조건이다.
     */
    private void replay(Repository source, List<RevCommit> snapshots, int level) throws Exception {
        Path bare = tmp.resolve("replay-remote-" + level + ".git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        Path work = tmp.resolve("replay-" + level);
        try (Git git = Git.init().setInitialBranch("main").setDirectory(work.toFile()).call();
             ObjectReader reader = source.newObjectReader()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setInt("core", null, "compression", level);
            config.setString("remote", "origin", "url", bare.toUri().toString());
            config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
            config.save();
            HostLock lock = new HostLock(git, "replay", InstantSource.system(), Duration.ofMinutes(10), Duration.ofMinutes(2), null);

            RevCommit previous = null;
            List<Long> times = new ArrayList<>();
            List<String> diagnostics = new ArrayList<>();
            List<String> pushes = new ArrayList<>();
            for (RevCommit snapshot : snapshots) {
                for (DiffEntry d : diff(source, reader, previous, snapshot)) {
                    if (d.getChangeType() == DiffEntry.ChangeType.DELETE) {
                        Files.deleteIfExists(work.resolve(d.getOldPath()));
                    } else {
                        Path file = work.resolve(d.getNewPath());
                        Files.createDirectories(file.getParent() == null ? work : file.getParent());
                        Files.write(file, reader.open(d.getNewId().toObjectId()).getBytes(Integer.MAX_VALUE));
                    }
                }
                if (level == -1) {
                    var status = git.status().call();
                    diagnostics.add("modified " + status.getModified().size() + " untracked " + status.getUntracked().size());
                }
                long t0 = System.nanoTime();
                lock.commitWorld("replay");
                times.add((System.nanoTime() - t0) / 1_000_000);
                long before = sizeKb(bare);
                long t2 = System.nanoTime();
                lock.pushCommitted();
                pushes.add("%dms/%dKB".formatted((System.nanoTime() - t2) / 1_000_000, sizeKb(bare) - before));
                previous = snapshot;
            }
            if (level == -1) System.out.println("diagnostics: " + diagnostics);
            System.out.println("push time/remote growth: " + pushes);
            long objectsKb = sizeKb(work.resolve(".git/objects"));
            System.out.printf("compression %d: commit ms %s, .git/objects %d KB%n", level, times, objectsKb);
        }
    }

    private static List<DiffEntry> diff(Repository repo, ObjectReader reader, RevCommit from, RevCommit to) throws Exception {
        CanonicalTreeParser newTree = new CanonicalTreeParser(null, reader, to.getTree());
        try (Git git = new Git(repo)) {
            var command = git.diff().setNewTree(newTree).setShowNameAndStatusOnly(true);
            if (from != null) {
                command.setOldTree(new CanonicalTreeParser(null, reader, from.getTree()));
            } else {
                command.setOldTree(new org.eclipse.jgit.treewalk.EmptyTreeIterator());
            }
            return command.call();
        }
    }

    /** 첫 커밋(init)부터 main까지, 스냅샷 커밋만 순서대로. */
    private static List<RevCommit> snapshots(Repository repo) throws Exception {
        List<RevCommit> result = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(repo.resolve("main")));
            walk.sort(org.eclipse.jgit.revwalk.RevSort.REVERSE);
            for (RevCommit c : walk) {
                if (c.getShortMessage().contains("snapshot")) result.add(c);
            }
        }
        return result;
    }

    private static long sizeKb(Path dir) throws Exception {
        try (var files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum() / 1024;
        }
    }
}
