package mcd.gui;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import javax.swing.UIManager;
import java.awt.Color;
import java.nio.charset.StandardCharsets;

/** FlatLaf 테마. Windows의 앱 모드(라이트/다크)를 따른다. */
final class Theme {

    static final Color GREEN = new Color(0x2EA043);
    static final Color ORANGE = new Color(0xD29922);
    static final Color GRAY = new Color(0x8B949E);
    static final Color RED = new Color(0xE5534B);
    /** 로그는 테마와 상관없이 터미널처럼 어둡게 보여준다. */
    static final Color LOG_BACKGROUND = new Color(0x1E1F22);
    static final Color LOG_FOREGROUND = new Color(0xD4D4D4);

    private Theme() {}

    static void apply() {
        if (windowsDarkMode()) {
            FlatMacDarkLaf.setup();
        } else {
            FlatMacLightLaf.setup();
        }
        UIManager.put("Button.arc", 10);
        UIManager.put("Component.arc", 10);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.width", 10);
    }

    /** HKCU\...\Personalize의 AppsUseLightTheme가 0이면 다크 모드. 알 수 없으면 라이트. */
    private static boolean windowsDarkMode() {
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return out.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\b.*");
        } catch (Exception e) {
            return false;
        }
    }
}
