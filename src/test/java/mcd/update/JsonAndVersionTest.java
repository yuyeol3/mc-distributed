package mcd.update;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonAndVersionTest {

    @Test
    void parsesNestedGitHubLikeJson() {
        Object parsed = Json.parse("""
                [{"tag_name":"v0.2.0","draft":false,"prerelease":true,"id":12,
                  "body":"줄\\n바꿈 \\"인용\\" \\u0041",
                  "assets":[{"name":"mcd-windows.zip","browser_download_url":"https:\\/\\/x\\/a.zip","size":1.5e3}],
                  "author":null}]""");
        Map<?, ?> release = (Map<?, ?>) ((List<?>) parsed).getFirst();
        assertEquals("v0.2.0", release.get("tag_name"));
        assertEquals(Boolean.TRUE, release.get("prerelease"));
        assertEquals("줄\n바꿈 \"인용\" A", release.get("body"));
        assertEquals(null, release.get("author"));
        Map<?, ?> asset = (Map<?, ?>) ((List<?>) release.get("assets")).getFirst();
        assertEquals("https://x/a.zip", asset.get("browser_download_url"));
        assertEquals(1500.0, asset.get("size"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1"));
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(AppVersion.parse("v0.10.0").get().compareTo(AppVersion.parse("0.2.0").get()) > 0);
        assertTrue(AppVersion.parse("0.2.1").get().compareTo(AppVersion.parse("v0.2.0-beta").get()) > 0);
        assertEquals(AppVersion.parse("0.2.0"), AppVersion.parse("v0.2.0"));
        assertEquals("0.2.0", AppVersion.parse("v0.2.0").get().toString());
        assertEquals(Optional.empty(), AppVersion.parse("nightly"));
        assertEquals(Optional.empty(), AppVersion.parse(null));
    }
}
