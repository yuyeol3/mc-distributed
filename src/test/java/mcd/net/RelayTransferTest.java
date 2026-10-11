package mcd.net;

import mcd.e2e.Bot;
import mcd.server.PaperServer;
import mcd.world.WorldTemplate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실험 E6: 바이트를 그대로 넘기는 중계를 거쳐도 플레이가 정상이고, 호스트가 `transfer localhost <중계 포트>`를
 * 보내면 클라이언트가 자기 중계로 다시 접속해 새 호스트에 들어가는지 확인한다.
 */
@Tag("paper")
class RelayTransferTest {

    private static final Path PAPER_JAR = Path.of("nodes/_cache/paper-26.1.2-74.jar");

    @TempDir
    Path tmp;

    @Test
    void playThroughRelayAndFollowTransferToNewHost() throws Exception {
        assumeTrue(Files.exists(PAPER_JAR), "Paper jar가 없다");
        Path dirA = server("A");
        Path dirB = server("B");

        try (PaperServer a = PaperServer.start(dirA, PAPER_JAR, 25651, Duration.ofMinutes(3), null);
             PaperServer b = PaperServer.start(dirB, PAPER_JAR, 25652, Duration.ofMinutes(3), null)) {
            AtomicReference<InetSocketAddress> host = new AtomicReference<>(local(25651));
            try (Relay relay = Relay.start(0, host::get);
                 Bot bot = Bot.join("e6bot", relay.port())) {
                a.commandAndAwait("give e6bot minecraft:diamond 2", "Gave 2");
                assertEquals("diamond:2", bot.awaitInventory("diamond:2"), "중계를 거쳐도 플레이가 정상이어야 한다");

                host.set(local(25652));
                a.command("transfer localhost " + relay.port() + " e6bot");
                assertEquals("localhost:" + relay.port(), bot.awaitTransfer());

                assertTrue(b.lines().stream().anyMatch(l -> l.contains("e6bot joined the game")), "새 호스트에 접속해야 한다");
                b.commandAndAwait("give e6bot minecraft:emerald 1", "Gave 1");
                assertEquals("emerald:1", bot.awaitInventory("emerald:1"), "B는 다른 월드이므로 B에서 준 아이템만 있다");
                bot.quit();
            }
            a.stop(Duration.ofMinutes(1));
            b.stop(Duration.ofMinutes(1));
        }
    }

    private Path server(String name) throws Exception {
        Path dir = tmp.resolve(name);
        Files.createDirectories(dir);
        WorldTemplate.writeInitialFiles(dir, false);
        WorldTemplate.acceptEula(dir);
        return dir;
    }

    private static InetSocketAddress local(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
