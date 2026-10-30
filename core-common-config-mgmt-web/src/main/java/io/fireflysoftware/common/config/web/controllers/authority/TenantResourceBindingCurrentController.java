package io.fireflysoftware.common.config.web.controllers.authority;

import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Current;
import io.fireflysoftware.common.config.web.tenantbinding.TenantResourceBindingCurrentIngress;
import io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingCurrentBudgetFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.*;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@ConditionalOnProperty(name="firefly.configuration.tenant-resource-binding.enabled",havingValue="true")
@io.swagger.v3.oas.annotations.tags.Tag(name="TenantResourceBindingCurrent")
@SecurityScheme(name="tenantResourceBindingBearer",type=SecuritySchemeType.HTTP,scheme="bearer",bearerFormat="JWT")
@SecurityRequirement(name="tenantResourceBindingBearer")
public final class TenantResourceBindingCurrentController {
    private final TenantResourceBindingCurrentIngress ingress;
    public TenantResourceBindingCurrentController(TenantResourceBindingCurrentIngress ingress){this.ingress=Objects.requireNonNull(ingress);}
    @GetMapping(value=TenantResourceBindingCurrentBudgetFilter.PATH,produces=MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId="observeCurrentTenantResourceBinding")
    @ApiResponses({@ApiResponse(responseCode="200",useReturnTypeSchema=true),@ApiResponse(responseCode="400",description="Unexpected request input"),@ApiResponse(responseCode="401",description="Bearer authentication required"),@ApiResponse(responseCode="403",description="Current permission unavailable"),@ApiResponse(responseCode="503",description="Protected result unavailable")})
    public Mono<ResponseEntity<Current>> current(ServerWebExchange exchange){
        return Mono.defer(()->{
            var original=TenantResourceBindingCurrentBudgetFilter.requireBudget(exchange);original.remaining();
            if(!exchange.getRequest().getQueryParams().isEmpty()||exchange.getRequest().getHeaders().getContentLength()>0||exchange.getRequest().getHeaders().containsHeader(HttpHeaders.TRANSFER_ENCODING))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            return exchange.getRequest().getBody().map(buffer->{org.springframework.core.io.buffer.DataBufferUtils.release(buffer);return Boolean.TRUE;})
                .doOnDiscard(org.springframework.core.io.buffer.DataBuffer.class,org.springframework.core.io.buffer.DataBufferUtils::release).next().defaultIfEmpty(Boolean.FALSE)
                .flatMap(bodyPresent->{
                    original.remaining();if(bodyPresent)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
                    return ingress.current(original).switchIfEmpty(Mono.error(new IllegalStateException("Current tenant resource binding unavailable")))
                        .map(value->{original.remaining();TenantResourceBindingCurrentBudgetFilter.retainCurrent(exchange,value);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);});
                });
        }).onErrorMap(TenantResourceBindingCurrentController::sanitized);
    }
    private static ResponseStatusException sanitized(Throwable error){
        if(error instanceof ResponseStatusException status&&java.util.Set.of(400,401,403).contains(status.getStatusCode().value()))return new ResponseStatusException(status.getStatusCode());
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
    @Schema(name="TenantResourceBindingCurrentFailure") public record Failure(String code){}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Failure> failure(ResponseStatusException error){
        String code=switch(error.getStatusCode().value()){case 400->"INVALID_TENANT_RESOURCE_BINDING_REQUEST";case 401->"TENANT_RESOURCE_BINDING_AUTHENTICATION_REQUIRED";case 403->"TENANT_RESOURCE_BINDING_PERMISSION_UNAVAILABLE";default->"TENANT_RESOURCE_BINDING_UNAVAILABLE";};
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore()).body(new Failure(code));
    }
}
