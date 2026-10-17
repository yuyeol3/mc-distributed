package mcd.update;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub 릴리스 목록을 읽기 위한 아주 작은 JSON 파서. 객체는 Map, 배열은 List, 문자열은 String, 숫자는 Double,
 * true/false는 Boolean, null은 null로 돌려준다. 릴리스마다 첨부 파일 배열이 중첩돼 있어서 정규식으로는 위험하다.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        Object value = p.value();
        p.skipSpace();
        if (p.i != p.s.length()) throw p.error("끝에 남은 내용이 있습니다");
        return value;
    }

    private Object value() {
        skipSpace();
        if (i >= s.length()) throw error("값이 없습니다");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        i++;
        skipSpace();
        if (peek('}')) return map;
        while (true) {
            skipSpace();
            String key = string();
            skipSpace();
            expect(':');
            map.put(key, value());
            skipSpace();
            if (peek('}')) return map;
            expect(',');
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        skipSpace();
        if (peek(']')) return list;
        while (true) {
            list.add(value());
            skipSpace();
            if (peek(']')) return list;
            expect(',');
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("문자열이 끝나지 않았습니다");
            char c = s.charAt(i++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> out.append(e); // \" \\ \/
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("알 수 없는 값");
        return Double.parseDouble(s.substring(start, i));
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, i)) throw error("알 수 없는 값");
        i += word.length();
        return value;
    }

    private void skipSpace() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("'" + c + "'가 와야 합니다");
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("JSON 오류(" + i + "번째 글자): " + message);
    }
}
