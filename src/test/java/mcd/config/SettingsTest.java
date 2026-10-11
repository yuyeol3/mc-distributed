package mcd.config;

import mcd.config.Settings.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    private static final World SURVIVAL = new World("https://github.com/me/survival.git", Path.of("C:\\Users\\me\\.mcd\\worlds\\survival"));
    private static final World CREATIVE = new World("https://github.com/friend/creative.git", Path.of("C:\\Users\\me\\.mcd\\worlds\\creative"));

    @TempDir
    Path tmp;

    @Test
    void savedSettingsLoadBackIncludingNonAsciiAndWindowsPaths() throws Exception {
        Path file = tmp.resolve("nested/settings.properties");
        Settings settings = new Settings("철수", "ghp_x", "localhost:25566", List.of(SURVIVAL, CREATIVE), CREATIVE.remote());

        settings.save(file);

        assertEquals(Optional.of(settings), Settings.load(file));
    }

    /** v0.3.1까지는 월드 하나를 remote/worldDir로 저장했다. 업데이트한 뒤에도 그 월드를 그대로 쓴다. */
    @Test
    void singleWorldFormatOfOlderVersionsStillLoads() throws Exception {
        Path file = tmp.resolve("settings.properties");
        Files.writeString(file, "node=a\nremote=https\\://github.com/me/survival.git\nworldDir=C\\:\\\\Users\\\\me\\\\.mcd\\\\worlds\\\\survival\ntoken=t\naddress=localhost\\:25566\n");

        Settings settings = Settings.load(file).orElseThrow();

        assertEquals(List.of(SURVIVAL), settings.worlds());
        assertEquals(Optional.of(SURVIVAL), settings.currentWorld());
    }

    @Test
    void blankWorldDirIsRejectedInsteadOfMeaningCurrentFolder() throws Exception {
        Path file = tmp.resolve("settings.properties");
        Files.writeString(file, "node=a\nremote=x\nworldDir=\ntoken=\naddress=localhost:25566\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Settings.load(file));
        assertTrue(e.getMessage().contains("worldDir"), e.getMessage());
    }

    @Test
    void addingSelectsTheWorldAndRemovingTheCurrentFallsBackToTheFirst() {
        Settings settings = new Settings("a", "t", "localhost:25566", List.of(), "");
        assertEquals(Optional.empty(), settings.currentWorld());

        settings = settings.withWorld(SURVIVAL).withWorld(CREATIVE);
        assertEquals(Optional.of(CREATIVE), settings.currentWorld());

        settings = settings.withoutWorld(CREATIVE.remote());
        assertEquals(List.of(SURVIVAL), settings.worlds());
        assertEquals(Optional.of(SURVIVAL), settings.currentWorld());
    }

    @Test
    void missingFileMeansFirstRun() throws Exception {
        assertEquals(Optional.empty(), Settings.load(tmp.resolve("none.properties")));
    }
}
