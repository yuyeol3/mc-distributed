package mcd.world;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.security.GeneralSecurityException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteTest {

    @Test
    void codeRoundTripsRepoAndKey() throws Exception {
        Invite invite = new Invite(new GitHubRepo("yuyeol3", "world"), DeployKey.generate());
        String code = invite.encode();

        Invite parsed = Invite.parse("  " + code + "\n");
        assertTrue(Invite.looksLikeInvite(code));
        assertEquals(invite.repo(), parsed.repo());
        assertEquals(invite.key().encode(), parsed.key().encode());
        assertTrue(code.length() < 400, "메신저로 보내기 좋은 길이여야 한다: " + code.length());
    }

    @Test
    void truncatedOrForeignTextIsRejected() throws Exception {
        String code = new Invite(new GitHubRepo("a", "b"), DeployKey.generate()).encode();
        assertThrows(IllegalArgumentException.class, () -> Invite.parse("https://github.com/a/b"));
        assertThrows(Exception.class, () -> Invite.parse(code.substring(0, code.length() / 2)));
        assertThrows(GeneralSecurityException.class, () -> Invite.parse(code.substring(0, code.lastIndexOf('.'))));
    }

    /**
     * 키 등록 확인이 실제 GitHub에서 맞게 판정하는지: 등록한 쓰기 키는 통과, 등록하지 않은 키는 거절.
     * MCD_SSH_REMOTE=ssh://git@ssh.github.com:443/<user>/<repo>.git MCD_DEPLOY_KEY=<등록한 쓰기 키> ./gradlew test --tests '*InviteTest*'
     */
    @Test
    @Tag("github")
    @EnabledIfEnvironmentVariable(named = "MCD_SSH_REMOTE", matches = ".+")
    void writeAccessCheckOnGitHub() throws Exception {
        String remote = System.getenv("MCD_SSH_REMOTE");
        assertDoesNotThrow(() -> WorldRepository.checkWriteAccess(remote, DeployKey.decode(System.getenv("MCD_DEPLOY_KEY")).transport()));
        DeployKey unregistered = DeployKey.generate();
        assertThrows(Exception.class, () -> WorldRepository.checkWriteAccess(remote, unregistered.transport()));
    }
}
