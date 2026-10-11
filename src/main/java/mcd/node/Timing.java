package mcd.node;

import java.time.Duration;

/**
 * 노드의 시간 값. 모두 TTL에 비례한다. 기본 TTL 10분이면 heartbeat 2분, 스냅샷 10분, 관찰 주기 30초,
 * 빈 서버 종료 5분이다. push 빈도는 GitHub 이용 정책을 고려해 낮게 잡았다. 테스트에서는 TTL을 줄여서 쓴다.
 */
public record Timing(Duration ttl, Duration margin, Duration poll, Duration heartbeat, Duration snapshot, Duration idle) {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    public static Timing fromTtl(Duration ttl) {
        return new Timing(ttl, ttl.dividedBy(5), ttl.dividedBy(20), ttl.dividedBy(5), ttl, ttl.dividedBy(2));
    }
}
