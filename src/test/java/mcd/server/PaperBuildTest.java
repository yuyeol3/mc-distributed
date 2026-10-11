package mcd.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaperBuildTest {

    @Test
    void parsesAndPrintsVersionDashBuild() {
        PaperBuild b = PaperBuild.parse(" 26.1.2-74\n");
        assertEquals(new PaperBuild("26.1.2", 74), b);
        assertEquals("26.1.2-74", b.toString());
        assertThrows(IllegalArgumentException.class, () -> PaperBuild.parse("26.1.2"));
    }

    @Test
    void ordersByVersionNumbersThenBuild() {
        List<String> sorted = Stream.of("26.2-1", "1.21.11-50", "26.1.2-80", "26.1.2-74", "1.20.5-10", "26.10-1")
                .map(PaperBuild::parse).sorted().map(PaperBuild::toString).toList();
        assertEquals(List.of("1.20.5-10", "1.21.11-50", "26.1.2-74", "26.1.2-80", "26.2-1", "26.10-1"), sorted);
    }
}
