package io.fireflysoftware.common.config.web.controllers.authority;

import io.fireflysoftware.common.config.core.services.authority.TenantEligibilityService;
import io.fireflysoftware.common.config.interfaces.authority.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@io.swagger.v3.oas.annotations.tags.Tag(name="PlatformTenantEligibility")
@RestController
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Retained receipt replay",useReturnTypeSchema=true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="Native action committed",useReturnTypeSchema=true)})
@RequestMapping("/api/v1/platform/tenants/{tenantId}/eligibility")
public class PlatformTenantEligibilityController {
    private final TenantEligibilityService service;
    public PlatformTenantEligibilityController(TenantEligibilityService service){this.service=service;}

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping(value="/suspensions",consumes="application/json",produces="application/json")
    @Operation(operationId="suspendPlatformTenantEligibility",summary="Suspend Tenant eligibility")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=TenantEligibilitySuspendRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<TenantEligibilityView>>> suspend(@PathVariable String tenantId,@RequestBody byte[] original){return valid(service.suspendTenantAsPlatform(tenantId,original));}

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping(value="/transition-proposals",consumes="application/json",produces="application/json")
    @Operation(operationId="preparePlatformTenantEligibilityTransition",summary="Prepare a Tenant eligibility transition")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=TenantEligibilityPrepareRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<PreparedTenantEligibilityTransition>>> prepare(@PathVariable String tenantId,@RequestBody byte[] original){return valid(service.prepareTenantTransitionAsPlatform(tenantId,original));}

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping(value="/transitions",consumes="application/json",produces="application/json")
    @Operation(operationId="executePlatformTenantEligibilityTransition",summary="Execute an approved Tenant eligibility transition")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=TenantEligibilityTransitionTarget.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<TenantEligibilityView>>> transition(@PathVariable String tenantId,@RequestBody byte[] original){return valid(service.transitionTenantAsPlatform(tenantId,original));}

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping(value="/proposals/{proposalId}/{decision}",consumes="application/json",produces="application/json")
    @Operation(operationId="decidePlatformTenantEligibilityProposal",summary="Decide a retained Tenant eligibility proposal")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=InstitutionAuthorityDecisionRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>>> decide(@PathVariable String tenantId,@PathVariable String proposalId,@PathVariable @Schema(allowableValues={"submit","approve","reject","withdraw"}) String decision,@RequestBody byte[] original){return valid(service.decideTenantProposalAsPlatform(tenantId,proposalId,decision,original));}

    private static <T> Mono<ResponseEntity<NativeAuthorityActionResult<T>>> valid(Mono<NativeAuthorityActionResult<T>> result){return result.map(value->ResponseEntity.status(value.replay()?HttpStatus.OK:HttpStatus.CREATED).body(value)).onErrorMap(IllegalArgumentException.class,error->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Tenant eligibility request"));}
}
