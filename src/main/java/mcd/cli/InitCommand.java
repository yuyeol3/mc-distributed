package mcd.cli;

import mcd.world.WorldRepository;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.nio.file.Path;

final class InitCommand {

    private InitCommand() {}

    static void run(Path dir, String remote, boolean onlineMode, CredentialsProvider credentials) throws Exception {
        WorldRepository.create(dir, remote, onlineMode, credentials);
        System.out.println("[mcd] 새 월드를 " + remote + "에 만들었다. 친구들은 mcd play로 들어오면 된다.");
    }
}
