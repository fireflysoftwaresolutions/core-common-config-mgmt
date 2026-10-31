package io.fireflysoftware.common.config.web.controllers.authority;

import io.fireflysoftware.common.config.core.services.authority.NativeAuthorityActionService;
import io.fireflysoftware.common.config.interfaces.authority.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@io.swagger.v3.oas.annotations.tags.Tag(name="InstitutionAuthority")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/institution-authorities/actions")
public class NativeAuthorityActionController {
    private final NativeAuthorityActionService service;
    public NativeAuthorityActionController(NativeAuthorityActionService service){this.service=service;}
    @GetMapping(value="/{actionId}",produces="application/json")
    @Operation(operationId="readInstitutionAuthorityAction")
    public Mono<ResponseEntity<NativeAuthorityActionSnapshot>> read(@PathVariable String tenantId,@PathVariable String actionId){return response(service.read(tenantId,actionId));}
    @DisableIdempotency
    @PostMapping(value="/{actionId}/reconciliations",consumes="application/json",produces="application/json")
    @Operation(operationId="reconcileInstitutionAuthorityAction")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=NativeGrantActionReconciliationRequest.class)))
    public Mono<ResponseEntity<NativeAuthorityActionSnapshot>> reconcile(@PathVariable String tenantId,@PathVariable String actionId,@RequestBody byte[] original){return response(service.reconcileGrant(tenantId,actionId,original));}
    private static Mono<ResponseEntity<NativeAuthorityActionSnapshot>> response(Mono<NativeAuthorityActionSnapshot> source){return source.map(value->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)).onErrorMap(IllegalArgumentException.class,error->new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid native action request"));}
}
