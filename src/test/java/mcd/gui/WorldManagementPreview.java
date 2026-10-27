package mcd.gui;

import mcd.world.WorldAdmin;
import mcd.world.WorldRepository;
import mcd.server.PaperBuild;
import org.eclipse.jgit.api.Git;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** 실제 사용자 설정/월드에 접근하지 않는 수동 GUI 검증 진입점. */
public final class WorldManagementPreview {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mcd-gui-preview-");
        Path bare = root.resolve("remote.git");
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare.toFile()).call().close();
        String remote = bare.toUri().toString();
        WorldRepository.create(root.resolve("seed"), remote, false, Optional.empty(), PaperBuild.parse("26.2-133"), null);
        WorldAdmin admin = new WorldAdmin(remote, "UI 확인", null);
        SwingUtilities.invokeLater(() -> {
            Theme.apply();
            WorldManagementDialog dialog = new WorldManagementDialog(null, "관리 기능 확인", admin, System.out::println,
                    () -> System.out.println("실제 활성 목록은 호스트에서 확인합니다."));
            if (args.length == 1) {
                dialog.addNotify();
                dialog.validate();
                javax.swing.Timer timer = new javax.swing.Timer(100, null);
                timer.addActionListener(e -> {
                    if (!ready(dialog.getContentPane())) return;
                    timer.stop();
                    try {
                        java.awt.Container content = dialog.getContentPane();
                        var image = new java.awt.image.BufferedImage(content.getWidth(), content.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var graphics = image.createGraphics();
                        content.printAll(graphics); graphics.dispose();
                        Path output = Path.of(args[0]);
                        Files.createDirectories(output.toAbsolutePath().getParent());
                        javax.imageio.ImageIO.write(image, "png", output.toFile());
                    } catch (Exception failure) { failure.printStackTrace(); System.exit(1); }
                    dialog.dispose(); System.exit(0);
                });
                timer.start();
            } else {
                dialog.setVisible(true);
                System.exit(0);
            }
        });
    }

    private static boolean ready(java.awt.Container parent) {
        for (java.awt.Component component : parent.getComponents()) {
            if (component instanceof javax.swing.JButton button && !button.isEnabled()) return false;
            if (component instanceof java.awt.Container child && !ready(child)) return false;
        }
        return true;
    }
}
