package mcd.cli;

import mcd.server.PaperBuild;
import mcd.world.NetworkConfig;
import mcd.world.WorldRepository;
import org.eclipse.jgit.api.TransportConfigCallback;

import java.nio.file.Path;
import java.util.Optional;

final class InitCommand {

    private InitCommand() {}

    static void run(Path dir, String remote, boolean onlineMode, Optional<NetworkConfig> network, PaperBuild paperBuild,
                    TransportConfigCallback auth) throws Exception {
        WorldRepository.create(dir, remote, onlineMode, network, paperBuild, auth);
        System.out.println("[mcd] 새 월드를 " + remote + "에 만들었습니다. 친구들은 mcd play로 들어오면 됩니다.");
    }
}
