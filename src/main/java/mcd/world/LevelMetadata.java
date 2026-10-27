package mcd.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** level.dat의 버전 미리보기용 NBT reader. 배열은 할당하지 않고 건너뛴다. */
final class LevelMetadata {
    record Info(String version, boolean singlePlayer) {}
    private int budget = 32 * 1024 * 1024;

    static Info read(Path file) throws IOException {
        try (var raw = new GZIPInputStream(Files.newInputStream(file))) {
            LevelMetadata reader = new LevelMetadata();
            try (DataInputStream in = new DataInputStream(new FilterInputStream(raw) {
                @Override public int read() throws IOException {
                    if (--reader.budget < 0) throw new IOException("level.dat이 너무 큽니다");
                    return super.read();
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = super.read(bytes, offset, Math.min(length, Math.max(1, reader.budget)));
                    if (count > 0 && (reader.budget -= count) < 0) throw new IOException("level.dat이 너무 큽니다");
                    return count;
                }
                @Override public long skip(long count) throws IOException {
                    byte[] buffer = new byte[(int) Math.min(8192, count)];
                    long done = 0;
                    while (done < count) {
                        int read = read(buffer, 0, (int) Math.min(buffer.length, count - done));
                        if (read < 0) break;
                        done += read;
                    }
                    return done;
                }
            })) {
                if (in.readUnsignedByte() != 10) throw new IOException("level.dat 루트가 compound가 아닙니다");
                in.readUTF();
                Object root = reader.tag(in, 10, 0);
                if (!(root instanceof Map<?, ?> map) || !(map.get("Data") instanceof Map<?, ?> data)
                        || !(data.get("Version") instanceof Map<?, ?> version) || !(version.get("Name") instanceof String name)
                        || !name.matches("\\d+(\\.\\d+){1,2}")) throw new IOException("지원하는 정식 버전 월드의 level.dat이 아닙니다");
                if (data.containsKey("FML") || data.containsKey("fml")) throw new IOException("모드 월드는 지원하지 않습니다");
                return new Info(name, data.containsKey("Player"));
            }
        }
    }

    private Object tag(DataInputStream in, int type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT 깊이가 너무 큽니다");
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 8 -> in.readUTF();
            case 7, 11, 12 -> {
                int count = size(in);
                in.skipNBytes((long) count * (type == 11 ? 4 : type == 12 ? 8 : 1));
                yield null;
            }
            case 9 -> {
                int child = in.readUnsignedByte();
                int count = size(in);
                if (child == 0 && count != 0) throw new IOException("잘못된 NBT list");
                for (int i = 0; i < count; i++) tag(in, child, depth + 1);
                yield null;
            }
            case 10 -> {
                Map<String, Object> result = new HashMap<>();
                int child;
                int entries = 0;
                while ((child = in.readUnsignedByte()) != 0) {
                    if (++entries > 100_000) throw new IOException("NBT 항목이 너무 많습니다");
                    result.put(in.readUTF(), tag(in, child, depth + 1));
                }
                yield result;
            }
            default -> throw new IOException("알 수 없는 NBT tag: " + type);
        };
    }

    private int size(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 16_000_000) throw new IOException("잘못된 NBT 길이");
        return count;
    }
}
