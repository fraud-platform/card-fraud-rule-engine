package com.fraud.engine.resource;

import com.fraud.engine.domain.Decision;
import com.fraud.engine.domain.Ruleset;
import com.fraud.engine.domain.TransactionContext;
import com.fraud.engine.engine.RuleEvaluator;
import com.fraud.engine.kafka.DecisionPublisher;
import com.fraud.engine.ruleset.RulesetLoader;
import com.fraud.engine.ruleset.RulesetRegistry;
import com.fraud.engine.resource.dto.*;
import com.fraud.engine.security.ScopeValidator;
import com.fraud.engine.util.DecisionNormalizer;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
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
@SecurityScheme(
        securitySchemeName = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class EvaluationResource {

    private static final Logger LOG = Logger.getLogger(EvaluationResource.class);

    @Inject
    RuleEvaluator ruleEvaluator;

    @Inject
    RulesetLoader rulesetLoader;

    @Inject
    RulesetRegistry rulesetRegistry;

    @Inject
    DecisionPublisher decisionPublisher;

    @Inject
    JsonWebToken jwt;

    @Inject
    ScopeValidator scopeValidator;

    @POST
    @Path("/auth")
    @SecurityRequirement(name = "bearerAuth")
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
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid token"),
            @APIResponse(responseCode = "403", description = "Forbidden - missing required scope"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response evaluateAuth(
            @RequestBody(
                    description = "Transaction to evaluate",
                    required = true,
                    content = @Content(schema = @Schema(implementation = TransactionContext.class))
            )
            TransactionContext transaction) {

        LOG.infof("AUTH evaluation request: transactionId=%s", transaction.getTransactionId());

        // Validate M2M token and scope
        scopeValidator.requireM2MWithScope(jwt, "execute:rules");

        return evaluateTransaction(transaction, RuleEvaluator.EVAL_AUTH);
    }

    @POST
    @Path("/monitoring")
    @SecurityRequirement(name = "bearerAuth")
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
            @APIResponse(responseCode = "401", description = "Unauthorized - missing or invalid token"),
            @APIResponse(responseCode = "403", description = "Forbidden - missing required scope"),
            @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response evaluateMonitoring(
            @RequestBody(
                    description = "Transaction to evaluate",
                    required = true,
                    content = @Content(schema = @Schema(implementation = TransactionContext.class))
            )
            TransactionContext transaction) {

        LOG.infof("MONITORING evaluation request: transactionId=%s", transaction.getTransactionId());

        // Validate M2M token and scope
        scopeValidator.requireM2MWithScope(jwt, "execute:rules");

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
        long requestStartTime = System.nanoTime();

        try {
            // Determine ruleset key from transaction type or use default
            String rulesetKey = determineRulesetKey(transaction, evaluationType);

            long lookupStart = System.nanoTime();
            Ruleset ruleset = rulesetRegistry.getRuleset(rulesetKey);
            long lookupEnd = System.nanoTime();
            double lookupTimeMs = (lookupEnd - lookupStart) / 1_000_000.0;

            if (ruleset != null) {
                LOG.debugf("Using ruleset: %s/v%d", rulesetKey, ruleset.getVersion());

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

                // Publish decision event (async)
                decisionPublisher.publishDecision(decision);

                return Response.ok(decision).build();
            }

            // Ruleset not found in cache - this should never happen if startup loading worked
            LOG.errorf("Compiled ruleset not found in registry: %s (was it loaded at startup?)", rulesetKey);

            Decision decision = buildFailOpenDecision(
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

            decisionPublisher.publishDecision(decision);
            return Response.ok(decision).build();

        } catch (Exception e) {
            LOG.errorf(e, "Error during evaluation");

            Decision decision = buildFailOpenDecision(
                    transaction,
                    evaluationType,
                    determineRulesetKey(transaction, evaluationType),
                    "INTERNAL_ERROR",
                    "Internal evaluation error"
            );

            decisionPublisher.publishDecision(decision);
            return Response.ok(decision).build();
        }
    }

    private Decision buildFailOpenDecision(
            TransactionContext transaction,
            String evaluationType,
            String rulesetKey,
            String errorCode,
            String errorMessage) {
        Decision decision = new Decision(transaction.getTransactionId(), evaluationType);
        decision.setDecision(Decision.DECISION_APPROVE);
        decision.setEngineMode(Decision.MODE_FAIL_OPEN);
        decision.setEngineErrorCode(errorCode);
        decision.setEngineErrorMessage(errorMessage);
        decision.setRulesetKey(rulesetKey);
        decision.setTransactionContext(transaction.toEvaluationContext());
        return decision;
    }

    private String determineRulesetKey(TransactionContext transaction, String evaluationType) {
        // Use transaction type to determine ruleset key
        // Default keys based on transaction type and evaluation mode
        String txnType = transaction.getTransactionType();

        if (txnType != null) {
            return switch (txnType.toUpperCase()) {
                case "PURCHASE", "AUTHORIZATION" -> "CARD_" + evaluationType;
                case "REFUND", "REVERSAL" -> "REFUND_" + evaluationType;
                case "TRANSFER" -> "TRANSFER_" + evaluationType;
                default -> "DEFAULT_" + evaluationType;
            };
        }

        return "CARD_" + evaluationType;
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
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get registry status", description = "Get information about loaded rulesets")
    @APIResponse(responseCode = "200", description = "Registry status")
    public Response getRegistryStatus() {
        // Validate M2M token and scope
        scopeValidator.requireM2MWithScope(jwt, "read:metrics");

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
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get rulesets by country", description = "Get all ruleset keys for a country")
    @APIResponse(responseCode = "200", description = "List of ruleset keys")
    public Response getCountryRulesets(@PathParam("country") String country) {
        // Validate M2M token and scope
        scopeValidator.requireM2MWithScope(jwt, "read:metrics");

        Set<String> keys = rulesetRegistry.getRulesetKeys(country);
        return Response.ok(new CountryRulesets(country, keys)).build();
    }

    /**
     * Hot-swaps a ruleset to a new version.
     */
    @POST
    @Path("/rulesets/hotswap")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Hot swap ruleset", description = "Atomically replace a ruleset with a new version")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Hot swap completed"),
            @APIResponse(responseCode = "400", description = "Invalid request"),
            @APIResponse(responseCode = "403", description = "Forbidden - missing scope")
    })
    public Response hotSwapRuleset(HotSwapRequest request) {
        // Validate M2M token and scope
        scopeValidator.requireM2MWithScope(jwt, "execute:rules");

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
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Load ruleset", description = "Load and register a ruleset into the registry")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Ruleset loaded"),
            @APIResponse(responseCode = "400", description = "Invalid request"),
            @APIResponse(responseCode = "404", description = "Ruleset not found")
    })
    public Response loadRuleset(LoadRulesetRequest request) {
        // Validate M2M token and scope
        scopeValidator.requireM2MWithScope(jwt, "execute:rules");

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
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Bulk load rulesets", description = "Load multiple rulesets into the registry")
    @APIResponse(responseCode = "200", description = "Bulk load completed")
    public Response bulkLoadRulesets(BulkLoadRequest request) {
        // Validate M2M token and scope
        scopeValidator.requireM2MWithScope(jwt, "execute:rules");

        if (request.rulesets == null || request.rulesets.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("INVALID_REQUEST", "rulesets list is required"))
                    .build();
        }

        int loaded = rulesetRegistry.bulkLoad(request.rulesets);
        return Response.ok(new BulkLoadResponse(loaded, request.rulesets.size())).build();
    }
}
