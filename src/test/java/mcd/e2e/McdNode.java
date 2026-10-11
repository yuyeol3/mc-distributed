package mcd.e2e;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** `mcd play`를 별도 JVM 프로세스로 띄운다. 강제 종료로 PC가 꺼지는 상황을 흉내 낼 수 있다. */
public final class McdNode implements AutoCloseable {

    private final String name;
    private final Process process;
    private final Writer stdin;
    private final List<String> lines = new ArrayList<>();

    private McdNode(String name, Process process) {
        this.name = name;
        this.process = process;
        this.stdin = process.outputWriter(StandardCharsets.UTF_8);
        Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[" + name + "] " + line);
                    synchronized (lines) {
                        lines.add(line);
                        lines.notifyAll();
                    }
                }
            } catch (IOException ignored) {
                // 프로세스가 끝나면 스트림이 닫힌다.
            }
        });
    }

    /** listenPort는 봇이 접속할 중계 포트, listenPort + 1은 이 노드가 호스트일 때 Paper 포트다. */
    public static McdNode play(String name, String remote, Path dir, int listenPort, int ttlSeconds) throws IOException {
        List<String> cmd = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                "mcd.cli.Main", "play",
                "--remote", remote,
                "--dir", dir.toAbsolutePath().toString(),
                "--node", name,
                "--listen", Integer.toString(listenPort),
                "--port", Integer.toString(listenPort + 1),
                "--accept-eula",
                "--cache", "nodes/_cache",
                "--ttl-seconds", Integer.toString(ttlSeconds));
        return new McdNode(name, new ProcessBuilder(cmd).redirectErrorStream(true).start());
    }

    public int mark() {
        synchronized (lines) {
            return lines.size();
        }
    }

    /** from번째 줄부터 text를 포함한 줄을 기다려 돌려준다. */
    public String await(int from, String text, Duration timeout) throws TimeoutException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lines) {
            int next = from;
            while (true) {
                for (; next < lines.size(); next++) {
                    if (lines.get(next).contains(text)) return lines.get(next);
                }
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) throw new TimeoutException(name + " 출력에서 '" + text + "'를 찾지 못했다");
                lines.wait(Math.min(remaining, 1000));
            }
        }
    }

    public boolean printed(String text) {
        synchronized (lines) {
            return lines.stream().anyMatch(l -> l.contains(text));
        }
    }

    /** 콘솔 입력. 호스트일 때 stop이 아닌 입력은 Paper 콘솔로 넘어간다. */
    public void send(String line) throws IOException {
        stdin.write(line + "\n");
        stdin.flush();
    }

    /** 콘솔 명령을 보내고 그 뒤에 나온 출력에서 expected를 기다린다. */
    public String sendAndAwait(String line, String expected) throws IOException, TimeoutException, InterruptedException {
        int from = mark();
        send(line);
        return await(from, expected, Duration.ofSeconds(30));
    }

    public boolean waitForExit(Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 전원이 꺼진 것처럼 mcd와 자식 Paper 프로세스를 모두 강제 종료한다. */
    public void killHard() throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(30, TimeUnit.SECONDS);
    }

    /** mcd 프로세스만 강제 종료하고, 남은 자식 프로세스(Paper)를 돌려준다. */
    public List<ProcessHandle> killMcdOnly() throws InterruptedException {
        List<ProcessHandle> children = process.descendants().toList();
        process.destroyForcibly();
        process.waitFor(30, TimeUnit.SECONDS);
        return children;
    }

    @Override
    public void close() throws InterruptedException {
        if (process.isAlive()) killHard();
    }
}
