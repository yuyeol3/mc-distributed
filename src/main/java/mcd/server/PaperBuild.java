package mcd.server;

import java.util.Arrays;

/**
 * Paper 빌드 식별자 "버전-빌드"(예: 26.1.2-74). 버전은 마인크래프트 버전과 같고 클라이언트도 이 버전이어야 한다.
 * 비교는 버전 숫자를 앞에서부터, 같으면 빌드 번호로 한다(1.21.11 < 26.1.2-74 < 26.1.2-80 < 26.2-1).
 */
public record PaperBuild(String version, int build) implements Comparable<PaperBuild> {

    public static PaperBuild parse(String text) {
        String s = text.trim();
        int dash = s.lastIndexOf('-');
        if (dash <= 0) {
            throw new IllegalArgumentException("Paper 빌드는 '버전-빌드' 형식이어야 한다: " + text);
        }
        return new PaperBuild(s.substring(0, dash), Integer.parseInt(s.substring(dash + 1)));
    }

    /** 빌드 번호를 따지지 않고 버전만 비교한다. */
    public int compareVersion(String other) {
        return Arrays.compare(numbers(version), numbers(other));
    }

    @Override
    public int compareTo(PaperBuild o) {
        int byVersion = compareVersion(o.version);
        return byVersion != 0 ? byVersion : Integer.compare(build, o.build);
    }

    @Override
    public String toString() {
        return version + "-" + build;
    }

    private static int[] numbers(String version) {
        return Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }
}
