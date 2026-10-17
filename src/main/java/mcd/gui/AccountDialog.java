package mcd.gui;

import mcd.config.Settings;
import mcd.github.GitHubClient;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 모든 월드에 공통인 설정: 내 이름, GitHub 토큰(선택), 내 서버 주소. 첫 실행이면 EULA 동의도 받는다.
 * 토큰을 넣으면 GitHub에 한 번 확인한 뒤에 받아들인다.
 */
final class AccountDialog extends JDialog {

    private final JTextField name = new JTextField(24);
    private final JPasswordField token = new JPasswordField(24);
    private final JTextField address = new JTextField(24);
    private final JCheckBox eula = new JCheckBox("Minecraft EULA(https://aka.ms/MinecraftEULA)에 동의한다");
    private final JButton ok = new JButton("저장");
    private final Settings base;
    private final boolean firstRun;
    private Settings result;

    private AccountDialog(Window owner, Settings base, boolean firstRun) {
        super(owner, firstRun ? "mcd 처음 설정" : "mcd 설정", ModalityType.APPLICATION_MODAL);
        this.base = base;
        this.firstRun = firstRun;
        name.setText(base.nodeName());
        token.setText(base.token());
        address.setText(base.address());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        int row = 0;
        row = Forms.addRow(form, row, "내 이름", name);
        row = Forms.addNote(form, row, "호스트가 누구인지 다른 사람에게 이 이름으로 보인다.");
        row = Forms.addRow(form, row, "GitHub 토큰", token);
        row = Forms.addNote(form, row, "비워 둬도 된다. 새 월드와 초대 코드는 토큰 없이 deploy key를 쓴다. "
                + "예전 방식(repo collaborator로 초대받은 월드)에만 repo 권한이 있는 토큰이 필요하다.");
        row = Forms.addRow(form, row, "내 서버 주소", address);
        row = Forms.addNote(form, row, "tailnet 없이 같은 네트워크에서 쓸 때 다른 PC가 내 서버에 접속할 주소. tailnet을 쓰면 무시된다.");
        if (firstRun) {
            Forms.addWide(form, row, eula);
        }

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

    /** 첫 실행: 월드 없는 설정을 돌려준다. 취소하면 null. */
    static Settings firstRun() {
        Settings empty = new Settings(System.getProperty("user.name"), "", "localhost:25566", List.of(), "");
        return show(null, empty, true);
    }

    /** 설정을 고친 결과. 취소하면 null. */
    static Settings edit(Window owner, Settings settings) {
        return show(owner, settings, false);
    }

    private static Settings show(Window owner, Settings settings, boolean firstRun) {
        AccountDialog dialog = new AccountDialog(owner, settings, firstRun);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void submit() {
        if (firstRun && !eula.isSelected()) {
            JOptionPane.showMessageDialog(this, "서버를 띄우려면 Minecraft EULA에 동의해야 한다.");
            return;
        }
        String nameText = name.getText().trim();
        String tokenText = new String(token.getPassword()).trim();
        String addressText = address.getText().trim();
        if (nameText.isEmpty() || addressText.isEmpty()) {
            JOptionPane.showMessageDialog(this, "이름과 서버 주소를 비울 수 없다.");
            return;
        }
        ok.setEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return tokenText.isEmpty() ? "" : new GitHubClient(GitHubClient.API, tokenText).login();
            }

            @Override
            protected void done() {
                try {
                    get();
                    result = base.withAccount(nameText, tokenText, addressText);
                    dispose();
                } catch (InterruptedException | ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    JOptionPane.showMessageDialog(AccountDialog.this, "GitHub 토큰을 확인하지 못했다: " + cause.getMessage());
                    ok.setEnabled(true);
                }
            }
        }.execute();
    }
}
