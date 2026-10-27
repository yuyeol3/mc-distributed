package mcd.world;

import mcd.lock.HostLock;
import mcd.node.Timing;
import mcd.server.*;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

@Tag("paper")
class WorldAdminPaperTest {
    @TempDir Path tmp;

    @Test
    void importedRealWorldAndPluginRunOnAnotherCopy() throws Exception {
        PaperBuild build = PaperBuild.parse(System.getProperty("mcd.paperBuild", "26.1.2-74"));
        Path jar = PaperDistribution.official().ensureDownloaded(Path.of("nodes/_cache"), build);
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        Path seed = tmp.resolve("seed");
        WorldRepository.create(seed, remote, false, Optional.empty(), build, null);
        WorldTemplate.acceptEula(seed);
        try (PaperServer paper = PaperServer.start(seed, jar, 25891, Duration.ofMinutes(3), null)) {
            paper.stop(Duration.ofMinutes(1));
        }
        WorldAdmin admin = new WorldAdmin(remote, "admin", null);
        try (var prepared = WorldImport.prepare(seed.resolve("world"))) { admin.replace(prepared); }
        Path plugin = samplePlugin(seed);
        admin.addPlugin(plugin);
        Path friend = tmp.resolve("friend");
        try (Git git = WorldRepository.openOrClone(friend, remote, null)) {
            PluginManager.verifyRuntime(friend);
            assertEquals(PluginManager.sha256(seed.resolve("world/level.dat")), PluginManager.sha256(friend.resolve("world/level.dat")), "실제 생성한 월드 데이터가 그대로 전달돼야 한다");
            assertEquals(PluginManager.sha256(plugin), PluginManager.sha256(friend.resolve("plugins/mcdsample.jar")));
            WorldTemplate.acceptEula(friend);
            try (PaperServer paper = PaperServer.start(friend, jar, 25892, Duration.ofMinutes(3), null)) {
                assertTrue(paper.lines().stream().anyMatch(line -> line.contains("MCD_SAMPLE_ENABLED")), "다른 사본에서도 실제로 플러그인이 활성화돼야 한다");
                paper.commandAndAwait("plugins", "McdSample");
                paper.stop(Duration.ofMinutes(1));
            }
        }
    }

    private Path samplePlugin(Path server) throws Exception {
        Path source = tmp.resolve("compile/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; public final class Main extends org.bukkit.plugin.java.JavaPlugin { public void onEnable() { getLogger().info(\"MCD_SAMPLE_ENABLED\"); } }");
        String classpath;
        try (var files = Files.walk(server)) {
            classpath = files.filter(p -> p.toString().endsWith(".jar")).map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        }
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", classpath, source.toString());
        assertEquals(0, result, "Paper에 포함된 API로 최소 플러그인을 컴파일해야 한다");
        Path plugin = tmp.resolve("sample.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(plugin))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write("name: McdSample\nversion: '1.0'\nmain: example.Main\napi-version: '1.20'\n".getBytes());
            out.closeEntry();
            out.putNextEntry(new JarEntry("example/Main.class"));
            out.write(Files.readAllBytes(source.resolveSibling("Main.class"))); out.closeEntry();
        }
        return plugin;
    }
}
