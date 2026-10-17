package mcd.gui;

import mcd.config.Settings;
import mcd.config.Settings.World;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.Optional;

/** 배포판의 기본 진입점. 처음이면 설정과 첫 월드를 받고, 그다음 메인 창에서 노드를 돌린다. */
public final class App {

    private App() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                Theme.apply();
                Optional<Settings> saved = Settings.load(Settings.defaultFile());
                Settings settings = saved.isPresent() ? saved.get() : firstRun();
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

    /** 이름, 토큰, EULA를 받고 첫 월드를 추가한다. 월드 추가를 취소해도 설정은 남기고 메인 창에서 다시 추가할 수 있다. */
    private static Settings firstRun() throws Exception {
        Settings settings = AccountDialog.firstRun();
        if (settings == null) return null;
        World world = AddWorldDialog.run(null, settings);
        if (world != null) {
            settings = settings.withWorld(world);
        }
        settings.save(Settings.defaultFile());
        return settings;
    }
}
