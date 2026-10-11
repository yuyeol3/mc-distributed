package mcd.gui;

import mcd.config.Settings;
import mcd.github.GitHubClient;
import mcd.server.PaperDistribution;
import mcd.world.NetworkConfig;
import mcd.world.WorldRepository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * 첫 실행 마법사. 이름, GitHub 토큰, 새 월드 만들기 또는 친구의 월드 참가, EULA 동의를 받는다.
 * 새 월드면 GitHub에 private repo를 만들고 초기 파일을 올린다.
 */
final class SetupDialog extends JDialog {

    private final JTextField name = new JTextField(System.getProperty("user.name"), 24);
    private final JPasswordField token = new JPasswordField(24);
    private final JRadioButton createNew = new JRadioButton("새 월드 만들기", true);
    private final JRadioButton joinExisting = new JRadioButton("친구의 월드에 참가");
    private final JTextField newRepoName = new JTextField("mcd-world", 24);
    private final JTextField existingRepo = new JTextField("https://github.com/<친구 계정>/<repo>.git", 24);
    private final JTextField tailnetKey = new JTextField(24);
    private final JTextField tailnetControl = new JTextField(24);
    private final JTextField address = new JTextField("localhost:25566", 24);
    private final JCheckBox eula = new JCheckBox("Minecraft EULA(https://aka.ms/MinecraftEULA)에 동의한다");
    private final JButton start = new JButton("시작");
    private Settings result;

    private SetupDialog() {
        super((java.awt.Frame) null, "mcd 처음 설정", true);
        ButtonGroup mode = new ButtonGroup();
        mode.add(createNew);
        mode.add(joinExisting);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        int row = 0;
        row = addRow(form, row, "내 이름", name);
        row = addRow(form, row, "GitHub 토큰", token);
        row = addNote(form, row, "repo 권한이 있는 토큰. 월드를 보관할 GitHub 계정은 메인 계정과 따로 만들어 쓰기를 권한다.");
        row = addRow(form, row, createNew, newRepoName);
        row = addRow(form, row, joinExisting, existingRepo);
        row = addNote(form, row, "친구의 월드에 참가하려면 친구가 그 repo에 나를 collaborator로 초대해야 한다.");
        row = addRow(form, row, "tailnet 인증 키", tailnetKey);
        row = addNote(form, row, "새 월드일 때만. 인터넷 너머의 친구가 접속하려면 필요하다. Tailscale 관리 화면에서 재사용 가능한 키를 한 번 발급해 넣으면 친구들은 아무것도 설치하지 않는다. 비우면 같은 PC/네트워크에서만 접속된다.");
        row = addRow(form, row, "조정 서버 주소", tailnetControl);
        row = addNote(form, row, "비우면 Tailscale을 쓴다. Headscale을 직접 운영하면 그 주소를 넣는다.");
        row = addRow(form, row, "내 서버 주소", address);
        row = addNote(form, row, "내 PC가 호스트일 때 다른 사람이 접속할 주소. 네트워크 방식이 정해지기 전까지는 기본값을 둔다.");
        GridBagConstraints c = constraints(0, row);
        c.gridwidth = 2;
        form.add(eula, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("취소");
        buttons.add(cancel);
        buttons.add(start);
        cancel.addActionListener(e -> dispose());
        start.addActionListener(e -> submit());

        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(null);
    }

    /** 설정을 마치면 저장된 설정을, 취소하면 null을 돌려준다. */
    static Settings run() {
        SetupDialog dialog = new SetupDialog();
        dialog.setVisible(true);
        return dialog.result;
    }

    private void submit() {
        if (!eula.isSelected()) {
            JOptionPane.showMessageDialog(this, "서버를 띄우려면 Minecraft EULA에 동의해야 한다.");
            return;
        }
        String tokenText = new String(token.getPassword()).trim();
        start.setEnabled(false);
        new SwingWorker<Settings, Void>() {
            private String inviteHint = "";

            @Override
            protected Settings doInBackground() throws Exception {
                GitHubClient github = new GitHubClient(GitHubClient.API, tokenText);
                String login = github.login();
                String remote;
                if (createNew.isSelected()) {
                    String repoName = newRepoName.getText().trim();
                    remote = github.createPrivateRepo(repoName);
                    String key = tailnetKey.getText().trim();
                    Optional<NetworkConfig> network = key.isEmpty()
                            ? Optional.empty()
                            : Optional.of(new NetworkConfig(tailnetControl.getText().trim(), key));
                    WorldRepository.create(worldDir(remote), remote, true, network, PaperDistribution.DEFAULT_BUILD,
                            new UsernamePasswordCredentialsProvider("x-access-token", tokenText));
                    inviteHint = "\n\n친구를 초대하려면 https://github.com/" + login + "/" + repoName
                            + "/settings/access 에서 collaborator로 추가한다.";
                } else {
                    remote = existingRepo.getText().trim();
                }
                Settings settings = new Settings(name.getText().trim(), remote, worldDir(remote), tokenText, address.getText().trim());
                settings.save(Settings.defaultFile());
                return settings;
            }

            @Override
            protected void done() {
                try {
                    result = get();
                    JOptionPane.showMessageDialog(SetupDialog.this, "설정을 마쳤다." + inviteHint);
                    dispose();
                } catch (InterruptedException | ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    JOptionPane.showMessageDialog(SetupDialog.this, "설정하지 못했다: " + cause.getMessage());
                    start.setEnabled(true);
                }
            }
        }.execute();
    }

    /** 월드 작업 트리는 ~/.mcd/worlds/<repo 이름>에 둔다. */
    static Path worldDir(String remote) {
        String last = remote.replaceAll("/+$", "");
        last = last.substring(last.lastIndexOf('/') + 1).replaceAll("\\.git$", "");
        return Path.of(System.getProperty("user.home"), ".mcd", "worlds", last);
    }

    private static int addRow(JPanel form, int row, Object label, java.awt.Component field) {
        form.add(label instanceof String text ? new JLabel(text) : (java.awt.Component) label, constraints(0, row));
        GridBagConstraints c = constraints(1, row);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
        return row + 1;
    }

    private static int addNote(JPanel form, int row, String text) {
        GridBagConstraints c = constraints(1, row);
        form.add(new JLabel("<html><div style='width:320px;color:gray'>" + text + "</div></html>"), c);
        return row + 1;
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 4);
        return c;
    }
}
