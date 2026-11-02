package io.fireflysoftware.common.config.web.controllers.authority;

import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingGovernedContracts.*;
import io.fireflysoftware.common.config.web.tenantbinding.impl.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.util.Objects;
import static io.fireflysoftware.common.config.core.authority.TenantResourceBindingGovernedCodec.*;

@RestController
@ConditionalOnProperty(prefix="firefly.configuration.tenant-resource-binding",name={"enabled","governance-http-enabled"},havingValue="true")
@RequestMapping(TenantResourceBindingGovernanceBudgetFilterImpl.BASE)
@io.swagger.v3.oas.annotations.tags.Tag(name="TenantResourceBindingGovernance")
@SecurityRequirement(name="tenantResourceBindingBearer")
public final class TenantResourceBindingGovernanceController {
    private final ObjectProvider<TenantResourceBindingGovernedActions> owners;
    public TenantResourceBindingGovernanceController(ObjectProvider<TenantResourceBindingGovernedActions> owners){this.owners=Objects.requireNonNull(owners);}
    @DisableIdempotency
    @PostMapping(value="/intents",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="prepareTenantResourceBindingIntent",description="Read-only owner action/digest preparation. Grants no authority and creates no native proposal or reservation.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=Intent.class)))
    @ApiResponse(responseCode="200",useReturnTypeSchema=true)
    public Mono<ResponseEntity<PreparedIntent>> prepare(@RequestBody byte[] body,ServerWebExchange exchange){return Mono.defer(()->{
        var budget=budget(exchange);var intent=TenantResourceBindingGovernanceRequestCodecImpl.intent(body);var expected=new PreparedIntent(action(intent),digest(intent));return owner().prepareIntent(intent,budget).switchIfEmpty(Mono.error(unavailable())).map(result->{if(!expected.equals(result))throw unavailable();budget.remaining();return response(result);});
    });}
    @DisableIdempotency
    @PostMapping(value="/actions",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="executeTenantResourceBindingAction",description="Executes exactly the retained owner intent once. PREPARATION creates the real native draft; DECISION submits or independently checks it; CONTROL establishes or revokes. OUTCOME_UNKNOWN requires original-action observation, never automatic replacement.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=ActionRequest.class)))
    @ApiResponse(responseCode="200",useReturnTypeSchema=true)
    public Mono<ResponseEntity<ActionResult>> execute(@RequestBody byte[] body,ServerWebExchange exchange){return Mono.defer(()->{
        var budget=budget(exchange);var request=TenantResourceBindingGovernanceRequestCodecImpl.action(body);original(request);var owner=owner();TenantResourceBindingGovernanceBudgetFilterImpl.retainAction(exchange,request);
        var unknown=new ActionResult(ActionStatus.OUTCOME_UNKNOWN,action(request.intent()),request.originalIntentDigest(),null,null);
        return Mono.defer(()->owner.execute(request,budget)).switchIfEmpty(Mono.just(unknown)).map(result->{if(!result.actionId().equals(unknown.actionId())||!result.intentDigest().equals(unknown.intentDigest()))return unknown;if(result.status()!=ActionStatus.OUTCOME_UNKNOWN)TenantResourceBindingGovernanceBudgetFilterImpl.retainKnownCommit(exchange,result);return result;})
            .onErrorResume(error->error instanceof ResponseStatusException status&&java.util.Set.of(401,403).contains(status.getStatusCode().value())&&!TenantResourceBindingGovernanceBudgetFilterImpl.observedCommit(exchange)?Mono.error(new ResponseStatusException(status.getStatusCode())):Mono.just(TenantResourceBindingGovernanceBudgetFilterImpl.unavailableAction(exchange)))
            .map(result->{if(result.status()==ActionStatus.COMMITTED){try{budget.remaining();}catch(RuntimeException expired){result=TenantResourceBindingGovernanceBudgetFilterImpl.unavailableAction(exchange);}}if(result.status()!=ActionStatus.COMMITTED)TenantResourceBindingGovernanceBudgetFilterImpl.retainUnavailable(exchange);return response(result);});
    });}
    @DisableIdempotency
    @PostMapping(value="/actions/observations",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="observeTenantResourceBindingAction",description="Read-only recovery of the exact original action under current platform action-read authority and retained caller identity. Historical receipt or absence at this read grants no current binding or replacement mutation.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType=MediaType.APPLICATION_JSON_VALUE,schema=@Schema(implementation=ActionRequest.class)))
    @ApiResponse(responseCode="200",useReturnTypeSchema=true)
    public Mono<ResponseEntity<Recovery>> recover(@RequestBody byte[] body,ServerWebExchange exchange){return Mono.defer(()->{
        var budget=budget(exchange);var request=TenantResourceBindingGovernanceRequestCodecImpl.action(body);original(request);return owner().recover(request,budget).switchIfEmpty(Mono.error(unavailable())).map(result->{if(!result.actionId().equals(action(request.intent()))||!result.intentDigest().equals(request.originalIntentDigest()))throw unavailable();budget.remaining();return response(result);});
    });}
    private static org.fireflyframework.core.transaction.R2dbcOperationBudget budget(ServerWebExchange exchange){var budget=TenantResourceBindingGovernanceBudgetFilterImpl.requireBudget(exchange);budget.remaining();if(!exchange.getRequest().getQueryParams().isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST);return budget;}
    private TenantResourceBindingGovernedActions owner(){var owner=owners.getIfAvailable();if(owner==null)throw unavailable();return owner;}
    private static <T> ResponseEntity<T> response(T value){return ResponseEntity.ok().header(HttpHeaders.VARY,HttpHeaders.AUTHORIZATION).cacheControl(CacheControl.noStore().cachePrivate()).body(value);}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}
    @Schema(name="TenantResourceBindingGovernanceFailure") public record Failure(String code){}
    @ExceptionHandler({ResponseStatusException.class,IllegalArgumentException.class,IllegalStateException.class})
    public ResponseEntity<Failure> failure(Exception error,ServerWebExchange exchange){var status=error instanceof ResponseStatusException explicit&&java.util.Set.of(400,401,403).contains(explicit.getStatusCode().value())?explicit.getStatusCode():error instanceof IllegalArgumentException?HttpStatus.BAD_REQUEST:HttpStatus.SERVICE_UNAVAILABLE;TenantResourceBindingGovernanceBudgetFilterImpl.retainRefusal(exchange,status);return ResponseEntity.status(status).header(HttpHeaders.VARY,HttpHeaders.AUTHORIZATION).cacheControl(CacheControl.noStore().cachePrivate()).body(new Failure(status.value()==400?"INVALID_TENANT_RESOURCE_BINDING_GOVERNANCE_REQUEST":status.value()==401?"TENANT_RESOURCE_BINDING_AUTHENTICATION_REQUIRED":status.value()==403?"TENANT_RESOURCE_BINDING_PERMISSION_UNAVAILABLE":"TENANT_RESOURCE_BINDING_GOVERNANCE_UNAVAILABLE"));}
}
