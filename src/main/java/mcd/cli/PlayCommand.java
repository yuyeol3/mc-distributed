package mcd.cli;

import mcd.node.Node;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/** `mcd play`: 노드를 띄우고, 콘솔 입력(stop이나 Paper 명령)과 Ctrl+C를 처리한다. */
final class PlayCommand {

    private final Node.Config config;
    private final boolean acceptEula;
    private final CountDownLatch stopRequested = new CountDownLatch(1);

    PlayCommand(Node.Config config, boolean acceptEula) {
        this.config = config;
        this.acceptEula = acceptEula;
    }

    void run() throws Exception {
        Node node = Node.open(config, acceptEula, PlayCommand::log, System.out);
        CountDownLatch finished = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stopRequested.countDown();
            try {
                finished.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        node.start();
        // stdin 읽기는 가상 스레드의 캐리어를 붙잡으므로 플랫폼 스레드로 읽는다.
        Thread.ofPlatform().daemon().name("console").start(() -> readConsole(node));
        try {
            stopRequested.await();
            log("종료한다.");
        } finally {
            node.close();
            finished.countDown();
        }
    }

    /** stop은 직접 처리하고, 나머지는 호스트일 때 Paper 콘솔로 넘긴다. */
    private void readConsole(Node node) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.trim().equals("stop")) {
                    stopRequested.countDown();
                } else if (!node.consoleCommand(line)) {
                    log("이 PC는 지금 호스트가 아니다. 끝내려면 stop을 입력한다.");
                }
            }
        } catch (IOException ignored) {
            // 콘솔이 닫히면 더 읽을 것이 없다.
        }
    }

    static void log(String message) {
        System.out.println("[mcd] " + message);
    }
}
