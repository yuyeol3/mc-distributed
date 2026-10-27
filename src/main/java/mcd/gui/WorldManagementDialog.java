package mcd.gui;

import mcd.world.*;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** 서버를 강제 종료하지 않고 원격 관리 락을 통해 적용한다. */
final class WorldManagementDialog extends JDialog {
    private final WorldAdmin admin;
    private final DefaultListModel<PluginManager.Plugin> model = new DefaultListModel<>();
    private final JList<PluginManager.Plugin> plugins = new JList<>(model);
    private final JPanel actions = new JPanel(new GridLayout(0, 3, 8, 8));
    private final JLabel status = new JLabel("배포 파일 목록입니다. 활성 여부는 호스트 콘솔의 plugins 명령으로 확인하세요.");
    private final Consumer<String> log;
    private boolean busy;

    WorldManagementDialog(Window owner, String name, WorldAdmin admin, Consumer<String> log, Runnable showActive) {
        super(owner, name + " — 월드·플러그인 관리", ModalityType.APPLICATION_MODAL);
        this.admin = admin;
        this.log = log;
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { if (!busy) dispose(); }
        });
        button("월드 교체", this::replace);
        button("백업 복구", this::restore);
        button("JAR 추가/교체", this::addPlugin);
        button("선택 플러그인 제거", this::removePlugin);
        button("새로고침", this::refresh);
        button("활성 목록(콘솔)", showActive);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(new JLabel("서버가 종료된 동안만 변경할 수 있습니다. 변경 전 상태는 자동으로 백업합니다."), BorderLayout.NORTH);
        content.add(new JScrollPane(plugins), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.SOUTH);
        content.add(bottom, BorderLayout.SOUTH);
        setContentPane(content);
        setSize(820, 360);
        setLocationRelativeTo(owner);
        refresh();
    }

    private void button(String title, Runnable action) {
        JButton button = new JButton(title);
        button.addActionListener(e -> action.run());
        actions.add(button);
    }

    private void refresh() {
        background("배포 목록을 읽는 중…", admin::plugins, list -> {
            model.clear();
            list.forEach(model::addElement);
            status.setText("배포 파일 " + list.size() + "개. 활성 여부는 호스트의 Paper 콘솔에서 확인하세요.");
        });
    }

    private void replace() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("월드 폴더 또는 ZIP 선택");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setFileFilter(new FileNameExtensionFilter("월드 ZIP", "zip"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        background("월드를 검증하는 중…", () -> WorldImport.prepare(chooser.getSelectedFile().toPath()), prepared -> {
            if (!confirm(prepared.summary() + "\n\n이 월드를 모든 참가자에게 적용할까요?")) {
                try { prepared.close(); } catch (Exception e) { error(e); }
                return;
            }
            background("백업 후 월드를 교체하는 중…", () -> {
                try (prepared) { return admin.replace(prepared); }
            }, this::applied);
        });
    }

    private void addPlugin() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Paper/Bukkit 플러그인", "jar"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        background("JAR를 검증하는 중…", () -> {
            Path copy = Files.createTempFile("mcd-plugin-preview-", ".jar");
            try {
                Files.copy(chooser.getSelectedFile().toPath(), copy, StandardCopyOption.REPLACE_EXISTING);
                return PluginManager.inspect(copy);
            } catch (Exception e) { Files.deleteIfExists(copy); throw e; }
        }, plugin -> {
            if (!confirm(plugin.name() + " " + plugin.version() + "\n필수 의존성: " + plugin.required()
                    + "\n\n이 JAR는 모든 호스트 PC에서 실행됩니다. 설치하거나 같은 플러그인을 교체할까요?")) {
                try { Files.deleteIfExists(plugin.file()); } catch (Exception e) { error(e); }
                return;
            }
            background("백업 후 플러그인을 적용하는 중…", () -> {
                try { return admin.addPlugin(plugin.file()); }
                finally { Files.deleteIfExists(plugin.file()); }
            }, this::applied);
        });
    }

    private void removePlugin() {
        PluginManager.Plugin selected = plugins.getSelectedValue();
        if (selected == null || !confirm(selected.name() + "을(를) 모든 호스트에서 제거할까요?\n데이터 폴더는 보존합니다.")) return;
        background("백업 후 플러그인을 제거하는 중…", () -> admin.removePlugin(selected.name()), this::applied);
    }

    private void restore() {
        background("백업 목록을 읽는 중…", admin::backups, backups -> {
            if (backups.isEmpty()) { status.setText("백업이 없습니다."); return; }
            Object selected = JOptionPane.showInputDialog(this, "복구할 백업을 선택하세요. 현재 상태도 먼저 백업합니다.\n월드·플러그인·서버 설정이 함께 복원됩니다.",
                    "백업 복구", JOptionPane.QUESTION_MESSAGE, null, backups.toArray(), backups.getLast());
            if (selected != null && confirm("선택한 백업을 모든 참가자에게 적용할까요?\n" + selected)) {
                background("백업을 복구하는 중…", () -> admin.restore(selected.toString()), this::applied);
            }
        });
    }

    private boolean confirm(String text) {
        return JOptionPane.showConfirmDialog(this, text, "적용 확인", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private void applied(String backup) {
        log.accept("관리 작업 완료. 변경 전 백업: " + backup);
        JOptionPane.showMessageDialog(this, "적용했습니다. 다음 호스트 시작 시 반영됩니다.\n변경 전 백업: " + backup);
        refresh();
    }

    private void error(Exception e) {
        status.setText("작업 실패");
        log.accept("관리 작업 실패: " + e.getMessage());
        JOptionPane.showMessageDialog(this, e.getMessage(), "관리 작업 실패", JOptionPane.ERROR_MESSAGE);
    }

    private <T> void background(String title, Callable<T> task, Consumer<T> done) {
        busy = true;
        for (Component component : actions.getComponents()) component.setEnabled(false);
        status.setText(title);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                busy = false;
                for (Component component : actions.getComponents()) component.setEnabled(true);
                try { done.accept(get()); }
                catch (Exception e) { error(e.getCause() instanceof Exception cause ? cause : e); }
            }
        }.execute();
    }
}
