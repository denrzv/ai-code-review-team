package dev.denrzv.codereview;

import dev.denrzv.codereview.config.ReviewProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPropertiesTest {

    private static final String ALLOCATION_ERROR = "Role allocations exceed the run budget";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void rejectsAnInvalidTypedSetting() {
        runner.withPropertyValues("review.budget.max-elapsed=invalid").run(context ->
                assertThat(context.getStartupFailure())
                        .hasCauseInstanceOf(BindException.class)
                        .hasStackTraceContaining("review.budget.max-elapsed"));
    }

    @Test
    void rejectsAnUnknownReviewSetting() {
        String key = "review.limits.max-chagned-files";

        runner.withPropertyValues(key + "=10").run(context ->
                assertThat(context.getStartupFailure())
                        .hasCauseInstanceOf(BindException.class)
                        .hasStackTraceContaining(key));
    }

    @ParameterizedTest
    @CsvSource({
            "max-changed-files, 21, 20",
            "max-changed-lines, 1501, 1500",
            "max-read-lines, 201, 200",
            "max-diff-lines-per-file, 401, 400",
            "max-search-hits, 21, 20",
            "max-tool-result-code-points, 16001, 16000",
            "max-read-lines, 0, 200"
    })
    void rejectsOutOfRangeLimits(String limit, int value, int cap) {
        String key = "review.limits." + limit;

        runner.withPropertyValues(key + "=" + value).run(context ->
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(key + " must be between 1 and " + cap));
    }

    @ParameterizedTest
    @ValueSource(strings = {"input-tokens=10001", "output-tokens=2001", "cost-microdollars=20001"})
    void rejectsASequenceThatExceedsOneRunAllocation(String override) {
        runner.withPropertyValues("review.budget.roles.planner." + override).run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseMessage(ALLOCATION_ERROR));
    }

    @ParameterizedTest
    @ValueSource(strings = {"input-tokens=190001", "output-tokens=38001", "cost-microdollars=500001"})
    void rejectsASingleInvocationThatExceedsOneRunAllocation(String override) {
        runner.withPropertyValues("review.budget.roles.single." + override).run(context ->
                assertThat(context.getStartupFailure()).hasRootCauseMessage(ALLOCATION_ERROR));
    }

    @Test
    void rejectsAnAllocationThatWouldGiveARetryNoTokens() {
        runner.withPropertyValues("review.budget.roles.planner.output-tokens=4").run(context ->
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("Allocations must support all three attempt shares"));
    }

    @EnableConfigurationProperties(ReviewProperties.class)
    static class PropertiesConfiguration {
    }
}
