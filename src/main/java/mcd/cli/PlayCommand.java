package mcd.cli;

import mcd.lock.HostLock;
import mcd.node.Node;
import mcd.node.Timing;
import mcd.server.PaperDistribution;
import mcd.world.WorldTemplate;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.concurrent.CountDownLatch;

/** `mcd play`: 노드를 띄우고, 콘솔 입력(stop이나 Paper 명령)과 Ctrl+C를 처리한다. */
final class PlayCommand {

    record Options(String remote, Path dir, String node, int listenPort, int paperPort, String address,
                   boolean acceptEula, Path cacheDir, CredentialsProvider credentials, Timing timing) {}

    private final Options options;
    private final CountDownLatch stopRequested = new CountDownLatch(1);

    PlayCommand(Options options) {
        this.options = options;
    }

    void run() throws Exception {
        try (Git git = openOrClone()) {
            prepareEula();
            WorldTemplate.checkPaperBuild(options.dir());
            Path jar = PaperDistribution.ensureDownloaded(options.cacheDir());
            InstantSource clock = InstantSource.system();
            Timing t = options.timing();
            HostLock lock = new HostLock(git, options.node(), clock, t.ttl(), t.margin(), options.credentials());
            Node node = new Node(lock, options.dir(), jar, options.listenPort(), options.paperPort(), options.node(),
                    options.address(), t, clock, PlayCommand::log);

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
            Thread.ofVirtual().start(() -> readConsole(node));
            try {
                stopRequested.await();
                log("종료한다.");
            } finally {
                node.close();
                finished.countDown();
            }
        }
    }

    private Git openOrClone() throws Exception {
        if (Files.exists(options.dir().resolve(".git"))) {
            return Git.open(options.dir().toFile());
        }
        log(options.remote() + "에서 월드를 받는다.");
        return Git.cloneRepository().setURI(options.remote()).setDirectory(options.dir().toFile())
                .setCredentialsProvider(options.credentials()).call();
    }

    private void prepareEula() throws IOException {
        if (options.acceptEula()) {
            WorldTemplate.acceptEula(options.dir());
        } else if (!Files.exists(options.dir().resolve("eula.txt"))) {
            throw new IllegalStateException("Minecraft EULA(https://aka.ms/MinecraftEULA)에 동의하면 --accept-eula를 붙여 다시 실행한다.");
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
