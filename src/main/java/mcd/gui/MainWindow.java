package mcd.gui;

import mcd.config.Settings;
import mcd.node.Node;
import mcd.node.Timing;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import javax.swing.BorderFactory;
import javax.swing.JButton;
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
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** 노드를 띄우고 상태, 접속 안내, 로그를 보여준다. 창을 닫으면 저장하고 락을 반납한 뒤 끝난다. */
final class MainWindow extends JFrame {

    private static final int LISTEN_PORT = 25565;
    private static final int PAPER_PORT = 25566;
    private static final int MAX_LOG_CHARS = 200_000;
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*m");

    private final Settings settings;
    private final JLabel status = new JLabel("준비하는 중...");
    private final JTextArea logArea = new JTextArea(24, 90);
    private final JTextField command = new JTextField();
    private volatile Node node;

    MainWindow(Settings settings) {
        super("mcd - " + settings.nodeName());
        this.settings = settings;

        JLabel howTo = new JLabel("마인크래프트 → 멀티플레이 → 서버 추가 → 주소 localhost:" + LISTEN_PORT);
        howTo.setFont(howTo.getFont().deriveFont(Font.BOLD, 16f));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        top.add(howTo, BorderLayout.NORTH);
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
        pack();
        setLocationRelativeTo(null);
    }

    void start() {
        setVisible(true);
        Thread.ofPlatform().name("node-start").start(() -> {
            try {
                Node.Config config = new Node.Config(settings.remote(), settings.worldDir(), settings.nodeName(),
                        LISTEN_PORT, PAPER_PORT, settings.address(), Path.of(System.getProperty("user.home"), ".mcd", "cache"),
                        new UsernamePasswordCredentialsProvider("x-access-token", settings.token()), Timing.fromTtl(Timing.DEFAULT_TTL),
                        LISTEN_PORT);
                Node opened = Node.open(config, true, this::log, new PrintStream(new LineSink(), true, StandardCharsets.UTF_8));
                opened.start();
                node = opened;
            } catch (Exception e) {
                log("시작하지 못했다: " + e.getMessage());
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "시작하지 못했다: " + e.getMessage()));
            }
        });
    }

    private void sendCommand() {
        String text = command.getText().trim();
        if (text.isEmpty() || node == null) return;
        command.setText("");
        try {
            if (!node.consoleCommand(text)) {
                log("이 PC는 지금 호스트가 아니라서 Paper 명령을 보낼 수 없다.");
            }
        } catch (Exception e) {
            log("명령을 보내지 못했다: " + e.getMessage());
        }
    }

    private void shutdown() {
        status.setText("저장하고 끝내는 중...");
        Thread.ofPlatform().name("node-stop").start(() -> {
            try {
                if (node != null) node.close();
            } catch (Exception e) {
                log("끝내는 중 오류: " + e.getMessage());
            }
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
