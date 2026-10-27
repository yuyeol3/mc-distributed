package mcd.node;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionPermitTest {
    @Test
    void lateReplyCannotReviveExpiredPermit() {
        AtomicLong clock = new AtomicLong();
        ExecutionPermit permit = new ExecutionPermit(Duration.ofSeconds(20), clock::get);
        assertTrue(permit.confirm(0));
        clock.set(Duration.ofSeconds(19).toNanos());
        assertEquals(1000, permit.remainingMillis());
        clock.set(Duration.ofSeconds(21).toNanos());
        assertFalse(permit.confirm(Duration.ofSeconds(18).toNanos()));
        assertEquals(0, permit.remainingMillis());
    }
}
