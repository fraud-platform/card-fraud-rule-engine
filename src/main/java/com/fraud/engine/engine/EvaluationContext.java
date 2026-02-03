package com.fraud.engine.engine;

import com.fraud.engine.domain.DebugInfo;
import com.fraud.engine.domain.Ruleset;
import com.fraud.engine.domain.TransactionContext;
import com.fraud.engine.domain.Decision;

public record EvaluationContext(
        TransactionContext transaction,
        Ruleset ruleset,
        Decision decision,
        boolean replayMode,
        long startTimeMs,
        String engineMode,
        DebugInfo.Builder debugBuilder
) {
    public static EvaluationContext create(
            TransactionContext transaction,
            Ruleset ruleset,
            Decision decision,
            boolean replayMode,
            long startTimeMs,
            String engineMode,
            DebugInfo.Builder debugBuilder) {
        return new EvaluationContext(
                transaction,
                ruleset,
                decision,
                replayMode,
                startTimeMs,
                engineMode,
                debugBuilder
        );
    }

    public String getEvaluationType() {
        return ruleset != null ? ruleset.getEvaluationType() : "AUTH";
    }

    public boolean isDebugEnabled() {
        return debugBuilder != null;
    }
}
