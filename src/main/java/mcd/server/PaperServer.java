package mcd.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paper 서버 프로세스를 띄우고 콘솔(stdin/stdout)로 제어한다.
 * 기다리는 로그 문구는 Paper 26.1.2에서 실제로 확인한 것이다.
 */
public final class PaperServer implements AutoCloseable {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(60);
    private static final String READY = "Done (";
    private static final Pattern PLAYERS = Pattern.compile("There are (\\d+) of");

    private final Process process;
    private final Writer stdin;
    private final PrintStream echo;
    private final List<String> lines = new ArrayList<>();
    private boolean ready;

    private PaperServer(Process process, PrintStream echo) {
        this.process = process;
        this.echo = echo;
        this.stdin = process.outputWriter(StandardCharsets.UTF_8);
        // 프로세스 출력 읽기는 가상 스레드의 캐리어를 붙잡으므로 플랫폼 스레드로 읽는다.
        Thread.ofPlatform().daemon().name("paper-output").start(this::readOutput);
    }

    /**
     * serverDir에서 Paper를 띄우고 기동이 끝날 때까지 기다린다. eula.txt와 server.properties는 호출자가 준비한다.
     * server.properties는 노드끼리 공유하므로 포트는 실행 옵션으로 넘긴다. echo가 null이 아니면 콘솔 출력을 그대로 내보낸다.
     */
    public static PaperServer start(Path serverDir, Path paperJar, int port, Duration startTimeout, PrintStream echo)
            throws IOException, TimeoutException {
        PaperServer server = launch(serverDir, paperJar, port, echo);
        server.awaitLine(0, READY, startTimeout);
        return server;
    }

    /** 기동을 기다리지 않고 프로세스만 띄운다. 기동 완료는 {@link #isReady}로 확인한다. */
    public static PaperServer launch(Path serverDir, Path paperJar, int port, PrintStream echo) throws IOException {
        String agent = "-javaagent:" + ParentWatchdog.createAgentJar();
        Process process = new ProcessBuilder(javaExecutable(), "-Xmx2G", agent, "-jar", paperJar.toAbsolutePath().toString(),
                "--nogui", "--port", Integer.toString(port))
                .directory(serverDir.toFile())
                .redirectErrorStream(true)
                .start();
        return new PaperServer(process, echo);
    }

    /**
     * 서버는 띄우지 않고 Paperclip이 Mojang jar를 받아 패치만 해 두게 한다(serverDir의 cache/, libraries/, versions/).
     * 처음이면 20초 가까이, 이미 했으면 1초 안쪽이 걸린다. 호스트가 된 뒤 첫 기동에서 이 시간을 빼려고 미리 한다.
     */
    public static void patch(Path serverDir, Path paperJar, PrintStream echo) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(javaExecutable(), "-Dpaperclip.patchonly=true", "-jar",
                paperJar.toAbsolutePath().toString())
                .directory(serverDir.toFile())
                .redirectErrorStream(true)
                .start();
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (echo != null) echo.println(line);
            }
        }
        if (process.waitFor() != 0) {
            throw new IOException("Paperclip이 종료 코드 " + process.exitValue() + "로 끝났습니다");
        }
    }

    public boolean isReady() {
        synchronized (lines) {
            return ready;
        }
    }

    public void command(String command) throws IOException {
        stdin.write(command + "\n");
        stdin.flush();
    }

    /** 명령을 보내고, 보낸 뒤에 expected를 포함한 줄이 나올 때까지 기다려 그 줄을 돌려준다. */
    public String commandAndAwait(String command, String expected) throws IOException, TimeoutException {
        int from = lineCount();
        command(command);
        return awaitLine(from, expected, COMMAND_TIMEOUT);
    }

    /** 기동이 끝날 때까지 기다린다. 시간 안에 끝나지 않거나 프로세스가 죽으면 false. */
    public boolean awaitReady(Duration timeout) {
        try {
            awaitLine(0, READY, timeout);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /** 접속 중인 플레이어 수. `list` 출력("There are N of a max of M players online")에서 읽는다. */
    public int onlinePlayers() throws IOException, TimeoutException {
        Matcher m = PLAYERS.matcher(commandAndAwait("list", "players online"));
        if (!m.find()) {
            throw new IllegalStateException("list 출력을 해석하지 못했습니다");
        }
        return Integer.parseInt(m.group(1));
    }

    /** 자동 저장을 멈추고 모든 청크를 디스크에 쓴다. 이 상태에서 월드 파일을 커밋한다. */
    public void pauseSavingAndFlush() throws IOException, TimeoutException {
        commandAndAwait("save-off", "Automatic saving is now disabled");
        commandAndAwait("save-all flush", "Saved the game");
    }

    public void resumeSaving() throws IOException, TimeoutException {
        commandAndAwait("save-on", "Automatic saving is now enabled");
    }

    /** 얼릴 플레이어 속성과 바닐라 기본값. 1.20.5부터 있는 속성이다. */
    private static final String[][] FROZEN_ATTRIBUTES = {
            {"movement_speed", "0.1"}, {"jump_strength", "0.42"},
            {"block_interaction_range", "4.5"}, {"entity_interaction_range", "3.0"}};

    /**
     * 모든 플레이어가 움직이거나 블록·엔티티와 상호작용하지 못하게 한다. 저장을 멈춘 뒤의 플레이는 넘긴 월드에 들어가지
     * 않으므로, 그동안 한 일이 사라지는 대신 할 수 없게 한다. 바뀐 속성은 저장을 멈춘 뒤라 디스크에 남지 않는다.
     */
    public void freezePlayers(String message) throws IOException {
        for (String[] attribute : FROZEN_ATTRIBUTES) {
            command("execute as @a run attribute @s minecraft:" + attribute[0] + " base set 0");
        }
        command("title @a title \"" + message + "\"");
    }

    /** {@link #freezePlayers}를 되돌린다. 저장을 다시 켜기 전에 불러야 얼린 속성이 저장되지 않는다. */
    public void unfreezePlayers() throws IOException {
        for (String[] attribute : FROZEN_ATTRIBUTES) {
            command("execute as @a run attribute @s minecraft:" + attribute[0] + " base set " + attribute[1]);
        }
        command("title @a clear");
    }

    /** 정상 종료한다. 종료 과정에서 월드 저장이 끝난다. */
    public void stop(Duration timeout) throws IOException, InterruptedException, TimeoutException {
        command("stop");
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Paper가 " + timeout + " 안에 종료되지 않았습니다");
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** 종료 코드 0은 stop으로 저장을 마치고 끝났다는 뜻이다. 프로세스가 끝난 뒤에만 부른다. */
    public boolean exitedCleanly() {
        return process.exitValue() == 0;
    }

    public List<String> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }

    private int lineCount() {
        synchronized (lines) {
            return lines.size();
        }
    }

    private String awaitLine(int from, String expected, Duration timeout) throws TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lines) {
            int next = from;
            while (true) {
                for (; next < lines.size(); next++) {
                    if (lines.get(next).contains(expected)) return lines.get(next);
                }
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0 || !process.isAlive()) {
                    throw new TimeoutException("Paper 로그에서 '" + expected + "'를 찾지 못했습니다");
                }
                try {
                    lines.wait(Math.min(remaining, 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TimeoutException("기다리는 중 인터럽트됨");
                }
            }
        }
    }

    private void readOutput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (echo != null) echo.println(line);
                synchronized (lines) {
                    if (line.contains(READY)) ready = true;
                    lines.add(line);
                    lines.notifyAll();
                }
            }
        } catch (IOException ignored) {
            // 프로세스가 끝나면 스트림이 닫힌다.
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
