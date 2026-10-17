package mcd.world;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployKeyTest {

    @Test
    void encodedKeyRoundTrips() throws Exception {
        DeployKey key = DeployKey.generate();
        DeployKey restored = DeployKey.decode(key.encode());
        assertEquals(key.encode(), restored.encode());
        assertEquals(key.publicKeyLine(), restored.publicKeyLine());
    }

    @Test
    void publicKeyLineIsWhatGitHubAccepts() throws Exception {
        assertTrue(DeployKey.generate().publicKeyLine().matches("ecdsa-sha2-nistp256 [A-Za-z0-9+/=]+ mcd"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(GeneralSecurityException.class, () -> DeployKey.decode("not-a-key"));
        assertThrows(GeneralSecurityException.class, () -> DeployKey.decode("abc.def"));
    }

    /** 다른 서버(중간자)의 키는 받지 않는다. GitHub 키를 받는지는 GitHub에 실제로 접속해 확인한다(PushCasTest). */
    @Test
    void acceptsOnlyGitHubHostKeys() throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        assertFalse(DeployKey.isGitHubHostKey(g.generateKeyPair().getPublic()));
    }
}
