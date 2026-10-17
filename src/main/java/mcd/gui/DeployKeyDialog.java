package mcd.gui;

import mcd.world.DeployKey;
import mcd.world.GitHubRepo;
import mcd.world.WorldRepository;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.util.concurrent.ExecutionException;

/**
 * 월드 repo에 deploy key를 등록하도록 안내하고, 등록되면 실제로 접속해 쓰기 권한까지 확인한다.
 * 토큰으로 자동 등록하지 않는다: 토큰으로 만든 deploy key는 그 토큰을 지우면 함께 지워진다(실험 E9).
 */
final class DeployKeyDialog extends JDialog {

    private final GitHubRepo repo;
    private final DeployKey key;
    private final JButton done = new JButton("등록 완료");
    private boolean confirmed;

    private DeployKeyDialog(Window owner, GitHubRepo repo, DeployKey key) {
        super(owner, "deploy key 등록: " + repo, ModalityType.APPLICATION_MODAL);
        this.repo = repo;
        this.key = key;

        JLabel steps = new JLabel("<html><div style='width:460px'>"
                + "이 월드의 GitHub repo(<b>" + repo + "</b>)에 아래 키를 한 번 등록해 주세요. 이 키가 있으면 GitHub 계정 없이 월드를 읽고 쓸 수 있습니다."
                + "<ol><li>'GitHub에서 열기'로 Deploy key 추가 화면을 엽니다(repo 주인 계정으로 로그인).</li>"
                + "<li>Title에 <b>mcd</b>, Key에 아래 공개키를 붙여 넣습니다('공개키 복사').</li>"
                + "<li><b>Allow write access</b>를 체크하고 Add key를 누릅니다.</li>"
                + "<li>여기서 '등록 완료'를 누르면 mcd가 접속해 확인합니다.</li></ol></div></html>");
        JTextArea publicKey = new JTextArea(key.publicKeyLine(), 3, 50);
        publicKey.setLineWrap(true);
        publicKey.setEditable(false);

        JButton copy = new JButton("공개키 복사");
        copy.addActionListener(e -> copyToClipboard(key.publicKeyLine()));
        JButton open = new JButton("GitHub에서 열기");
        open.addActionListener(e -> openInBrowser(repo.newDeployKeyPage()));
        JButton cancel = new JButton("취소");
        cancel.addActionListener(e -> dispose());
        done.addActionListener(e -> verify());

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        center.add(steps, BorderLayout.NORTH);
        center.add(new JScrollPane(publicKey), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(copy);
        buttons.add(open);
        buttons.add(cancel);
        buttons.add(done);
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    /** 등록과 확인이 끝나면 true, 취소하면 false. */
    static boolean run(Window owner, GitHubRepo repo, DeployKey key) {
        DeployKeyDialog dialog = new DeployKeyDialog(owner, repo, key);
        dialog.setVisible(true);
        return dialog.confirmed;
    }

    private void verify() {
        done.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                WorldRepository.checkWriteAccess(repo.sshRemote(), key.transport());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    confirmed = true;
                    dispose();
                } catch (InterruptedException | ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    JOptionPane.showMessageDialog(DeployKeyDialog.this, "이 키로 쓰지 못했습니다: " + cause.getMessage()
                            + "\n\n키 전체를 붙여 넣었는지, Allow write access를 체크했는지 확인해 주세요.");
                    done.setEnabled(true);
                }
            }
        }.execute();
    }

    static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void openInBrowser(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            copyToClipboard(url);
            JOptionPane.showMessageDialog(this, "브라우저를 열지 못해 주소를 복사했습니다: " + url);
        }
    }
}
