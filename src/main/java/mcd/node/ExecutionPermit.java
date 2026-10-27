package mcd.node;

import java.time.Duration;
import java.util.function.LongSupplier;

/** 원격 조회의 시작 시각으로 제한하는 단조 시간 허가. 한 번 만료되면 재사용하지 않는다. */
final class ExecutionPermit {
    private final long lifetime;
    private final LongSupplier clock;
    private long deadline;
    private boolean confirmed;
    private boolean revoked;

    ExecutionPermit(Duration lifetime, LongSupplier clock) {
        this.lifetime = lifetime.toNanos();
        this.clock = clock;
    }

    synchronized boolean confirm(long requestStart) {
        long now = clock.getAsLong();
        if (revoked || confirmed && now - deadline >= 0 || now - requestStart >= lifetime) {
            revoked = true;
            return false;
        }
        confirmed = true;
        deadline = requestStart + lifetime;
        return true;
    }

    synchronized void revoke() { revoked = true; }

    synchronized long remainingMillis() {
        if (!confirmed || revoked) return 0;
        long remaining = deadline - clock.getAsLong();
        if (remaining <= 0) { revoked = true; return 0; }
        return Math.max(1, remaining / 1_000_000);
    }
}
