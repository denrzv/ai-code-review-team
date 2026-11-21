package dev.denrzv.codereview.config;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "review", ignoreUnknownFields = false)
public record ReviewProperties(
        Model model,
        Budget budget,
        Limits limits,
        List<Repository> allowlist,
        Map<String, Price> prices,
        Path outputDir) {

    public ReviewProperties {
        allowlist = allowlist == null ? List.of() : List.copyOf(allowlist);
        prices = Map.copyOf(prices);
    }

    public record Model(URI baseUrl, String name, boolean thinkingEnabled) {
    }

    public record Budget(
            Duration maxElapsed,
            int maxDelegations,
            int maxInvocationsPerTopic,
            ToolCalls toolCalls,
            Allocation run,
            RoleAllocations roles) {

        public Budget {
            if (!maxElapsed.isPositive() || maxDelegations < 1 || maxInvocationsPerTopic < 1) {
                throw new IllegalArgumentException("Elapsed time and delegation limits must be positive");
            }

            if (!run.covers(roles.single()) || !run.coversSequence(roles, maxDelegations)) {
                throw new IllegalArgumentException("Role allocations exceed the run budget");
            }
        }
    }

    public record ToolCalls(int perRun, int perSpecialist) {

        public ToolCalls {
            if (perSpecialist < Allocation.MIN_RETRY_ALLOCATION || perRun < perSpecialist) {
                throw new IllegalArgumentException("Invalid run or specialist tool call allocation");
            }
        }
    }

    public record RoleAllocations(Allocation planner, Allocation specialist, Allocation single) {
    }

    public record Allocation(int inputTokens, int outputTokens, int costMicrodollars) {

        // The smallest planned retry share is 20%.
        private static final int MIN_RETRY_ALLOCATION = 5;

        public Allocation {
            if (inputTokens < MIN_RETRY_ALLOCATION || outputTokens < MIN_RETRY_ALLOCATION
                    || costMicrodollars < MIN_RETRY_ALLOCATION) {
                throw new IllegalArgumentException("Allocations must support all three attempt shares");
            }
        }

        boolean covers(Allocation requested) {
            return inputTokens >= requested.inputTokens
                    && outputTokens >= requested.outputTokens
                    && costMicrodollars >= requested.costMicrodollars;
        }

        boolean coversSequence(RoleAllocations roles, int delegations) {
            Allocation planner = roles.planner();
            Allocation specialist = roles.specialist();
            long planningCalls = delegations + 1L;
            long specialistCalls = delegations;
            return inputTokens >= planningCalls * planner.inputTokens + specialistCalls * specialist.inputTokens
                    && outputTokens >= planningCalls * planner.outputTokens + specialistCalls * specialist.outputTokens
                    && costMicrodollars >= planningCalls * planner.costMicrodollars
                    + specialistCalls * specialist.costMicrodollars;
        }
    }

    public record Limits(
            int maxChangedFiles,
            int maxChangedLines,
            int maxReadLines,
            int maxDiffLinesPerFile,
            int maxSearchHits,
            int maxToolResultCodePoints) {

        public Limits {
            requireWithinCap("max-changed-files", maxChangedFiles, 20);
            requireWithinCap("max-changed-lines", maxChangedLines, 1_500);
            requireWithinCap("max-read-lines", maxReadLines, 200);
            requireWithinCap("max-diff-lines-per-file", maxDiffLinesPerFile, 400);
            requireWithinCap("max-search-hits", maxSearchHits, 20);
            requireWithinCap("max-tool-result-code-points", maxToolResultCodePoints, 16_000);
        }

        private static void requireWithinCap(String name, int value, int cap) {
            if (value < 1 || value > cap) {
                throw new IllegalArgumentException("review.limits." + name + " must be between 1 and " + cap);
            }
        }
    }

    public record Repository(String id, Path path, boolean synthetic) {
    }

    public record Price(
            LocalDate asOf,
            BigDecimal cacheHitUsdPerMillion,
            BigDecimal cacheMissUsdPerMillion,
            BigDecimal outputUsdPerMillion) {
    }
}
