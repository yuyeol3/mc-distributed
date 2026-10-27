package mcd.world;

import mcd.server.PaperBuild;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldImportTest {
    @TempDir Path tmp;

    @Test
    void runningSourceWorldIsRejectedInsteadOfCopyingChangingFiles() throws Exception {
        Path source = tmp.resolve("running"); Files.createDirectories(source);
        level(source.resolve("level.dat"), "26.2");
        try (var channel = java.nio.channels.FileChannel.open(source.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var held = channel.lock()) {
            assertThrows(IOException.class, () -> WorldImport.prepare(source));
        }
    }

    @Test
    void archiveCannotEscapeStagingDirectory() throws Exception {
        Path zip = tmp.resolve("bad.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../escaped.dat")); out.write(1); out.closeEntry();
        }
        assertThrows(IOException.class, () -> WorldImport.prepare(zip));
        assertFalse(Files.exists(tmp.resolve("escaped.dat")));
    }

    @Test
    void newerWorldIsRejectedBeforeExistingWorldIsChanged() throws Exception {
        Path source = tmp.resolve("new"); Files.createDirectories(source);
        level(source.resolve("level.dat"), "26.3");
        Path target = tmp.resolve("server"); Files.createDirectories(target.resolve("world"));
        Files.writeString(target.resolve("world/keep"), "existing");
        try (var prepared = WorldImport.prepare(source)) {
            assertThrows(IOException.class, () -> prepared.apply(target, PaperBuild.parse("26.2-133")));
        }
        assertEquals("existing", Files.readString(target.resolve("world/keep")));
    }

    @Test
    void replacesAllDimensionsWithoutTouchingServerSettingsOrSource() throws Exception {
        Path source = tmp.resolve("input");
        Files.createDirectories(source.resolve("DIM-1/region"));
        level(source.resolve("level.dat"), "26.1.2");
        Files.writeString(source.resolve("DIM-1/region/nether.mca"), "imported");
        Path target = tmp.resolve("server");
        Files.createDirectories(target.resolve("world_the_end/DIM1/region"));
        Files.createDirectories(target.resolve("world/playerdata"));
        Files.writeString(target.resolve("world/playerdata/old.dat"), "old");
        Files.writeString(target.resolve("server.properties"), "online-mode=true\n");
        try (var prepared = WorldImport.prepare(source)) {
            prepared.apply(target, PaperBuild.parse("26.1.2-74"));
        }
        assertEquals("imported", Files.readString(target.resolve("world/DIM-1/region/nether.mca")));
        assertFalse(Files.exists(target.resolve("world_the_end")));
        assertFalse(Files.exists(target.resolve("world/playerdata/old.dat")));
        assertEquals("online-mode=true\n", Files.readString(target.resolve("server.properties")));
        assertTrue(Files.exists(source.resolve("level.dat")));
    }

    static void level(Path path, String version) throws Exception {
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) {
            out.writeByte(10); out.writeUTF("");
            out.writeByte(10); out.writeUTF("Data");
            out.writeByte(10); out.writeUTF("Version");
            out.writeByte(8); out.writeUTF("Name"); out.writeUTF(version);
            out.writeByte(0); out.writeByte(0); out.writeByte(0);
        }
    }
}
