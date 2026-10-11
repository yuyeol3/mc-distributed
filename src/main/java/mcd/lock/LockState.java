package mcd.lock;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** 월드 repo 루트의 {@value #FILE_NAME}에 저장되는 호스트 소유권 상태. */
public record LockState(String owner, long epoch, State state, String address) {

    public static final String FILE_NAME = "mcd-lock.properties";

    public enum State { FREE, HOSTING }

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
        return new LockState(
                p.getProperty("owner", ""),
                Long.parseLong(p.getProperty("epoch", "0")),
                State.valueOf(p.getProperty("state", "FREE")),
                p.getProperty("address", ""));
    }

    public static LockState read(Path repoRoot) throws IOException {
        return parse(Files.readAllBytes(repoRoot.resolve(FILE_NAME)));
    }

    /** Properties.store는 시각 주석을 넣어 내용이 매번 달라지므로 직접 쓴다. 값에 줄바꿈이나 '\'가 없다고 가정한다. */
    public void write(Path repoRoot) throws IOException {
        String content = "owner=" + owner + "\nepoch=" + epoch + "\nstate=" + state + "\naddress=" + address + "\n";
        Files.writeString(repoRoot.resolve(FILE_NAME), content);
    }

    public LockState hostedBy(String node, String hostAddress) {
        return new LockState(node, epoch + 1, State.HOSTING, hostAddress);
    }

    public LockState freed() {
        return new LockState(owner, epoch, State.FREE, "");
    }
}
