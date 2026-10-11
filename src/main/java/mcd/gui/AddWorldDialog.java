package mcd.gui;

import mcd.config.Settings;
import mcd.config.Settings.World;
import mcd.github.GitHubClient;
import mcd.server.PaperDistribution;
import mcd.world.NetworkConfig;
import mcd.world.WorldRepository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * 월드 추가: 새 월드를 만들거나 친구의 월드에 참가한다. 새 월드면 GitHub에 private repo를 만들고 초기 파일을 올린다.
 */
final class AddWorldDialog extends JDialog {

    private final JRadioButton createNew = new JRadioButton("새 월드 만들기", true);
    private final JRadioButton joinExisting = new JRadioButton("친구의 월드에 참가");
    private final JTextField newRepoName = new JTextField("mcd-world", 24);
    private final JTextField existingRepo = new JTextField("https://github.com/<친구 계정>/<repo>.git", 24);
    private final JTextField tailnetKey = new JTextField(24);
    private final JTextField tailnetControl = new JTextField(24);
    private final JButton ok = new JButton("추가");
    private final Settings settings;
    private final String token;
    private World result;

    private AddWorldDialog(Window owner, Settings settings) {
        super(owner, "월드 추가", ModalityType.APPLICATION_MODAL);
        this.settings = settings;
        this.token = settings.token();
        ButtonGroup mode = new ButtonGroup();
        mode.add(createNew);
        mode.add(joinExisting);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        int row = 0;
        row = Forms.addRow(form, row, createNew, newRepoName);
        row = Forms.addNote(form, row, "GitHub에 이 이름의 private repo를 만든다. 마인크래프트 " + PaperDistribution.DEFAULT_BUILD.version() + " 월드로 만들어진다.");
        row = Forms.addRow(form, row, joinExisting, existingRepo);
        row = Forms.addNote(form, row, "친구의 월드에 참가하려면 친구가 그 repo에 나를 collaborator로 초대해야 한다.");
        row = Forms.addRow(form, row, "tailnet 인증 키", tailnetKey);
        row = Forms.addNote(form, row, "새 월드일 때만. 인터넷 너머의 친구가 접속하려면 필요하다. Tailscale 관리 화면에서 재사용 가능한 키를 한 번 발급해 넣으면 친구들은 아무것도 설치하지 않는다. 비우면 같은 PC/네트워크에서만 접속된다.");
        row = Forms.addRow(form, row, "조정 서버 주소", tailnetControl);
        Forms.addNote(form, row, "비우면 Tailscale을 쓴다. Headscale을 직접 운영하면 그 주소를 넣는다.");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("취소");
        buttons.add(cancel);
        buttons.add(ok);
        cancel.addActionListener(e -> dispose());
        ok.addActionListener(e -> submit());

        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    /** 추가한 월드. 취소하면 null. */
    static World run(Window owner, Settings settings) {
        AddWorldDialog dialog = new AddWorldDialog(owner, settings);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void submit() {
        ok.setEnabled(false);
        new SwingWorker<World, Void>() {
            private String inviteHint = "";

            @Override
            protected World doInBackground() throws Exception {
                if (joinExisting.isSelected()) {
                    String remote = existingRepo.getText().trim();
                    checkFolderFree(remote);
                    return new World(remote, worldDir(remote));
                }
                GitHubClient github = new GitHubClient(GitHubClient.API, token);
                String login = github.login();
                String repoName = newRepoName.getText().trim();
                checkFolderFree("https://github.com/" + login + "/" + repoName + ".git");
                String remote = github.createPrivateRepo(repoName);
                String key = tailnetKey.getText().trim();
                Optional<NetworkConfig> network = key.isEmpty()
                        ? Optional.empty()
                        : Optional.of(new NetworkConfig(tailnetControl.getText().trim(), key));
                WorldRepository.create(worldDir(remote), remote, true, network, PaperDistribution.DEFAULT_BUILD,
                        new UsernamePasswordCredentialsProvider("x-access-token", token));
                inviteHint = "\n\n친구를 초대하려면 https://github.com/" + login + "/" + repoName
                        + "/settings/access 에서 collaborator로 추가한다.";
                return new World(remote, worldDir(remote));
            }

            @Override
            protected void done() {
                try {
                    result = get();
                    JOptionPane.showMessageDialog(AddWorldDialog.this, "월드 " + result.name() + "을(를) 추가했다." + inviteHint);
                    dispose();
                } catch (InterruptedException | ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    JOptionPane.showMessageDialog(AddWorldDialog.this, "월드를 추가하지 못했다: " + cause.getMessage());
                    ok.setEnabled(true);
                }
            }
        }.execute();
    }

    /** 다른 계정의 같은 이름 repo가 이미 목록에 있으면 같은 폴더를 쓰게 되므로 거부한다. */
    private void checkFolderFree(String remote) {
        Path dir = worldDir(remote);
        for (World w : settings.worlds()) {
            if (w.dir().equals(dir) && !w.remote().equals(remote)) {
                throw new IllegalStateException("이미 같은 이름의 월드(" + w.remote() + ")가 " + dir + "를 쓰고 있다.");
            }
        }
    }

    /** 월드 작업 트리는 ~/.mcd/worlds/<repo 이름>에 둔다. */
    static Path worldDir(String remote) {
        String last = remote.replaceAll("/+$", "");
        last = last.substring(last.lastIndexOf('/') + 1).replaceAll("\\.git$", "");
        return Path.of(System.getProperty("user.home"), ".mcd", "worlds", last);
    }
}
