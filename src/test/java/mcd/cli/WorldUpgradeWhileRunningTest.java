package mcd.cli;

import mcd.e2e.Bot;
import mcd.e2e.McdNode;
import mcd.server.PaperDistribution;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 노드가 켜져 있는 동안 누가 월드의 Paper 버전을 올리면, 그 노드가 다음에 호스트가 될 때 새 버전으로 띄워야 한다.
 * 옛 버전으로 띄우면 새 버전에서 열린 월드가 망가질 수 있다. 테스트 봇은 26.2에 접속하지 못하므로, 중계에 로그인
 * 첫머리만 보내 호스트가 되게 한다.
 * 실행: ./gradlew paperTest --tests '*WorldUpgradeWhileRunningTest*'
 */
@Tag("paper")
class WorldUpgradeWhileRunningTest {

    private static final int RELAY = 25941;

    @TempDir
    Path tmp;

    @Test
    void runningNodeHostsWithTheUpgradedBuild() throws Exception {
        Path bare = tmp.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        InitCommand.run(tmp.resolve("init"), remote, false, Optional.empty(), Bot.PAPER_BUILD, null);

        try (McdNode a = McdNode.play("A", remote, tmp.resolve("A"), RELAY, 60)) {
            a.await(0, "[mcd] 준비됐습니다", Duration.ofMinutes(1));
            assertTrue(a.printed("Paper " + Bot.PAPER_BUILD + ")"), "처음에는 26.1.2 월드여야 한다");

            UpgradeCommand.run(tmp.resolve("owner"), remote, "owner", "26.2-133", Path.of("nodes/_cache"), null,
                    PaperDistribution.official(), m -> {});

            try (Socket login = loginStart(RELAY)) {
                a.await(0, "[mcd] 월드의 버전이 바뀌었습니다: 마인크래프트 26.2(Paper 26.2-133)", Duration.ofMinutes(1));
                a.await(0, "Starting minecraft server version 26.2", Duration.ofMinutes(2));
                a.await(0, "[mcd] Paper가 준비됐습니다", Duration.ofMinutes(3));
            }
            int m = a.mark();
            a.send("stop");
            a.await(m, "[mcd] 월드를 올리고 락을 반납했습니다.", Duration.ofMinutes(2));
        }
    }

    /** 핸드셰이크(다음 상태: 로그인)와 로그인 시작만 보낸다. 중계는 이것으로 접속을 붙잡고 호스트를 띄운다. */
    private static Socket loginStart(int port) throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
        OutputStream out = socket.getOutputStream();
        ByteArrayOutputStream handshake = new ByteArrayOutputStream();
        writeVarInt(handshake, 0x00);
        writeVarInt(handshake, 775);
        writeString(handshake, "localhost");
        new DataOutputStream(handshake).writeShort(port);
        writeVarInt(handshake, 2);
        ByteArrayOutputStream start = new ByteArrayOutputStream();
        writeVarInt(start, 0x00);
        writeString(start, "probe");
        start.write(new byte[16]);
        for (ByteArrayOutputStream packet : new ByteArrayOutputStream[] {handshake, start}) {
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            writeVarInt(framed, packet.size());
            packet.writeTo(framed);
            out.write(framed.toByteArray());
        }
        out.flush();
        return socket;
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
