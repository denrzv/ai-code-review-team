package dev.denrzv.codereview.adapter.llm;

import java.math.BigDecimal;
import dev.denrzv.codereview.config.ReviewProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static dev.denrzv.codereview.adapter.llm.DeepSeekProtocolSmoke.MAX_ELAPSED;
import static dev.denrzv.codereview.adapter.llm.DeepSeekProtocolSmoke.MAX_REQUESTS;
import static dev.denrzv.codereview.adapter.llm.DeepSeekProtocolSmoke.REQUIRED_TOOL_CALLS;
import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekLiveSmokeTest {

    private static final String API_KEY = "DEEPSEEK_API_KEY";

    @Test
    @EnabledIfSystemProperty(named = "deepseek.live", matches = "true")
    void exercisesTheFixedSyntheticFixture() {
        var result = run(requireCredential(System.getenv(API_KEY)));
        System.out.printf("model=%s outcome=%s requests=%d tools=%d elapsed_ms=%d prices_as_of=%s usage=%s%n",
                result.model(), result.outcome(), result.attempts().size(), result.toolCalls(),
                result.elapsed().toMillis(), result.pricesAsOf(), result.attempts());
        assertProtocol(result);
    }

    static void assertProtocol(DeepSeekProtocolSmoke.Result result) {
        assertThat(result.outcome()).isEqualTo(DeepSeekProtocolSmoke.Outcome.COMPLETE);
        assertThat(result.toolCalls()).isEqualTo(REQUIRED_TOOL_CALLS);
        assertThat(result.decision().action()).as("Planner action").isEqualTo(DeepSeekProtocolSmoke.PlanAction.COMPLETE);
        assertThat(result.attempts()).allSatisfy(attempt -> {
            Integer reasoning = attempt.observed().reasoning();
            if (reasoning != null) {
                assertThat(reasoning).as("reported reasoning tokens").isZero();
            }
        });
    }

    static String requireCredential(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Enabled live smoke requires " + API_KEY);
        }
        return key;
    }

    static DeepSeekProtocolSmoke.Result run(String key, String... settingsOverrides) {
        var smoke = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Settings.class)
                .withPropertyValues(settingsOverrides);
        var result = new DeepSeekProtocolSmoke.Result[1];
        smoke.run(context -> {
            var settings = context.getBean(ReviewProperties.class);
            result[0] = configuredSmoke(settings, key).run(DeepSeekProtocolSmoke.FIXTURE_ID);
        });
        return result[0];
    }

    private static DeepSeekProtocolSmoke configuredSmoke(ReviewProperties settings, String key) {
        var model = settings.model();
        var budget = settings.budget();
        var allocation = budget.run();
        var elapsed = budget.maxElapsed().compareTo(MAX_ELAPSED) > 0 ? MAX_ELAPSED : budget.maxElapsed();
        var limit = new DeepSeekProtocolSmoke.Budget(MAX_REQUESTS,
                Math.min(REQUIRED_TOOL_CALLS, budget.toolCalls().perSpecialist()),
                allocation.inputTokens(), allocation.outputTokens(),
                BigDecimal.valueOf(allocation.costMicrodollars(), 6), elapsed);
        var rate = settings.prices().get(model.name());
        assertThat(rate).as("dated pricing for the selected model").isNotNull();
        var price = new DeepSeekProtocolSmoke.Price(rate.asOf(), rate.cacheHitUsdPerMillion(),
                rate.cacheMissUsdPerMillion(), rate.outputUsdPerMillion());
        return new DeepSeekProtocolSmoke(model.baseUrl(), model.name(), key, limit, price);
    }

    @EnableConfigurationProperties(ReviewProperties.class)
    static class Settings {
    }
}
