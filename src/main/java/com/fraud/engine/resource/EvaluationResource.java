package com.fraud.engine.resource;

import com.fraud.engine.domain.Decision;
import com.fraud.engine.domain.Ruleset;
import com.fraud.engine.domain.TransactionContext;
import com.fraud.engine.engine.RuleEvaluator;
import com.fraud.engine.kafka.DecisionPublisher;
import com.fraud.engine.kafka.EventPublishException;
import com.fraud.engine.outbox.AsyncOutboxDispatcher;
import com.fraud.engine.ruleset.RulesetLoader;
import com.fraud.engine.ruleset.RulesetRegistry;
import com.fraud.engine.resource.dto.*;
import com.fraud.engine.util.DecisionNormalizer;
import com.fraud.engine.util.EngineMetrics;
import com.fraud.engine.util.RulesetKeyResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * REST API for card fraud rule evaluation.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>AUTH evaluation (first-match, fail-open)</li>
 *   <li>MONITORING evaluation (all-match, track decisions)</li>
 *   <li>Hot reload management (Phase 4)</li>
 * </ul>
 *
 * <p>Phase 4: Uses RulesetRegistry for fast in-memory ruleset lookup
 * and atomic hot-swap without downtime.
 */
@Path("/v1/evaluate")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Evaluation", description = "Card fraud rule evaluation endpoints")
public class EvaluationResource {

    private static final Logger LOG = Logger.getLogger(EvaluationResource.class);

    @Inject
    RuleEvaluator ruleEvaluator;

    @Inject
    RulesetLoader rulesetLoader;

    @Inject
    RulesetRegistry rulesetRegistry;

    @Inject
    AsyncOutboxDispatcher asyncOutboxDispatcher;

    @Inject
    DecisionPublisher decisionPublisher;

    @Inject
    RulesetKeyResolver rulesetKeyResolver;

    @Inject
    EngineMetrics engineMetrics;

    @POST
    @Path("/auth")
    @Operation(
            summary = "AUTH evaluation",
            description = "Evaluates a transaction using AUTH ruleset. First-match semantics, fail-open by default."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Evaluation successful",
                    content = @Content(schema = @Schema(implementation = Decision.class))
            ),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response evaluateAuth(
            @RequestBody(
                    description = "Transaction to evaluate",
                    required = true,
                    content = @Content(schema = @Schema(implementation = TransactionContext.class))
            )
            TransactionContext transaction) {

        if (LOG.isDebugEnabled()) {
            LOG.debugf("AUTH evaluation request: transactionId=%s", transaction.getTransactionId());
        }

        return evaluateTransaction(transaction, RuleEvaluator.EVAL_AUTH);
    }

