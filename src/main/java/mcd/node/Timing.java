package mcd.node;

import java.time.Duration;

/**
 * Git lease와 저장 주기. 기본 TTL 10분이면 heartbeat 2분, 스냅샷 10분, 관찰 주기 30초,
 * 빈 서버 종료 5분이다. VPN health와 짧은 실행 허가는 TTL과 독립적이다. 테스트에서는 TTL을 줄여서 쓴다.
 */
public record Timing(Duration ttl, Duration margin, Duration poll, Duration heartbeat, Duration snapshot, Duration idle) {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    public static Timing fromTtl(Duration ttl) {
        return new Timing(ttl, ttl.dividedBy(5), ttl.dividedBy(20), ttl.dividedBy(5), ttl, ttl.dividedBy(2));
    }
}
