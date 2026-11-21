package dev.denrzv.codereview.orchestration.workflow;

import java.time.Instant;
import java.util.UUID;

final class NonReplayableCallsFixture {

    Object[] capture() {
        return new Object[] {System.currentTimeMillis(), System.nanoTime(), Instant.now(), UUID.randomUUID()};
    }
}
