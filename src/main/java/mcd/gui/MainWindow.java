package mcd.gui;

import mcd.cli.UpgradeCommand;
import mcd.config.Settings;
import mcd.config.Settings.World;
import mcd.node.Node;
import mcd.node.Timing;
import mcd.server.PaperDistribution;
import mcd.update.AppVersion;
import mcd.update.Updater;
import mcd.world.WorldRepository;
import mcd.world.WorldTemplate;
import mcd.world.DeployKey;
import org.eclipse.jgit.api.TransportConfigCallback;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 고른 월드의 노드를 띄우고 상태, 접속 안내, 로그를 보여준다. 월드 고르기, 추가, 목록에서 빼기, 버전 올리기, 설정도 여기서 한다.
 * 노드를 켜고 끄는 일은 모두 한 스레드(control)에서 차례로 한다. 창을 닫으면 저장하고 락을 반납한 뒤 끝난다.
 */
final class MainWindow extends JFrame {

    private static final int LISTEN_PORT = 25565;
    private static final int PAPER_PORT = 25566;
    private static final int MAX_LOG_CHARS = 200_000;
    private static final Path CACHE = Path.of(System.getProperty("user.home"), ".mcd", "cache");
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");

    private volatile Settings settings;
    private final JLabel status = new JLabel("준비하는 중...");
    private final JComboBox<World> worldBox = new JComboBox<>();
    private final JButton addWorld = new JButton("월드 추가");
    private final JButton removeWorld = new JButton("목록에서 빼기");
    private final JButton upgrade = new JButton("버전 올리기");
    private final JButton account = new JButton("설정");
    private final JTextArea logArea = new JTextArea(24, 90);
    private final JTextField command = new JTextField();
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("node-control").unstarted(r));
    /** 목록을 다시 채우는 동안 생기는 선택 이벤트를 무시한다(EDT에서만 씀). */
    private boolean refreshing;
    private volatile Node node;

    MainWindow(Settings settings) {
        super("mcd - " + settings.nodeName());
        this.settings = settings;

        JLabel howTo = new JLabel("마인크래프트 → 멀티플레이 → 서버 추가 → 주소 localhost:" + LISTEN_PORT);
        howTo.setFont(howTo.getFont().deriveFont(Font.BOLD, 16f));
        DefaultListCellRenderer names = new DefaultListCellRenderer();
        worldBox.setRenderer((list, world, index, selected, focused) -> {
            java.awt.Component cell = names.getListCellRendererComponent(list, world == null ? "(월드 없음)" : world.name(), index, selected, focused);
            names.setToolTipText(world == null ? null : world.remote());
            return cell;
        });
        worldBox.addActionListener(e -> onWorldSelected());
        addWorld.addActionListener(e -> addWorld());
        removeWorld.addActionListener(e -> removeWorld());
        upgrade.addActionListener(e -> upgradeWorld());
        account.addActionListener(e -> editAccount());
        JPanel worldBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        worldBar.add(new JLabel("월드"));
        worldBar.add(worldBox);
        worldBar.add(addWorld);
        worldBar.add(removeWorld);
        worldBar.add(upgrade);
        worldBar.add(account);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        top.add(worldBar, BorderLayout.NORTH);
        top.add(howTo, BorderLayout.CENTER);
        top.add(status, BorderLayout.SOUTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton send = new JButton("Paper에 명령 보내기");
        JButton quit = new JButton("종료");
        send.addActionListener(e -> sendCommand());
        command.addActionListener(e -> sendCommand());
        quit.addActionListener(e -> shutdown());
        JPanel bottom = new JPanel(new BorderLayout(6, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));
        bottom.add(command, BorderLayout.CENTER);
        JPanel buttons = new JPanel();
        buttons.add(send);
        buttons.add(quit);
        bottom.add(buttons, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(logArea), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
        refreshWorlds();
        pack();
        setLocationRelativeTo(null);
    }

    void start() {
        setVisible(true);
        inControl(this::startNode);
        Thread.ofPlatform().daemon().name("update-check").start(this::checkForUpdate);
    }

    // ---- 노드 켜고 끄기 (control 스레드) ----

    private void startNode() {
        Optional<World> world = settings.currentWorld();
        if (world.isEmpty()) {
            log("월드가 없다. '월드 추가'로 새 월드를 만들거나 친구의 월드에 참가한다.");
            return;
        }
        try {
            Settings s = settings;
            Node.Config config = new Node.Config(world.get().remote(), world.get().dir(), s.nodeName(), LISTEN_PORT, PAPER_PORT,
                    s.address(), CACHE, auth(world.get()), Timing.fromTtl(Timing.DEFAULT_TTL), LISTEN_PORT);
            Node opened = Node.open(config, true, this::log, new PrintStream(new LineSink(), true, StandardCharsets.UTF_8));
            opened.start();
            node = opened;
        } catch (Exception e) {
            log("시작하지 못했다: " + e.getMessage());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "시작하지 못했다: " + e.getMessage()));
        }
    }

    /** 호스트였다면 저장하고 락을 반납한다(접속 중인 사람이 있으면 넘긴다). */
    private void stopNode() {
        Node current = node;
        if (current == null) return;
        node = null;
        try {
            current.close();
        } catch (Exception e) {
            log("끝내는 중 오류: " + e.getMessage());
        }
    }

    private void restartNode() {
        stopNode();
        startNode();
    }

    /** 노드 작업을 control 스레드에서 하고, 그동안 월드/설정 버튼을 막는다. */
    private void inControl(Runnable task) {
        setControlsEnabled(false);
        control.execute(() -> {
            try {
                task.run();
            } finally {
                SwingUtilities.invokeLater(() -> setControlsEnabled(true));
            }
        });
    }

    private void setControlsEnabled(boolean enabled) {
        boolean hasWorld = settings.currentWorld().isPresent();
        worldBox.setEnabled(enabled);
        addWorld.setEnabled(enabled);
        removeWorld.setEnabled(enabled && hasWorld);
        upgrade.setEnabled(enabled && hasWorld);
        account.setEnabled(enabled);
    }

    // ---- 월드와 설정 (EDT) ----

    private void refreshWorlds() {
        refreshing = true;
        try {
            worldBox.removeAllItems();
            settings.worlds().forEach(worldBox::addItem);
            settings.currentWorld().ifPresent(worldBox::setSelectedItem);
            worldBox.setToolTipText(settings.currentWorld().map(World::remote).orElse(null));
        } finally {
            refreshing = false;
        }
    }

    private void onWorldSelected() {
        if (refreshing || !(worldBox.getSelectedItem() instanceof World chosen)) return;
        if (settings.currentWorld().map(chosen::equals).orElse(false)) return;
        if (!save(settings.withCurrent(chosen.remote()))) return;
        worldBox.setToolTipText(chosen.remote());
        log("월드를 " + chosen.name() + "(으)로 바꾼다.");
        inControl(this::restartNode);
    }

    private void addWorld() {
        World world = AddWorldDialog.run(this, settings);
        if (world == null || !save(settings.withWorld(world))) return;
        refreshWorlds();
        inControl(this::restartNode);
    }

    private void removeWorld() {
        Optional<World> current = settings.currentWorld();
        if (current.isEmpty()) return;
        World world = current.get();
        int answer = JOptionPane.showConfirmDialog(this, "월드 " + world.name() + "을(를) 목록에서 뺀다.\n"
                + "GitHub의 월드와 이 PC의 사본(" + world.dir() + ")은 지우지 않으므로 나중에 다시 추가할 수 있다.",
                "목록에서 빼기", JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION || !save(settings.withoutWorld(world.remote()))) return;
        refreshWorlds();
        inControl(this::restartNode);
    }

    /** 월드의 Paper 버전을 올린다. 이 PC의 노드를 잠시 끄고, 락을 잡아 버전 파일을 바꾼 뒤 다시 켠다. */
    private void upgradeWorld() {
        Optional<World> current = settings.currentWorld();
        if (current.isEmpty()) return;
        World world = current.get();
        String now;
        try {
            now = WorldRepository.exists(world.dir()) ? WorldTemplate.paperBuild(world.dir()).toString() : "알 수 없음";
        } catch (Exception e) {
            now = "알 수 없음";
        }
        Object target = JOptionPane.showInputDialog(this,
                "월드 " + world.name() + "의 지금 버전: Paper " + now + "\n"
                        + "올릴 버전(예: 26.2, 또는 빌드까지 26.2-133). 버전만 쓰면 그 버전의 최신 안정 빌드를 쓴다.",
                "월드 버전 올리기", JOptionPane.QUESTION_MESSAGE, null, null, PaperDistribution.DEFAULT_BUILD.version());
        if (target == null || target.toString().isBlank()) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "월드를 " + target.toString().trim() + "(으)로 올리면 되돌릴 수 없고, 모두 그 버전의 마인크래프트로 접속해야 한다.\n"
                        + "누가 플레이 중이면 올릴 수 없다. 이 PC가 호스트라면 저장하고 넘긴 뒤 올린다. 계속할까?",
                "월드 버전 올리기", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        String version = target.toString().trim();
        inControl(() -> {
            stopNode();
            try {
                UpgradeCommand.run(world.dir(), world.remote(), settings.nodeName(), version, CACHE, auth(world),
                        PaperDistribution.official(), this::log);
            } catch (Exception e) {
                log("버전을 올리지 못했다: " + e.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "버전을 올리지 못했다: " + e.getMessage()));
            }
            startNode();
        });
    }

    private void editAccount() {
        Settings next = AccountDialog.edit(this, settings);
        if (next == null || !save(next)) return;
        setTitle("mcd - " + next.nodeName());
        log("설정을 바꿨다. 노드를 다시 시작한다.");
        inControl(this::restartNode);
    }

    private boolean save(Settings next) {
        try {
            next.save(Settings.defaultFile());
            settings = next;
            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "설정을 저장하지 못했다: " + e.getMessage());
            return false;
        }
    }

    /** 월드에 deploy key가 있으면 그 키로 SSH, 없으면 설정의 GitHub 토큰으로 https 접속한다. */
    private TransportConfigCallback auth(World world) throws java.security.GeneralSecurityException {
        return world.deployKey().isEmpty()
                ? WorldRepository.tokenAuth(settings.token())
                : DeployKey.decode(world.deployKey()).transport();
    }

    // ---- 업데이트, 명령, 종료 ----

    /** 새 버전이 있으면 물어보고, 동의하면 받아서 검증한 뒤 mcd를 정상 종료하고 바꾼 다음 다시 켠다. */
    private void checkForUpdate() {
        Optional<AppVersion> current = AppVersion.current();
        Optional<Path> installDir = Updater.installDir();
        if (current.isEmpty() || installDir.isEmpty()) return; // 개발 중 실행
        try {
            Updater updater = Updater.official();
            Optional<Updater.Release> release = updater.newerThan(current.get());
            if (release.isEmpty()) return;
            String question = "새 버전 " + release.get().version() + "이(가) 있다(지금 " + current.get() + "). 지금 업데이트할까?\n"
                    + "월드를 저장하고 mcd를 껐다가, 바꾼 뒤 다시 켠다. 호스트라면 접속 중인 사람은 다른 PC로 옮겨 간다.";
            int[] answer = new int[1];
            SwingUtilities.invokeAndWait(() ->
                    answer[0] = JOptionPane.showConfirmDialog(this, question, "mcd 업데이트", JOptionPane.YES_NO_OPTION));
            if (answer[0] != JOptionPane.YES_OPTION) return;
            log("새 버전 " + release.get().version() + "을(를) 받는 중이다.");
            Path newApp = updater.download(release.get());
            updater.launchReplacer(newApp, installDir.get(), Optional.of(installDir.get().resolve("mcd.exe")));
            log("업데이트를 준비했다. mcd를 껐다가 다시 켠다.");
            shutdown();
        } catch (Exception e) {
            log("업데이트하지 못했다: " + e.getMessage());
        }
    }

    private void sendCommand() {
        String text = command.getText().trim();
        Node current = node;
        if (text.isEmpty() || current == null) return;
        command.setText("");
        try {
            if (!current.consoleCommand(text)) {
                log("이 PC는 지금 호스트가 아니라서 Paper 명령을 보낼 수 없다.");
            }
        } catch (Exception e) {
            log("명령을 보내지 못했다: " + e.getMessage());
        }
    }

    private void shutdown() {
        SwingUtilities.invokeLater(() -> {
            status.setText("저장하고 끝내는 중...");
            setControlsEnabled(false);
        });
        control.execute(() -> {
            stopNode();
            System.exit(0);
        });
    }

    /** mcd 안내 메시지. 상태 줄에도 보여준다. */
    private void log(String message) {
        SwingUtilities.invokeLater(() -> status.setText(message));
        append("[mcd] " + message);
    }

    private void append(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + "\n");
            int overflow = logArea.getDocument().getLength() - MAX_LOG_CHARS;
            if (overflow > 0) logArea.replaceRange("", 0, overflow);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /** Paper 콘솔 출력을 한 줄씩 로그 창에 붙인다. */
    private final class LineSink extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                // Paper 콘솔의 ANSI 색상 코드는 로그 창에서 의미가 없으므로 지운다.
                append(ANSI.matcher(buffer.toString(StandardCharsets.UTF_8)).replaceAll("").stripTrailing());
                buffer.reset();
            } else {
                buffer.write(b);
            }
        }
    }
}
