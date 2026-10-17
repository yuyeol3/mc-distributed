package mcd.world;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * 초대 코드: 월드의 GitHub repo와 그 repo의 deploy key. 이 코드만 있으면 GitHub 계정 없이 월드에 참가한다.
 * 코드를 가진 사람은 누구나 월드를 읽고 쓸 수 있으므로, 초대를 끊으려면 GitHub에서 키를 지우고 새로 만든다.
 */
public record Invite(GitHubRepo repo, DeployKey key) {

    private static final String PREFIX = "mcd1.";

    public static boolean looksLikeInvite(String text) {
        return text.trim().startsWith(PREFIX);
    }

    public static Invite parse(String code) throws GeneralSecurityException {
        String text = code.trim();
        if (!looksLikeInvite(text)) {
            throw new IllegalArgumentException("mcd 초대 코드가 아니다(mcd1.로 시작해야 한다)");
        }
        String[] parts = text.substring(PREFIX.length()).split("\\.", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("초대 코드가 잘렸다. 전체를 다시 복사한다.");
        }
        String repo;
        try {
            repo = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("초대 코드가 잘렸다. 전체를 다시 복사한다.", e);
        }
        return new Invite(GitHubRepo.parse(repo), DeployKey.decode(parts[1]));
    }

    public String encode() {
        String repoPart = Base64.getUrlEncoder().withoutPadding().encodeToString(repo.toString().getBytes(StandardCharsets.UTF_8));
        return PREFIX + repoPart + "." + key.encode();
    }
}
