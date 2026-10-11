package mcd.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    @TempDir
    Path tmp;

    @Test
    void savedSettingsLoadBackIncludingNonAsciiAndWindowsPaths() throws Exception {
        Path file = tmp.resolve("nested/settings.properties");
        Settings settings = new Settings("철수", "https://github.com/me/world.git", Path.of("C:\\Users\\me\\.mcd\\worlds\\world"), "ghp_x", "localhost:25566");

        settings.save(file);

        assertEquals(Optional.of(settings), Settings.load(file));
    }

    @Test
    void blankWorldDirIsRejectedInsteadOfMeaningCurrentFolder() throws Exception {
        Path file = tmp.resolve("settings.properties");
        java.nio.file.Files.writeString(file, "node=a\nremote=x\nworldDir=\ntoken=\naddress=localhost:25566\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Settings.load(file));
        assertTrue(e.getMessage().contains("worldDir"), e.getMessage());
    }

    @Test
    void missingFileMeansFirstRun() throws Exception {
        assertEquals(Optional.empty(), Settings.load(tmp.resolve("none.properties")));
    }
}
