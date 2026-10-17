package mcd.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubRepoTest {

    @Test
    void acceptsTheAddressFormsPeopleCopy() {
        GitHubRepo expected = new GitHubRepo("yuyeol3", "my-world.v2");
        for (String address : new String[] {
                "https://github.com/yuyeol3/my-world.v2",
                "https://github.com/yuyeol3/my-world.v2.git",
                "https://github.com/yuyeol3/my-world.v2/",
                " github.com/yuyeol3/my-world.v2 ",
                "git@github.com:yuyeol3/my-world.v2.git",
                "ssh://git@ssh.github.com:443/yuyeol3/my-world.v2.git",
                "yuyeol3/my-world.v2"}) {
            assertEquals(expected, GitHubRepo.parse(address), address);
        }
    }

    @Test
    void rejectsOtherHostsAndPaths() {
        for (String address : new String[] {
                "https://gitlab.com/a/b", "https://github.com/a", "https://github.com/a/b/tree/main", "", "a/b/c"}) {
            assertThrows(IllegalArgumentException.class, () -> GitHubRepo.parse(address), address);
        }
    }

    @Test
    void sshRemoteUsesPort443AndRoundTrips() {
        GitHubRepo repo = new GitHubRepo("a", "b");
        assertEquals("ssh://git@ssh.github.com:443/a/b.git", repo.sshRemote());
        assertEquals(repo, GitHubRepo.parse(repo.sshRemote()));
        assertEquals("https://github.com/a/b/settings/keys/new", repo.newDeployKeyPage());
    }
}
