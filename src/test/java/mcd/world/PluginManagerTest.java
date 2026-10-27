package mcd.world;

import mcd.server.PaperBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {
    @TempDir Path tmp;

    @Test
    void requiredDependencyMustExistBeforeAnyJarIsInstalled() throws Exception {
        Path server = tmp.resolve("server"); Files.createDirectories(server);
        WorldTemplate.writeInitialFiles(server, false, PaperBuild.parse("26.2-133"));
        Path plugin = jar(tmp.resolve("dependent.jar"), "Dependent", "1.0", "depend: [Missing]\n");
        assertThrows(java.io.IOException.class, () -> PluginManager.install(server, plugin));
        assertTrue(PluginManager.list(server).isEmpty());
    }

    @Test
    void localModificationOfManagedJarIsDetected() throws Exception {
        Path server = tmp.resolve("server"); Files.createDirectories(server);
        WorldTemplate.writeInitialFiles(server, false, PaperBuild.parse("26.2-133"));
        PluginManager.install(server, jar(tmp.resolve("one.jar"), "Example", "1.0", ""));
        Files.copy(jar(tmp.resolve("two.jar"), "Example", "2.0", ""), server.resolve("plugins/example.jar"), StandardCopyOption.REPLACE_EXISTING);
        assertThrows(java.io.IOException.class, () -> PluginManager.verifyRuntime(server));
    }

    @Test
    void replacingPluginKeepsOneJarAndDetectsLocalOnlyJarBeforeHosting() throws Exception {
        Path server = tmp.resolve("server");
        Files.createDirectories(server);
        WorldTemplate.writeInitialFiles(server, false, PaperBuild.parse("26.2-133"));
        Path old = jar(tmp.resolve("old.jar"), "Example", "1.0", "");
        Path next = jar(tmp.resolve("next.jar"), "Example", "2.0", "");
        PluginManager.install(server, old);
        PluginManager.install(server, next);
        assertEquals(1, PluginManager.list(server).size());
        assertEquals("2.0", PluginManager.list(server).getFirst().version());
        PluginManager.verifyRuntime(server);
        Files.copy(old, server.resolve("plugins/local-only.jar"));
        assertThrows(java.io.IOException.class, () -> PluginManager.verifyRuntime(server));
    }

    static Path jar(Path file, String name, String version, String extra) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write(("name: " + name + "\nversion: '" + version + "'\nmain: example.Main\napi-version: '1.20'\n" + extra).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new JarEntry("example/Main.class")); out.write(new byte[]{0}); out.closeEntry();
        }
        return file;
    }
}
