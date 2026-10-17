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
import mcd.world.GitHubRepo;
import mcd.world.Invite;
import org.eclipse.jgit.api.TransportConfigCallback;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import javax.swing.UIManager;
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
    private final StatusPanel statusPanel = new StatusPanel("localhost:" + LISTEN_PORT);
    private final JComboBox<World> worldBox = new JComboBox<>();
    private final JButton addWorld = new JButton("+ 월드 추가");
    private final JButton removeWorld = new JButton("목록에서 빼기");
    private final JButton upgrade = new JButton("버전 올리기");
    private final JButton inviteButton = new JButton("친구 초대");
    private final JButton account = new JButton("설정");
    private final JButton quit = new JButton("종료");
    private final JToggleButton logToggle = new JToggleButton("▸ 로그와 Paper 콘솔");
    private final JPanel logSection = new JPanel(new BorderLayout(0, 6));
    private final JTextArea logArea = new JTextArea(18, 80);
    private final JTextField command = new JTextField();
    private final ExecutorService control = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("node-control").unstarted(r));
    /** 목록을 다시 채우는 동안 생기는 선택 이벤트를 무시한다(EDT에서만 씀). */
    private boolean refreshing;
    /** control 스레드에서 노드를 켜거나 끄는 중이다. */
    private volatile boolean busy;
    private volatile boolean closing;
    private volatile Node node;

    MainWindow(Settings settings) {
        super("mcd — " + settings.nodeName());
        this.settings = settings;

        DefaultListCellRenderer names = new DefaultListCellRenderer();
        worldBox.setRenderer((list, world, index, selected, focused) -> {
            java.awt.Component cell = names.getListCellRendererComponent(list, world == null ? "(월드 없음)" : world.name(), index, selected, focused);
            names.setToolTipText(world == null ? null : world.remote());
            return cell;
        });
        worldBox.setFont(worldBox.getFont().deriveFont(Font.BOLD, worldBox.getFont().getSize2D() + 2f));
        worldBox.setPrototypeDisplayValue(new World("x", Path.of("minecraft-distributed-world")));
        worldBox.addActionListener(e -> onWorldSelected());
        addWorld.addActionListener(e -> addWorld());
        removeWorld.addActionListener(e -> removeWorld());
        upgrade.addActionListener(e -> upgradeWorld());
        inviteButton.addActionListener(e -> invite());
        inviteButton.setBackground(UIManager.getColor("Button.default.background"));
        inviteButton.setForeground(UIManager.getColor("Button.default.foreground"));
        inviteButton.setFont(inviteButton.getFont().deriveFont(Font.BOLD));
        account.addActionListener(e -> editAccount());

        JPanel header = new JPanel(new BorderLayout(8, 0));
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerRight.add(addWorld);
        headerRight.add(account);
        header.add(worldBox, BorderLayout.WEST);
        header.add(headerRight, BorderLayout.EAST);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.add(inviteButton);
        actions.add(upgrade);
        actions.add(removeWorld);

        logArea.setEditable(false);
        // 긴 줄 때문에 오른쪽 끝으로 가로 스크롤되면 짧은 줄들이 안 보인다.
        logArea.setLineWrap(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(Theme.LOG_BACKGROUND);
        logArea.setForeground(Theme.LOG_FOREGROUND);
        logArea.setCaretColor(Theme.LOG_FOREGROUND);
        JButton send = new JButton("Paper에 보내기");
        send.addActionListener(e -> sendCommand());
        command.addActionListener(e -> sendCommand());
        command.putClientProperty("JTextField.placeholderText", "Paper 콘솔 명령(이 PC가 호스트일 때), 예: op <이름>");
        JPanel commandRow = new JPanel(new BorderLayout(6, 0));
        commandRow.add(command, BorderLayout.CENTER);
        commandRow.add(send, BorderLayout.EAST);
        logSection.add(new JScrollPane(logArea), BorderLayout.CENTER);
        logSection.add(commandRow, BorderLayout.SOUTH);
        logSection.setVisible(false);
        logToggle.putClientProperty("JButton.buttonType", "borderless");
        // 노드를 켜는 동안 다른 컨트롤이 잠깐 꺼지면 포커스가 여기로 와서 테두리가 남는다.
        logToggle.setFocusable(false);
        logToggle.addActionListener(e -> toggleLog());

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        for (JComponent c : new JComponent[] {header, statusPanel, actions, logToggle}) {
            c.setAlignmentX(LEFT_ALIGNMENT);
            top.add(c);
            top.add(Box.createVerticalStrut(10));
        }

        quit.setToolTipText("월드를 저장하고 올린 뒤 끝냅니다(호스트라면 접속 중인 사람에게 넘깁니다). 창을 닫아도 같습니다.");
        quit.addActionListener(e -> shutdown());
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bottom.add(quit);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 14, 16));
        root.add(top, BorderLayout.NORTH);
        root.add(logSection, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        setContentPane(root);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });
        refreshWorlds();
        refreshStatus();
        new Timer(1000, e -> refreshStatus()).start();
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(null);
    }

    void start() {
        setVisible(true);
        worldBox.requestFocusInWindow();
        inControl(this::startNode);
        Thread.ofPlatform().daemon().name("update-check").start(this::checkForUpdate);
    }

    /** 로그 영역을 펼치거나 접는다. 펼치면 창을 키우고 접으면 줄인다. */
    private void toggleLog() {
        boolean show = logToggle.isSelected();
        logSection.setVisible(show);
        logToggle.setText(show ? "▾ 로그와 Paper 콘솔" : "▸ 로그와 Paper 콘솔");
        if (show) {
            setSize(getWidth(), Math.max(getHeight(), getMinimumSize().height + 300));
        } else {
            setSize(getWidth(), getMinimumSize().height);
        }
        revalidate();
    }

    /** 상태 카드를 노드 상태로 갱신한다(EDT, 1초마다). */
    private void refreshStatus() {
        if (closing) {
            statusPanel.show(Theme.GRAY, "저장하고 끝내는 중", "호스트라면 월드를 올리고 접속 중인 사람에게 넘깁니다.");
            return;
        }
        if (settings.currentWorld().isEmpty()) {
            statusPanel.show(Theme.GRAY, "월드 없음","'+ 월드 추가'로 새 월드를 만들거나 초대 코드로 참가하세요.");
            return;
        }
        Node current = node;
        if (current == null) {
            if (busy) {
                statusPanel.show(Theme.ORANGE, "준비하는 중", "월드를 받고 서버 파일을 확인하고 있습니다.");
            } else {
                statusPanel.show(Theme.RED, "꺼져 있음","아래 로그에서 이유를 확인하세요. 월드를 다시 고르면 다시 시작합니다.");
            }
            return;
        }
        Node.Status s = current.status();
        switch (s.phase()) {
            case CHECKING -> statusPanel.show(Theme.GRAY, "원격 확인 중", "누가 호스트인지 확인하고 있습니다.");
            case NO_HOST -> statusPanel.show(Theme.GRAY, "호스트 없음", "접속하면 이 PC가 서버가 됩니다(10~20초).");
            case OTHER_HOST -> statusPanel.show(Theme.GREEN, "지금 호스트: " + s.host(), "접속하면 그 PC의 서버로 이어집니다.");
            case STARTING -> statusPanel.show(Theme.ORANGE, "이 PC가 서버를 띄우는 중", "잠시 뒤 접속됩니다. 접속 화면에서 기다려 주세요.");
            case HOSTING -> statusPanel.show(Theme.GREEN, "이 PC가 호스트",
                    s.players() == 0 ? "접속한 사람이 없습니다. 아무도 없으면 5분 뒤 스스로 내려갑니다." : "접속 " + s.players() + "명");
        }
    }

    // ---- 노드 켜고 끄기 (control 스레드) ----

    private void startNode() {
        Optional<World> world = settings.currentWorld();
        if (world.isEmpty()) {
            log("월드가 없습니다. '월드 추가'로 새 월드를 만들거나 친구의 월드에 참가하세요.");
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
            log("시작하지 못했습니다: " + e.getMessage());
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "시작하지 못했습니다: " + e.getMessage()));
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
            busy = true;
            try {
                task.run();
            } finally {
                busy = false;
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
        inviteButton.setEnabled(enabled && hasWorld);
        account.setEnabled(enabled);
        // 컨트롤을 끈 동안 포커스가 다른 버튼(주로 종료)으로 넘어간다. 다시 켜면 월드 선택으로 돌려놓는다.
        if (enabled && (getFocusOwner() == null || getFocusOwner() == quit)) {
            worldBox.requestFocusInWindow();
        }
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
        log("월드를 " + chosen.name() + "(으)로 바꿉니다.");
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
        int answer = JOptionPane.showConfirmDialog(this, "월드 " + world.name() + "을(를) 목록에서 뺍니다.\n"
                + "GitHub의 월드와 이 PC의 사본(" + world.dir() + ")은 지우지 않으므로 나중에 다시 추가할 수 있습니다.",
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
                        + "올릴 버전(예: 26.2, 또는 빌드까지 26.2-133). 버전만 쓰면 그 버전의 최신 안정 빌드를 씁니다.",
                "월드 버전 올리기", JOptionPane.QUESTION_MESSAGE, null, null, PaperDistribution.DEFAULT_BUILD.version());
        if (target == null || target.toString().isBlank()) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "월드를 " + target.toString().trim() + "(으)로 올리면 되돌릴 수 없고, 모두 그 버전의 마인크래프트로 접속해야 합니다.\n"
                        + "누가 플레이 중이면 올릴 수 없습니다. 이 PC가 호스트라면 저장하고 넘긴 뒤 올립니다. 계속할까요?",
                "월드 버전 올리기", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        String version = target.toString().trim();
        inControl(() -> {
            stopNode();
            try {
                UpgradeCommand.run(world.dir(), world.remote(), settings.nodeName(), version, CACHE, auth(world),
                        PaperDistribution.official(), this::log);
            } catch (Exception e) {
                log("버전을 올리지 못했습니다: " + e.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "버전을 올리지 못했습니다: " + e.getMessage()));
            }
            startNode();
        });
    }

    /**
     * 고른 월드의 초대 코드를 보여준다. 월드에 deploy key가 없으면(토큰으로 쓰던 월드) 등록을 안내하고, 확인되면 이 PC도
     * 그 키로 접속하도록 월드를 SSH로 바꾼 뒤 노드를 다시 켠다.
     */
    private void invite() {
        Optional<World> current = settings.currentWorld();
        if (current.isEmpty()) return;
        World world = current.get();
        try {
            if (world.deployKey().isEmpty()) {
                world = changeKey(world, "초대 코드를 만들려면 이 월드의 GitHub repo에 deploy key를 한 번 등록해야 합니다.\n"
                        + "등록하면 이 PC도 토큰 대신 그 키로 접속합니다. 이 PC의 tailnet 기기는 한 번 새로 등록됩니다.");
            } else {
                String[] options = {"초대 코드 복사", "키 바꾸기(초대 끊기)", "취소"};
                int choice = JOptionPane.showOptionDialog(this, "월드 " + world.name() + "의 초대 코드를 복사하거나, 키를 바꿔 지금까지의 초대를 끊을 수 있습니다.",
                        "친구 초대", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
                if (choice == 1) {
                    world = changeKey(world, "새 키를 만들어 등록하고 이 PC도 새 키로 바꿉니다.\n"
                            + "그 뒤 GitHub repo의 Settings → Deploy keys에서 예전 mcd 키를 지워야 지금까지의 초대 코드가 끊깁니다.\n"
                            + "계속 함께할 친구에게는 새 초대 코드를 보내 주세요.");
                } else if (choice != 0) {
                    return;
                }
            }
            if (world == null) return;
            String code = new Invite(GitHubRepo.parse(world.remote()), DeployKey.decode(world.deployKey())).encode();
            DeployKeyDialog.copyToClipboard(code);
            JTextArea text = new JTextArea(code, 4, 50);
            text.setLineWrap(true);
            text.setEditable(false);
            JOptionPane.showMessageDialog(this, new Object[] {
                    "초대 코드를 클립보드에 복사했습니다. 친구는 받은 코드를 '월드 추가 → 친구의 월드에 참가'에 붙여 넣으면 됩니다.",
                    new JScrollPane(text),
                    "이 코드를 가진 사람은 누구나 이 월드를 읽고 쓸 수 있습니다. 공개된 곳에 올리지 마세요.\n"
                            + "초대를 끊으려면 '친구 초대 → 키 바꾸기'로 새 키를 등록한 뒤 GitHub에서 예전 mcd 키를 지우세요(남은 사람도 새 코드가 필요합니다)."},
                    "친구 초대: " + world.name(), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "초대 코드를 만들지 못했습니다: " + e.getMessage());
        }
    }

    /**
     * 새 deploy key를 만들어 등록을 안내하고, 확인되면 이 월드가 그 키(SSH)로 접속하게 저장한 뒤 노드를 다시 켠다
     * (이 PC가 호스트라면 저장하고 넘긴다). 취소하면 null.
     */
    private World changeKey(World world, String explanation) throws Exception {
        GitHubRepo repo = GitHubRepo.parse(world.remote());
        int answer = JOptionPane.showConfirmDialog(this, explanation + "\n노드를 다시 켭니다(이 PC가 호스트라면 저장하고 넘깁니다). 계속할까요?",
                "deploy key: " + repo, JOptionPane.OK_CANCEL_OPTION);
        if (answer != JOptionPane.OK_OPTION) return null;
        DeployKey key = DeployKey.generate();
        if (!DeployKeyDialog.run(this, repo, key)) return null;
        World switched = new World(repo.sshRemote(), world.dir(), key.encode());
        if (!save(settings.withoutWorld(world.remote()).withWorld(switched))) return null;
        refreshWorlds();
        inControl(this::restartNode);
        return switched;
    }

    private void editAccount() {
        Settings next = AccountDialog.edit(this, settings);
        if (next == null || !save(next)) return;
        setTitle("mcd - " + next.nodeName());
        log("설정을 바꿨습니다. 노드를 다시 시작합니다.");
        inControl(this::restartNode);
    }

    private boolean save(Settings next) {
        try {
            next.save(Settings.defaultFile());
            settings = next;
            return true;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "설정을 저장하지 못했습니다: " + e.getMessage());
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
            String question = "새 버전 " + release.get().version() + "이(가) 있습니다(지금 " + current.get() + "). 지금 업데이트할까요?\n"
                    + "월드를 저장하고 mcd를 껐다가, 바꾼 뒤 다시 켭니다. 호스트라면 접속 중인 사람은 다른 PC로 옮겨 갑니다.";
            int[] answer = new int[1];
            SwingUtilities.invokeAndWait(() ->
                    answer[0] = JOptionPane.showConfirmDialog(this, question, "mcd 업데이트", JOptionPane.YES_NO_OPTION));
            if (answer[0] != JOptionPane.YES_OPTION) return;
            log("새 버전 " + release.get().version() + "을(를) 받는 중입니다.");
            Path newApp = updater.download(release.get());
            updater.launchReplacer(newApp, installDir.get(), Optional.of(installDir.get().resolve("mcd.exe")));
            log("업데이트를 준비했습니다. mcd를 껐다가 다시 켭니다.");
            shutdown();
        } catch (Exception e) {
            log("업데이트하지 못했습니다: " + e.getMessage());
        }
    }

    private void sendCommand() {
        String text = command.getText().trim();
        Node current = node;
        if (text.isEmpty() || current == null) return;
        command.setText("");
        try {
            if (!current.consoleCommand(text)) {
                log("이 PC는 지금 호스트가 아니라서 Paper 명령을 보낼 수 없습니다.");
            }
        } catch (Exception e) {
            log("명령을 보내지 못했습니다: " + e.getMessage());
        }
    }

    private void shutdown() {
        closing = true;
        SwingUtilities.invokeLater(() -> {
            refreshStatus();
            setControlsEnabled(false);
        });
        control.execute(() -> {
            stopNode();
            System.exit(0);
        });
    }

    /** mcd 안내 메시지. 상태 줄에도 보여준다. */
    private void log(String message) {
        SwingUtilities.invokeLater(() -> statusPanel.setMessage(message));
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
