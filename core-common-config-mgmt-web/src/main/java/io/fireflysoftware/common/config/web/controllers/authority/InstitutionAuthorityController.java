package io.fireflysoftware.common.config.web.controllers.authority;

import io.fireflysoftware.common.config.core.services.authority.InstitutionAuthorityService;
import io.fireflysoftware.common.config.interfaces.authority.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@io.swagger.v3.oas.annotations.tags.Tag(name="InstitutionAuthority")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/institution-authorities")
public class InstitutionAuthorityController {
    private final InstitutionAuthorityService service;
    public InstitutionAuthorityController(InstitutionAuthorityService service){this.service=service;}
    @DisableIdempotency
    @PostMapping(value="/grant-proposals",consumes="application/json",produces="application/json")
    @Operation(operationId="prepareInstitutionAuthorityGrant")
    @ApiResponses({@ApiResponse(responseCode="200",useReturnTypeSchema=true),@ApiResponse(responseCode="201",useReturnTypeSchema=true)})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=GrantPrepareRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<PreparedInstitutionAuthorityGrant>>> prepare(@PathVariable String tenantId,@RequestBody byte[] original){return mutation(service.prepareGrant(tenantId,original));}
    @DisableIdempotency
    @PostMapping(value="/{institutionId}/proposals/{proposalId}/{decision}",consumes="application/json",produces="application/json")
    @Operation(operationId="decideInstitutionAuthorityGrant")
    @ApiResponses({@ApiResponse(responseCode="200",useReturnTypeSchema=true),@ApiResponse(responseCode="201",useReturnTypeSchema=true)})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=InstitutionAuthorityDecisionRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>>> decide(@PathVariable String tenantId,@PathVariable String institutionId,@PathVariable String proposalId,@PathVariable @Schema(allowableValues={"submit","approve","reject","withdraw"}) String decision,@RequestBody byte[] original){return mutation(service.decideProposal(tenantId,institutionId,proposalId,decision,original));}
    @DisableIdempotency
    @PostMapping(value="/{institutionId}/grant-issues",consumes="application/json",produces="application/json")
    @Operation(operationId="issueInstitutionAuthorityGrant")
    @ApiResponses({@ApiResponse(responseCode="200",useReturnTypeSchema=true),@ApiResponse(responseCode="201",useReturnTypeSchema=true)})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=GrantIssueTargetV2.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<TenantInstitutionGrantDefinition>>> issue(@PathVariable String tenantId,@PathVariable String institutionId,@RequestBody byte[] original){return mutation(service.issueGrant(tenantId,institutionId,original));}
    @GetMapping(value="/{institutionId}/issuers/{issuerAuthorityId}/current-grant",produces="application/json")
    @Operation(operationId="observeCurrentInstitutionAuthorityGrant")
    public Mono<ResponseEntity<InstitutionAuthorityGrantObservation>> observe(@PathVariable String tenantId,@PathVariable String institutionId,@PathVariable String issuerAuthorityId,@RequestParam InstitutionAuthorityUse use){return read(service.observe(tenantId,institutionId,issuerAuthorityId,use));}
    @GetMapping(value="/{institutionId}/issuers/{issuerAuthorityId}/grants/{grantId}/versions/{version}",produces="application/json")
    @Operation(operationId="readHistoricalInstitutionAuthorityGrant")
    public Mono<ResponseEntity<InstitutionAuthorityGrantHistory>> history(@PathVariable String tenantId,@PathVariable String institutionId,@PathVariable String issuerAuthorityId,@PathVariable String grantId,@PathVariable long version){return read(service.history(tenantId,institutionId,issuerAuthorityId,grantId,version));}
    @DisableIdempotency
    @PostMapping(value="/{institutionId}/grant-control-proposals",consumes="application/json",produces="application/json")
    @Operation(operationId="prepareInstitutionAuthorityControl")
    @ApiResponses({@ApiResponse(responseCode="200",useReturnTypeSchema=true),@ApiResponse(responseCode="201",useReturnTypeSchema=true)})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=GrantControlPrepareRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<PreparedInstitutionAuthorityControl>>> prepareControl(@PathVariable String tenantId,@PathVariable String institutionId,@RequestBody byte[] original){return mutation(service.prepareControl(tenantId,institutionId,original));}
    @DisableIdempotency
    @PostMapping(value="/{institutionId}/grants/{grantId}/control-proposals/{proposalId}/{decision}",consumes="application/json",produces="application/json")
    @Operation(operationId="decideInstitutionAuthorityControl")
    @ApiResponses({@ApiResponse(responseCode="200",useReturnTypeSchema=true),@ApiResponse(responseCode="201",useReturnTypeSchema=true)})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=InstitutionAuthorityDecisionRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>>> decideControl(@PathVariable String tenantId,@PathVariable String institutionId,@PathVariable String grantId,@PathVariable String proposalId,@PathVariable @Schema(allowableValues={"submit","approve","reject","withdraw"}) String decision,@RequestBody byte[] original){return mutation(service.decideControlProposal(tenantId,institutionId,grantId,proposalId,decision,original));}
    @DisableIdempotency
    @PostMapping(value="/{institutionId}/grant-controls",consumes="application/json",produces="application/json")
    @Operation(operationId="executeInstitutionAuthorityControl")
    @ApiResponses({@ApiResponse(responseCode="200",useReturnTypeSchema=true),@ApiResponse(responseCode="201",useReturnTypeSchema=true)})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=GrantControlTarget.class)))
    public Mono<ResponseEntity<NativeAuthorityActionResult<GrantAuthorityControlView>>> control(@PathVariable String tenantId,@PathVariable String institutionId,@RequestBody byte[] original){return mutation(service.control(tenantId,institutionId,original));}
    private static <T> Mono<ResponseEntity<NativeAuthorityActionResult<T>>> mutation(Mono<NativeAuthorityActionResult<T>> source){return source.map(value->ResponseEntity.status(value.replay()?HttpStatus.OK:HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(value)).onErrorMap(IllegalArgumentException.class,error->invalid());}
    private static <T> Mono<ResponseEntity<T>> read(Mono<T> source){return source.map(value->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)).onErrorMap(IllegalArgumentException.class,error->invalid());}
    private static ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid institution authority request");}
}
