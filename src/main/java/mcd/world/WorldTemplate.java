package mcd.world;

import mcd.lock.LockState;
import mcd.server.PaperDistribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 새 월드 repo에 들어갈 초기 파일. 작업 트리가 곧 Paper 서버 폴더다. */
public final class WorldTemplate {

    /** 이 월드를 만든 Paper 빌드. 다른 빌드로 열면 월드가 업그레이드되어 다른 호스트가 열 수 없게 될 수 있다. */
    public static final String PAPER_BUILD_FILE = "mcd-paper-build.txt";

    /** 노드마다 다르거나 Paper가 다시 만드는 파일은 버전 관리하지 않는다. 목록은 Paper 26.1.2 실행 결과로 정했다. */
    static final String GITIGNORE = """
            /libraries/
            /versions/
            /cache/
            /logs/
            /crash-reports/
            /plugins/spark/
            /plugins/bStats/
            *.jar
            /eula.txt
            /usercache.json
            /version_history.json
            /world/session.lock
            """;

    private WorldTemplate() {}

    public static void writeInitialFiles(Path dir, boolean onlineMode) throws IOException {
        Files.writeString(dir.resolve(".gitignore"), GITIGNORE);
        Files.writeString(dir.resolve("server.properties"), """
                online-mode=%s
                region-file-compression=deflate
                accepts-transfers=true
                """.formatted(onlineMode));
        // Paper의 자동 저장은 save-off 중에도 level.dat과 data/*.dat을 쓴다(실험 E3). 스냅샷 도중 파일이 바뀌지 않도록
        // 자동 저장을 끄고, 저장은 mcd의 주기 스냅샷(save-all flush)이 맡는다. Paper가 나머지 기본값을 채워 넣는다.
        Files.writeString(dir.resolve("bukkit.yml"), """
                ticks-per:
                  autosave: 0
                """);
        Files.writeString(dir.resolve(PAPER_BUILD_FILE), PaperDistribution.BUILD + "\n");
        LockState.initial().write(dir);
    }

    /** 월드가 이 프로그램의 Paper 빌드로 만들어졌는지 확인한다. */
    public static void checkPaperBuild(Path dir) throws IOException {
        String recorded = Files.readString(dir.resolve(PAPER_BUILD_FILE)).trim();
        if (!recorded.equals(PaperDistribution.BUILD)) {
            throw new IllegalStateException("이 월드는 Paper " + recorded + "용인데 이 프로그램은 " + PaperDistribution.BUILD + "를 쓴다");
        }
    }

    /** Paper를 띄우려면 각 PC에서 EULA에 동의해야 한다. eula.txt는 버전 관리하지 않는다. */
    public static void acceptEula(Path dir) throws IOException {
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
    }
}
