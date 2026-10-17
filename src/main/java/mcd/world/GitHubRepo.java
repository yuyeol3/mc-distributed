package mcd.world;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub repo 하나(계정/이름). 사용자가 넣은 여러 형태의 주소를 받아 SSH 원격 주소와 설정 화면 주소를 만든다. */
public record GitHubRepo(String owner, String name) {

    private static final Pattern ADDRESS = Pattern.compile(
            "^(?:https?://(?:www\\.)?github\\.com/|ssh://git@(?:ssh\\.)?github\\.com(?::\\d+)?/|git@github\\.com:|github\\.com/)?"
                    + "([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$");

    /** https://github.com/a/b(.git), ssh://git@ssh.github.com:443/a/b.git, git@github.com:a/b.git, github.com/a/b, a/b. */
    public static GitHubRepo parse(String address) {
        Matcher m = ADDRESS.matcher(address.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("GitHub repo 주소가 아닙니다: " + address.trim());
        }
        return new GitHubRepo(m.group(1), m.group(2));
    }

    /** deploy key로 접속할 원격 주소. 방화벽이 22번 포트를 막아도 되도록 443 포트를 쓴다. */
    public String sshRemote() {
        return "ssh://git@ssh.github.com:443/" + owner + "/" + name + ".git";
    }

    /** deploy key 추가 화면. */
    public String newDeployKeyPage() {
        return "https://github.com/" + owner + "/" + name + "/settings/keys/new";
    }

    @Override
    public String toString() {
        return owner + "/" + name;
    }
}
