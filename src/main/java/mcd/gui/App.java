package mcd.gui;

import mcd.config.Settings;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.Optional;

/** 배포판의 기본 진입점. 처음이면 설정 마법사를 띄우고, 그다음 메인 창에서 노드를 돌린다. */
public final class App {

    private App() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                Optional<Settings> saved = Settings.load(Settings.defaultFile());
                Settings settings = saved.isPresent() ? saved.get() : SetupDialog.run();
                if (settings == null) {
                    System.exit(0);
                }
                new MainWindow(settings).start();
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "mcd를 시작하지 못했다: " + e.getMessage());
                System.exit(1);
            }
        });
    }
}
