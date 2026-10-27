package mcd.net;

import mcd.lock.LockState;
import org.junit.jupiter.api.Test;
import java.net.Proxy;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class HealthCheckTest {
    @Test
    void onlyMatchingSessionAndEpochRespondHealthy() throws Exception {
        LockState owner = new LockState("A", 1, LockState.State.GUARDED, "a:1", "session-a", "");
        try (HealthCheck server = HealthCheck.start(0, true, () -> owner)) {
            String address = "127.0.0.1:" + server.port();
            assertTrue(HealthCheck.probe(address, owner, Proxy.NO_PROXY, Duration.ofSeconds(1)));
            assertFalse(HealthCheck.probe(address, new LockState("A", 2, owner.state(), "a:1", "session-a", ""),
                    Proxy.NO_PROXY, Duration.ofSeconds(1)));
        }
    }
}