    @POST
    @Path("/monitoring")
    @Operation(
            summary = "MONITORING evaluation",
            description = "Evaluates a transaction using MONITORING ruleset. All-match semantics, tracks all decisions."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Evaluation successful",
                    content = @Content(schema = @Schema(implementation = Decision.class))
            ),
            @APIResponse(responseCode = "400", description = "Invalid request"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response evaluateMonitoring(
            @RequestBody(
                    description = "Transaction to evaluate",
                    required = true,
                    content = @Content(schema = @Schema(implementation = TransactionContext.class))
            )
            TransactionContext transaction) {

        if (LOG.isDebugEnabled()) {
            LOG.debugf("MONITORING evaluation request: transactionId=%s", transaction.getTransactionId());
        }

        String normalizedDecision = DecisionNormalizer.normalizeMONITORINGDecision(transaction.getDecision());
        if (normalizedDecision == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_REQUEST", "decision must be APPROVE or DECLINE"))
                    .build();
        }
        transaction.setDecision(normalizedDecision);

        return evaluateTransaction(transaction, RuleEvaluator.EVAL_MONITORING);
    }

    private Response evaluateTransaction(TransactionContext transaction, String evaluationType) {
        try {
            // Determine ruleset key from transaction type or use default
            String rulesetKey = rulesetKeyResolver.resolve(transaction, evaluationType);

            // ADR-0016: Country-aware lookup with global fallback
            String country = transaction != null ? transaction.getCountryCode() : null;
            long lookupStart = System.nanoTime();
            Ruleset ruleset = rulesetRegistry.getRulesetWithFallback(country, rulesetKey);
            long lookupEnd = System.nanoTime();
            double lookupTimeMs = (lookupEnd - lookupStart) / 1_000_000.0;

            if (ruleset != null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugf("Using ruleset: %s/v%d", rulesetKey, ruleset.getVersion());
                }

                Decision decision = ruleEvaluator.evaluate(transaction, ruleset);

                com.fraud.engine.domain.TimingBreakdown breakdown = decision.getTimingBreakdown();
                if (breakdown == null) {
                    breakdown = new com.fraud.engine.domain.TimingBreakdown();
                    decision.setTimingBreakdown(breakdown);
                }
                breakdown.setRulesetLookupTimeMs(lookupTimeMs);
                breakdown.setRuleEvaluationTimeMs(decision.getProcessingTimeMs() - lookupTimeMs);

                // Count velocity checks
                if (decision.getVelocityResults() != null) {
                    breakdown.setVelocityCheckCount(decision.getVelocityResults().size());
                }

                // Measure Redis outbox time for AUTH
                long persistStart = System.nanoTime();
                persistDecisionOutcome(transaction, decision, evaluationType);
                long persistEnd = System.nanoTime();
                double persistTimeMs = (persistEnd - persistStart) / 1_000_000.0;

                // Only AUTH uses Redis outbox (MONITORING uses async Kafka)
                if (RuleEvaluator.EVAL_AUTH.equalsIgnoreCase(evaluationType)) {
                    breakdown.setRedisOutboxTimeMs(persistTimeMs);
                    return Response.ok(SlimAuthResult.from(decision)).build();
                }

                return Response.ok(decision).build();
            }

            // Ruleset not found in cache - this should never happen if startup loading worked
            LOG.errorf("Compiled ruleset not found in registry: %s (was it loaded at startup?)", rulesetKey);

            Decision decision = buildErrorDecision(
                    transaction,
                    evaluationType,
                    rulesetKey,
                    "RULESET_NOT_LOADED",
                    "Ruleset not loaded in registry: " + rulesetKey
            );

            // Attach timing breakdown even for fail-open
            com.fraud.engine.domain.TimingBreakdown breakdown = new com.fraud.engine.domain.TimingBreakdown();
            breakdown.setRulesetLookupTimeMs(lookupTimeMs);
            decision.setTimingBreakdown(breakdown);

            persistDecisionOutcome(transaction, decision, evaluationType);
            return okResponse(decision, evaluationType);
        } catch (EventPublishException e) {
            // ADR-0003: engine-layer failures return HTTP 200 with in-band signaling.
            // 503 is reserved only for OUTBOX_UNAVAILABLE (ADR-0014).
            LOG.errorf(e, "Kafka publish failed for %s evaluation", evaluationType);
            Decision degraded = buildErrorDecision(
                    transaction,
                    evaluationType,
                    rulesetKeyResolver.resolve(transaction, evaluationType),
                    "EVENT_PUBLISH_FAILED",
                    "Failed to persist decision event"
            );
            return okResponse(degraded, evaluationType);
        } catch (Exception e) {
            LOG.errorf(e, "Error during evaluation");

            Decision decision = buildErrorDecision(
                    transaction,
                    evaluationType,
                    rulesetKeyResolver.resolve(transaction, evaluationType),
                    "INTERNAL_ERROR",
                    "Internal evaluation error"
            );

            try {
                persistDecisionOutcome(transaction, decision, evaluationType);
                return okResponse(decision, evaluationType);
            } catch (EventPublishException persistEx) {
                // ADR-0003: Kafka failures return 200 + DEGRADED, not 503
                LOG.errorf(persistEx, "Kafka publish failed while handling evaluation error");
                return okResponse(decision, evaluationType);
            } catch (Exception persistEx) {
                // ADR-0003: engine-layer failures return 200 with in-band signaling
                LOG.errorf(persistEx, "Failed to persist fail-open decision");
                return okResponse(decision, evaluationType);
            }
        }
    }

    private Decision buildErrorDecision(
            TransactionContext transaction,
            String evaluationType,
            String rulesetKey,
            String errorCode,
            String errorMessage) {
        String transactionId = transaction != null ? transaction.getTransactionId() : null;
        Decision decision = new Decision(transactionId, evaluationType);
        if (RuleEvaluator.EVAL_MONITORING.equalsIgnoreCase(evaluationType)) {
            String monitoringDecision = transaction != null ? transaction.getDecision() : null;
            decision.setDecision(monitoringDecision != null ? monitoringDecision : Decision.DECISION_APPROVE);
            decision.setEngineMode(Decision.MODE_DEGRADED);
            engineMetrics.incrementDegraded();
        } else {
            decision.setDecision(Decision.DECISION_APPROVE);
            decision.setEngineMode(Decision.MODE_FAIL_OPEN);
            engineMetrics.incrementFailOpen();
        }
        decision.setEngineErrorCode(errorCode);
        decision.setEngineErrorMessage(errorMessage);
        decision.setRulesetKey(rulesetKey);
        // Only embed transactionContext for non-AUTH (AUTH carries it via outbox separately)
        if (transaction != null && !RuleEvaluator.EVAL_AUTH.equalsIgnoreCase(evaluationType)) {
            decision.setTransactionContext(transaction.toEvaluationContext());
        }
        return decision;
    }

    private void persistDecisionOutcome(TransactionContext transaction, Decision decision, String evaluationType) {
        if (RuleEvaluator.EVAL_AUTH.equalsIgnoreCase(evaluationType)) {
            // ADR-0018: never block AUTH on durability.
            // Request thread only enqueues; background writer persists to outbox.
            asyncOutboxDispatcher.enqueueAuth(transaction, decision);
            return;
        }
        // Use async publish for MONITORING to avoid blocking on Kafka (performance optimization)
        decisionPublisher.publishDecisionAsync(decision);
    }

