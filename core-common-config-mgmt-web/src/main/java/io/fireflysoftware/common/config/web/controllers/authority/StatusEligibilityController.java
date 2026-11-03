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

@io.swagger.v3.oas.annotations.tags.Tag(name="StatusEligibility")
@RestController
@io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="Retained receipt replay",useReturnTypeSchema=true),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="Native action committed",useReturnTypeSchema=true)})
@RequestMapping("/api/v1/platform/tenant-statuses/{statusId}/eligibility")
public class StatusEligibilityController {
    private final TenantEligibilityService service;
    public StatusEligibilityController(TenantEligibilityService service){this.service=service;}

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping(value="/suspensions",consumes="application/json",produces="application/json")
    @Operation(operationId="suspendStatusEligibility",summary="Suspend tenant status eligibility")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=StatusEligibilitySuspendRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<StatusEligibilityView>>> suspend(@PathVariable String statusId,@RequestBody byte[] original){return valid(service.suspendStatus(statusId,original));}

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping(value="/transition-proposals",consumes="application/json",produces="application/json")
    @Operation(operationId="prepareStatusEligibilityTransition",summary="Prepare a tenant status eligibility transition")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=StatusEligibilityPrepareRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<PreparedStatusEligibilityTransition>>> prepare(@PathVariable String statusId,@RequestBody byte[] original){return valid(service.prepareStatusTransition(statusId,original));}

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping(value="/transitions",consumes="application/json",produces="application/json")
    @Operation(operationId="executeStatusEligibilityTransition",summary="Execute an approved tenant status eligibility transition")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=StatusEligibilityTransitionTarget.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<StatusEligibilityView>>> transition(@PathVariable String statusId,@RequestBody byte[] original){return valid(service.transitionStatus(statusId,original));}

    @org.fireflyframework.web.idempotency.annotation.DisableIdempotency
    @PostMapping(value="/proposals/{proposalId}/{decision}",consumes="application/json",produces="application/json")
    @Operation(operationId="decideStatusEligibilityProposal",summary="Decide a retained tenant status eligibility proposal")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=InstitutionAuthorityDecisionRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>>> decide(@PathVariable String statusId,@PathVariable String proposalId,@PathVariable @Schema(allowableValues={"submit","approve","reject","withdraw"}) String decision,@RequestBody byte[] original){return valid(service.decideStatusProposal(statusId,proposalId,decision,original));}

    private static <T> Mono<ResponseEntity<NativeAuthorityActionResult<T>>> valid(Mono<NativeAuthorityActionResult<T>> result){return result.map(value->ResponseEntity.status(value.replay()?HttpStatus.OK:HttpStatus.CREATED).body(value)).onErrorMap(IllegalArgumentException.class,error->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid tenant status eligibility request"));}
}
