package mcd.world;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 같은 로컬 사본에서 두 mcd가 Paper와 Git 작업 트리를 공유하지 못하게 한다. */
public final class WorldProcessLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private WorldProcessLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    public static WorldProcessLock acquire(Path directory) throws IOException {
        FileChannel channel = FileChannel.open(directory.resolve(".git/mcd-running.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("이 월드는 다른 mcd에서 열려 있습니다");
            return new WorldProcessLock(channel, lock);
        } catch (IOException | OverlappingFileLockException e) {
            channel.close();
            throw new IOException("이 월드는 다른 mcd에서 열려 있습니다", e);
        }
    }

    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
