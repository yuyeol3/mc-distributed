package mcd.gui;

import mcd.config.Settings;
import mcd.config.Settings.World;
import mcd.server.PaperDistribution;
import mcd.world.DeployKey;
import mcd.world.GitHubRepo;
import mcd.world.Invite;
import mcd.world.NetworkConfig;
import mcd.world.WorldRepository;

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
 * 월드 추가: 새 월드를 만들거나 친구의 월드에 참가한다. GitHub 토큰은 쓰지 않는다. 새 월드는 사용자가 웹에서 만든 빈 repo에
 * deploy key를 등록해 올리고, 참가는 초대 코드(repo + deploy key)로 한다. 예전처럼 repo 주소와 토큰으로 참가하는 것도 받는다.
 */
final class AddWorldDialog extends JDialog {

    private final JRadioButton createNew = new JRadioButton("새 월드 만들기", true);
    private final JRadioButton joinExisting = new JRadioButton("친구의 월드에 참가");
    private final JTextField newRepo = new JTextField("https://github.com/<내 계정>/<repo>", 24);
    private final JTextField invite = new JTextField(24);
    private final JTextField tailnetKey = new JTextField(24);
    private final JTextField tailnetControl = new JTextField(24);
    private final JButton ok = new JButton("추가");
    private final Settings settings;
    private World result;

    private AddWorldDialog(Window owner, Settings settings) {
        super(owner, "월드 추가", ModalityType.APPLICATION_MODAL);
        this.settings = settings;
        ButtonGroup mode = new ButtonGroup();
        mode.add(createNew);
        mode.add(joinExisting);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        int row = 0;
        row = Forms.addRow(form, row, createNew, newRepo);
        row = Forms.addNote(form, row, "먼저 github.com/new에서 빈 private repo(README 없이)를 만들고 그 주소를 넣어 주세요. "
                + "추가를 누르면 deploy key 등록을 안내합니다. 마인크래프트 " + PaperDistribution.DEFAULT_BUILD.version() + " 월드로 만들어집니다.");
        row = Forms.addRow(form, row, joinExisting, invite);
        row = Forms.addNote(form, row, "월드 주인에게 받은 초대 코드(mcd1.로 시작)를 붙여 넣어 주세요. GitHub 계정이 필요 없습니다.");
        row = Forms.addRow(form, row, "tailnet 인증 키", tailnetKey);
        row = Forms.addNote(form, row, "새 월드일 때만 씁니다. 인터넷 너머의 친구가 접속하려면 필요합니다. Tailscale 관리 화면에서 재사용 가능한 키를 한 번 발급해 넣으면 친구들은 아무것도 설치하지 않아도 됩니다. 비우면 같은 PC/네트워크에서만 접속됩니다.");
        row = Forms.addRow(form, row, "조정 서버 주소", tailnetControl);
        Forms.addNote(form, row, "비우면 Tailscale을 씁니다. Headscale을 직접 운영한다면 그 주소를 넣어 주세요.");

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
        try {
            if (joinExisting.isSelected()) {
                join(invite.getText().trim());
            } else {
                create(GitHubRepo.parse(newRepo.getText()));
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "월드를 추가하지 못했습니다: " + e.getMessage());
        }
    }

    /** 키 등록을 안내한 뒤(이 창 위에 모달), 확인되면 빈 repo에 새 월드를 올린다. */
    private void create(GitHubRepo repo) throws Exception {
        String remote = repo.sshRemote();
        checkFolderFree(remote);
        DeployKey key = DeployKey.generate();
        if (!DeployKeyDialog.run(this, repo, key)) return;
        String tailnet = tailnetKey.getText().trim();
        Optional<NetworkConfig> network = tailnet.isEmpty()
                ? Optional.empty()
                : Optional.of(new NetworkConfig(tailnetControl.getText().trim(), tailnet));
        inBackground(() -> {
            WorldRepository.create(worldDir(remote), remote, true, network, PaperDistribution.DEFAULT_BUILD, key.transport());
            return new World(remote, worldDir(remote), key.encode());
        }, "\n\n친구를 초대하려면 메인 창의 '친구 초대'를 누르세요.");
    }

    private void join(String text) throws Exception {
        if (!Invite.looksLikeInvite(text)) {
            // 예전 방식: repo collaborator로 초대받고 설정의 GitHub 토큰으로 접속한다.
            checkFolderFree(text);
            result = new World(text, worldDir(text));
            dispose();
            return;
        }
        Invite parsed = Invite.parse(text);
        String remote = parsed.repo().sshRemote();
        checkFolderFree(remote);
        inBackground(() -> {
            WorldRepository.checkWriteAccess(remote, parsed.key().transport());
            return new World(remote, worldDir(remote), parsed.key().encode());
        }, "");
    }

    private interface Task {
        World run() throws Exception;
    }

    private void inBackground(Task task, String doneNote) {
        ok.setEnabled(false);
        new SwingWorker<World, Void>() {
            @Override
            protected World doInBackground() throws Exception {
                return task.run();
            }

            @Override
            protected void done() {
                try {
                    result = get();
                    JOptionPane.showMessageDialog(AddWorldDialog.this, "월드 " + result.name() + "을(를) 추가했습니다." + doneNote);
                    dispose();
                } catch (InterruptedException | ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    JOptionPane.showMessageDialog(AddWorldDialog.this, "월드를 추가하지 못했습니다: " + cause.getMessage());
                    ok.setEnabled(true);
                }
            }
        }.execute();
    }

    /** 다른 repo가 이미 같은 폴더를 쓰고 있으면 거부한다. */
    private void checkFolderFree(String remote) {
        Path dir = worldDir(remote);
        for (World w : settings.worlds()) {
            if (w.dir().equals(dir) && !w.remote().equals(remote)) {
                throw new IllegalStateException("이미 같은 이름의 월드(" + w.remote() + ")가 " + dir + "를 쓰고 있습니다.");
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
