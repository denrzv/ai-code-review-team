package dev.denrzv.codereview;

import java.time.Instant;

import dev.langchain4j.internal.Json;
import io.temporal.common.converter.DefaultDataConverter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SdkSerializationTest {

    @Test
    void temporalRoundTripsARecordWithJdkTime() {
        var converter = DefaultDataConverter.newDefaultInstance();
        var original = new TimedValue(Instant.EPOCH);
        var payload = converter.toPayload(original).orElseThrow();

        assertThat(converter.fromPayload(payload, TimedValue.class, TimedValue.class)).isEqualTo(original);
    }

    @Test
    void langchain4jRoundTripsARecordWithItsJacksonMapper() {
        var original = new Message("bootstrap");

        assertThat(Json.fromJson(Json.toJson(original), Message.class)).isEqualTo(original);
    }

    record TimedValue(Instant time) {
    }

    record Message(String text) {
    }
}
