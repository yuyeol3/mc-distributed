package mcd.update;

import java.util.Arrays;
import java.util.Optional;

/** mcd 버전(예: 0.2.0, 태그 v0.2.0). 숫자를 앞에서부터 비교한다. */
public record AppVersion(int[] parts) implements Comparable<AppVersion> {

    /** "v0.2.0", "0.2.0", "0.2.0-beta" 같은 문자열. 숫자로 읽을 수 없으면 비어 있다. */
    public static Optional<AppVersion> parse(String text) {
        if (text == null) return Optional.empty();
        String core = text.trim().replaceFirst("^[vV]", "").split("[-+]", 2)[0];
        try {
            return Optional.of(new AppVersion(Arrays.stream(core.split("\\.")).mapToInt(Integer::parseInt).toArray()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 지금 실행 중인 mcd의 버전(jar manifest). 개발 중 실행(클래스 폴더)에는 없다. */
    public static Optional<AppVersion> current() {
        return parse(AppVersion.class.getPackage().getImplementationVersion());
    }

    @Override
    public int compareTo(AppVersion o) {
        return Arrays.compare(parts, o.parts);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AppVersion v && Arrays.equals(parts, v.parts);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(parts);
    }

    @Override
    public String toString() {
        return String.join(".", Arrays.stream(parts).mapToObj(Integer::toString).toList());
    }
}
