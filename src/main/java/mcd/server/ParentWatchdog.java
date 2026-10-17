package mcd.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Paper JVM에 붙는 Java 에이전트. 부모(mcd)가 죽으면 Paper를 정상 종료시킨다.
 * Paper는 stdin이 닫혀도 계속 돌기 때문에(직접 확인), 이것이 없으면 mcd가 크래시했을 때 락 갱신 없이 서버만 남는다.
 * 이 클래스는 Paper JVM에서 혼자 로드되므로 다른 클래스에 의존하면 안 된다.
 */
public final class ParentWatchdog {

    private static final String CLASS_FILE = "mcd/server/ParentWatchdog.class";

    private ParentWatchdog() {}

    public static void premain(String args) {
        ProcessHandle.current().parent().ifPresent(parent -> parent.onExit().thenRun(() -> {
            System.out.println("[mcd] mcd 프로세스가 끝나서 서버를 내립니다.");
            System.exit(0);
        }));
    }

    /** 이 클래스 하나만 담은 에이전트 jar를 임시 파일로 만든다. */
    static Path createAgentJar() throws IOException {
        Path jar = Files.createTempFile("mcd-watchdog", ".jar");
        jar.toFile().deleteOnExit();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), ParentWatchdog.class.getName());
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(out, manifest);
             InputStream classBytes = ParentWatchdog.class.getClassLoader().getResourceAsStream(CLASS_FILE)) {
            if (classBytes == null) {
                throw new IOException(CLASS_FILE + "를 찾지 못했습니다");
            }
            jarOut.putNextEntry(new JarEntry(CLASS_FILE));
            classBytes.transferTo(jarOut);
            jarOut.closeEntry();
        }
        return jar;
    }
}