    private Response okResponse(Decision decision, String evaluationType) {
        if (RuleEvaluator.EVAL_AUTH.equalsIgnoreCase(evaluationType)) {
            // Use ultra-slim response for AUTH (4 fields, ~80 bytes, jsoniter serialization <0.1ms)
            return Response.ok(SlimAuthResult.from(decision)).build();
        }
        return Response.ok(decision).build();
    }


    /**
     * Health check endpoint for the evaluation service.
     */
    @GET
    @Path("/health")
    @Operation(summary = "Health check", description = "Check if the evaluation service is healthy")
    @APIResponse(responseCode = "200", description = "Service is healthy")
    public Response health() {
        return Response.ok(new HealthResponse("UP", rulesetLoader.isStorageAccessible())).build();
    }

    // ========== Phase 4: Hot Reload Management Endpoints ==========

    /**
     * Gets the current status of the ruleset registry.
     */
    @GET
    @Path("/rulesets/registry/status")
    @Operation(summary = "Get registry status", description = "Get information about loaded rulesets")
    @APIResponse(responseCode = "200", description = "Registry status")
    public Response getRegistryStatus() {
        Set<String> countries = rulesetRegistry.getCountries();
        int totalRulesets = rulesetRegistry.size();

        RegistryStatus status = new RegistryStatus();
        status.totalRulesets = totalRulesets;
        status.countries = countries.size();
        status.storageAccessible = rulesetLoader.isStorageAccessible();

        return Response.ok(status).build();
    }

    /**
     * Gets all ruleset keys for a country.
     */
    @GET
    @Path("/rulesets/registry/{country}")
    @Operation(summary = "Get rulesets by country", description = "Get all ruleset keys for a country")
    @APIResponse(responseCode = "200", description = "List of ruleset keys")
    public Response getCountryRulesets(@PathParam("country") String country) {
        Set<String> keys = rulesetRegistry.getRulesetKeys(country);
        return Response.ok(new CountryRulesets(country, keys)).build();
    }

    /**
     * Hot-swaps a ruleset to a new version.
     */
    @POST
    @Path("/rulesets/hotswap")
    @Operation(summary = "Hot swap ruleset", description = "Atomically replace a ruleset with a new version")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Hot swap completed"),
            @APIResponse(responseCode = "400", description = "Invalid request")
    })
    public Response hotSwapRuleset(HotSwapRequest request) {
        if (request.key == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_REQUEST", "rulesetKey is required"))
                    .build();
        }

        if (request.version <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_REQUEST", "version must be positive"))
                    .build();
        }

        String country = request.country != null ? request.country : "global";
        RulesetRegistry.HotSwapResult result =
                rulesetRegistry.hotSwap(country, request.key, request.version);

        if (result.success()) {
            return Response.ok(new HotSwapResponse(
                    true,
                    result.status(),
                    result.message(),
                    result.oldVersion(),
                    request.version
            )).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new HotSwapResponse(
                            false,
                            result.status(),
                            result.message(),
                            result.oldVersion(),
                            request.version
                    ))
                    .build();
        }
    }

    /**
     * Loads and registers a ruleset.
     */
    @POST
    @Path("/rulesets/load")
    @Operation(summary = "Load ruleset", description = "Load and register a ruleset into the registry")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Ruleset loaded"),
            @APIResponse(responseCode = "400", description = "Invalid request"),
            @APIResponse(responseCode = "404", description = "Ruleset not found")
    })
    public Response loadRuleset(LoadRulesetRequest request) {
        if (request.key == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_REQUEST", "rulesetKey is required"))
                    .build();
        }

        if (request.version <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_REQUEST", "version must be positive"))
                    .build();
        }

        String country = request.country != null ? request.country : "global";
        boolean success = rulesetRegistry.loadAndRegister(country, request.key, request.version);

        if (success) {
            return Response.ok(new LoadRulesetResponse(
                    true,
                    "Ruleset loaded successfully",
                    request.key,
                    request.version,
                    country
            )).build();
        } else {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("LOAD_FAILED", "Failed to load ruleset"))
                    .build();
        }
    }

    /**
     * Bulk loads rulesets at startup.
     */
    @POST
    @Path("/rulesets/bulk-load")
    @Operation(summary = "Bulk load rulesets", description = "Load multiple rulesets into the registry")
    @APIResponse(responseCode = "200", description = "Bulk load completed")
    public Response bulkLoadRulesets(BulkLoadRequest request) {
        if (request.rulesets == null || request.rulesets.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_REQUEST", "rulesets list is required"))
                    .build();
        }

        int loaded = rulesetRegistry.bulkLoad(request.rulesets);
        return Response.ok(new BulkLoadResponse(loaded, request.rulesets.size())).build();
    }
}
