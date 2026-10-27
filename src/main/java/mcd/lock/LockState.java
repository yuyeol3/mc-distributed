package mcd.lock;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 월드 repo 루트의 {@value #FILE_NAME}에 저장되는 호스트 소유권 상태. */
public record LockState(String owner, long epoch, State state, String address, String session, String healthAddress) {

    public static final String FILE_NAME = "mcd-lock.properties";

    public enum State { FREE, HOSTING, GUARDED, MAINTENANCE }

    public LockState(String owner, long epoch, State state, String address) {
        this(owner, epoch, state, address, "", "");
    }

    public boolean hosting() {
        return state == State.HOSTING || state == State.GUARDED;
    }

    public static LockState initial() {
        return new LockState("", 0, State.FREE, "");
    }

    public static LockState parse(byte[] content) {
        Properties p = new Properties();
        try {
            p.load(new StringReader(new String(content, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        if (Integer.parseInt(p.getProperty("protocol", "1")) > 2) {
            throw new IllegalStateException("이 월드는 더 새 mcd 락 형식을 사용합니다. mcd를 업데이트하세요.");
        }
        return new LockState(
                p.getProperty("owner", ""),
                Long.parseLong(p.getProperty("epoch", "0")),
                State.valueOf(p.getProperty("state", "FREE")),
                p.getProperty("address", ""),
                p.getProperty("session", ""),
                p.getProperty("healthAddress", ""));
    }

    public static LockState read(Path repoRoot) throws IOException {
        return parse(Files.readAllBytes(repoRoot.resolve(FILE_NAME)));
    }

    /** Properties.store는 시각 주석을 넣어 내용이 매번 달라지므로 직접 쓴다. 값에 줄바꿈이나 '\'가 없다고 가정한다. */
    public void write(Path repoRoot) throws IOException {
        String content = "protocol=2\nowner=" + owner + "\nepoch=" + epoch + "\nstate=" + state + "\naddress=" + address
                + "\nsession=" + session + "\nhealthAddress=" + healthAddress + "\n";
        Files.writeString(repoRoot.resolve(FILE_NAME), content);
    }

    public LockState hostedBy(String node, String hostAddress) {
        return new LockState(node, epoch + 1, State.HOSTING, hostAddress);
    }

    public LockState freed() {
        return new LockState(owner, epoch, State.FREE, "", session, "");
    }
}
