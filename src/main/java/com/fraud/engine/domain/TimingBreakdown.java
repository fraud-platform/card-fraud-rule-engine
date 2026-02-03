package com.fraud.engine.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Detailed timing breakdown for evaluation latency analysis.
 * <p>
 * Captures component-level timing to ensure 10-20ms AUTH latency target is met.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TimingBreakdown {

    @JsonProperty("total_processing_time_ms")
    private double totalProcessingTimeMs;

    @JsonProperty("ruleset_lookup_time_ms")
    private Double rulesetLookupTimeMs;

    @JsonProperty("rule_evaluation_time_ms")
    private Double ruleEvaluationTimeMs;

    @JsonProperty("velocity_check_time_ms")
    private Double velocityCheckTimeMs;

    @JsonProperty("velocity_check_count")
    private Integer velocityCheckCount;

    @JsonProperty("decision_build_time_ms")
    private Double decisionBuildTimeMs;

    public TimingBreakdown() {
    }

    public TimingBreakdown(double totalProcessingTimeMs) {
        this.totalProcessingTimeMs = totalProcessingTimeMs;
    }

    public double getTotalProcessingTimeMs() {
        return totalProcessingTimeMs;
    }

    public void setTotalProcessingTimeMs(double totalProcessingTimeMs) {
        this.totalProcessingTimeMs = totalProcessingTimeMs;
    }

    public Double getRulesetLookupTimeMs() {
        return rulesetLookupTimeMs;
    }

    public void setRulesetLookupTimeMs(Double rulesetLookupTimeMs) {
        this.rulesetLookupTimeMs = rulesetLookupTimeMs;
    }

    public Double getRuleEvaluationTimeMs() {
        return ruleEvaluationTimeMs;
    }

    public void setRuleEvaluationTimeMs(Double ruleEvaluationTimeMs) {
        this.ruleEvaluationTimeMs = ruleEvaluationTimeMs;
    }

    public Double getVelocityCheckTimeMs() {
        return velocityCheckTimeMs;
    }

    public void setVelocityCheckTimeMs(Double velocityCheckTimeMs) {
        this.velocityCheckTimeMs = velocityCheckTimeMs;
    }

    public Integer getVelocityCheckCount() {
        return velocityCheckCount;
    }

    public void setVelocityCheckCount(Integer velocityCheckCount) {
        this.velocityCheckCount = velocityCheckCount;
    }

    public Double getDecisionBuildTimeMs() {
        return decisionBuildTimeMs;
    }

    public void setDecisionBuildTimeMs(Double decisionBuildTimeMs) {
        this.decisionBuildTimeMs = decisionBuildTimeMs;
    }

    /**
     * Creates a timing breakdown with nanosecond precision converted to milliseconds.
     */
    public static TimingBreakdown fromNanos(long totalNanos) {
        return new TimingBreakdown(totalNanos / 1_000_000.0);
    }

    @Override
    public String toString() {
        return "TimingBreakdown{" +
                "total=" + totalProcessingTimeMs + "ms" +
                ", rulesetLookup=" + rulesetLookupTimeMs + "ms" +
                ", ruleEvaluation=" + ruleEvaluationTimeMs + "ms" +
                ", velocityCheck=" + velocityCheckTimeMs + "ms" +
                ", velocityCount=" + velocityCheckCount +
                '}';
    }
}
