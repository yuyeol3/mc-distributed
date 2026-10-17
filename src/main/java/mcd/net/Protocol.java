package mcd.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 중계가 이해해야 하는 마인크래프트 프로토콜의 아주 작은 부분: 연결 첫머리(핸드셰이크, 서버 목록 상태, 로그인
 * 시작)의 패킷 틀과 몇 가지 패킷. 이 구간은 압축도 암호화도 되지 않는다. 그 뒤로는 바이트를 그대로 넘긴다.
 * 패킷 ID는 26.1(프로토콜 775) 기준이며, 이 구간의 ID는 1.20.5 이후 바뀌지 않았다.
 */
final class Protocol {

    static final int INTENT_STATUS = 1;
    static final int INTENT_LOGIN = 2;
    static final int INTENT_TRANSFER = 3;

    static final int STATUS_REQUEST = 0x00;
    static final int STATUS_RESPONSE = 0x00;
    static final int PING = 0x01;

    static final int LOGIN_DISCONNECT = 0x00;
    static final int LOGIN_PLUGIN_REQUEST = 0x04;
    static final int LOGIN_PLUGIN_RESPONSE = 0x02;

    /** 첫머리 패킷은 작다. 이보다 크면 마인크래프트 연결이 아닌 것으로 본다. */
    private static final int MAX_PACKET = 32 * 1024;

    private Protocol() {}

    /** 길이 접두사까지 포함한 원래 바이트(그대로 다시 보낼 때 씀)와 패킷 ID, 나머지 내용. */
    record Packet(byte[] raw, int id, byte[] body) {}

    record Handshake(int protocolVersion, String host, int port, int intent) {}

    static Packet read(InputStream in) throws IOException {
        int length = readVarInt(in);
        if (length <= 0 || length > MAX_PACKET) {
            throw new IOException("마인크래프트 패킷이 아닙니다(길이 " + length + ")");
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) throw new EOFException();
        ByteArrayInputStream p = new ByteArrayInputStream(payload);
        int id = readVarInt(p);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        writeVarInt(raw, length);
        raw.write(payload);
        return new Packet(raw.toByteArray(), id, p.readAllBytes());
    }

    static Handshake handshake(Packet packet) throws IOException {
        if (packet.id() != 0x00) throw new IOException("핸드셰이크가 아닙니다");
        ByteArrayInputStream in = new ByteArrayInputStream(packet.body());
        int protocol = readVarInt(in);
        String host = readString(in);
        int port = (in.read() << 8) | in.read();
        return new Handshake(protocol, host, port, readVarInt(in));
    }

    static void write(OutputStream out, int id, byte[] body) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, id);
        payload.write(body);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, payload.size());
        payload.writeTo(frame);
        out.write(frame.toByteArray());
        out.flush();
    }

    /** 서버 목록에 보일 상태. 버전은 클라이언트의 것을 그대로 돌려줘 "호환 안 됨"으로 보이지 않게 한다. */
    static byte[] statusResponse(int protocolVersion, String motd) {
        String json = "{\"version\":{\"name\":\"mcd\",\"protocol\":" + protocolVersion + "},"
                + "\"players\":{\"max\":0,\"online\":0},\"description\":{\"text\":\"" + jsonEscape(motd) + "\"}}";
        return string(json);
    }

    static byte[] loginDisconnect(String message) {
        return string("{\"text\":\"" + jsonEscape(message) + "\"}");
    }

    /** 클라이언트가 이해하지 못해도 "모름"으로 답하는 질의. 로그인 단계에서 연결을 살려 두는 데 쓴다. */
    static byte[] loginPluginRequest(int messageId, String channel) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, messageId);
        out.writeBytes(string(channel));
        return out.toByteArray();
    }

    /** 로그인 플러그인 응답의 messageId. */
    static int pluginResponseId(Packet packet) throws IOException {
        return readVarInt(new ByteArrayInputStream(packet.body()));
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt가 너무 깁니다");
    }

    static void writeVarInt(OutputStream out, int value) {
        try {
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String readString(InputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_PACKET) throw new IOException("문자열 길이가 이상합니다");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    static byte[] string(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
        return out.toByteArray();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
