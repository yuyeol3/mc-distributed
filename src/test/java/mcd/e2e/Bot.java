package mcd.e2e;

import mcd.server.PaperBuild;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** e2e/bot.js(Mineflayer)를 띄워서 offline mode 플레이어 하나를 흉내 낸다. */
public final class Bot implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** 테스트 봇(Mineflayer 4.39)이 접속할 수 있는 Paper 빌드. 봇을 쓰는 테스트는 이 빌드로 월드를 만든다. */
    public static final PaperBuild PAPER_BUILD = PaperBuild.parse("26.1.2-74");

    private final Process process;
    private final Writer stdin;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    private Bot(Process process) {
        this.process = process;
        this.stdin = process.outputWriter(StandardCharsets.UTF_8);
        Thread.ofPlatform().daemon().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            } catch (IOException ignored) {
                // 프로세스가 끝나면 스트림이 닫힌다.
            }
        });
    }

    /** 접속해서 스폰될 때까지 기다린다. 작업 디렉터리는 프로젝트 루트라고 가정한다. */
    public static Bot join(String username, int port) throws IOException, TimeoutException, InterruptedException {
        return join(username, port, TIMEOUT);
    }

    /** spawnTimeout은 서버가 막 기동하는 경우처럼 접속이 오래 걸릴 때 늘린다. */
    public static Bot join(String username, int port, Duration spawnTimeout) throws IOException, TimeoutException, InterruptedException {
        return start(List.of("node", "e2e/bot.js", "localhost", Integer.toString(port), username), spawnTimeout);
    }

    /** command는 bot.js를 띄우는 명령(도커 실험실에서는 docker run -i ...). 스폰될 때까지 기다린다. */
    public static Bot start(List<String> command, Duration spawnTimeout) throws IOException, TimeoutException, InterruptedException {
        Bot bot = new Bot(new ProcessBuilder(command).redirectErrorStream(true).start());
        try {
            bot.await("SPAWNED", spawnTimeout);
        } catch (TimeoutException | IllegalStateException e) {
            bot.close();
            throw e;
        }
        return bot;
    }

    /**
     * mcd 중계로 접속한다. 호스트가 막 기동하는 중이면(새 폴더의 첫 기동은 25초를 넘는다) 중계가 연결을 닫으므로,
     * 실제 플레이어처럼 다시 접속해본다.
     */
    public static Bot joinViaRelay(String username, int relayPort) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return join(username, relayPort);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    /** 인벤토리를 "아이템:개수" 목록(정렬됨)을 쉼표로 이은 문자열로 돌려준다. */
    public String inventory() throws IOException, TimeoutException, InterruptedException {
        send("inventory");
        return await("INVENTORY ").substring("INVENTORY ".length()).trim();
    }

    /** 아이템 지급 등은 서버 로그보다 늦게 도착할 수 있으므로, 인벤토리가 expected가 될 때까지 최대 10초 기다린다. */
    public String awaitInventory(String expected) throws IOException, TimeoutException, InterruptedException {
        String actual = inventory();
        for (int i = 0; i < 20 && !actual.equals(expected); i++) {
            Thread.sleep(500);
            actual = inventory();
        }
        return actual;
    }

    /** 서버가 보낸 transfer를 따라 다른 주소로 다시 접속해 스폰될 때까지 기다리고, 옮겨 간 주소를 돌려준다. */
    public String awaitTransfer() throws TimeoutException, InterruptedException {
        String target = await("TRANSFER ").substring("TRANSFER ".length()).trim();
        await("SPAWNED");
        return target;
    }

    /** 서버 목록 확인(ping)을 한 번 보내고 응답 JSON(description, version, players)을 돌려준다. */
    public static String ping(int port) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("node", "e2e/ping.js", "localhost", Integer.toString(port)).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(30, TimeUnit.SECONDS);
        return out;
    }

    public void quit() throws IOException, InterruptedException {
        send("quit");
        process.waitFor(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }

    private void send(String command) throws IOException {
        stdin.write(command + "\n");
        stdin.flush();
    }

    private String await(String prefix) throws TimeoutException, InterruptedException {
        return await(prefix, TIMEOUT);
    }

    private String await(String prefix, Duration timeout) throws TimeoutException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            String line = lines.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line == null) throw new TimeoutException("봇 출력에서 '" + prefix + "'를 찾지 못했다");
            if (line.startsWith(prefix)) return line;
            if (line.startsWith("KICKED") || line.startsWith("ERROR") || line.startsWith("ENDED")) throw new IllegalStateException("봇: " + line);
        }
    }
}
