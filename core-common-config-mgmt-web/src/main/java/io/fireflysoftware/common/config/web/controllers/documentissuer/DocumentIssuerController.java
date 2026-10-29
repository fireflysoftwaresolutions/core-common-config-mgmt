/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.web.controllers.documentissuer;

import io.fireflysoftware.common.config.core.authority.DocumentIssuerCodec;
import io.fireflysoftware.common.config.core.services.documentissuer.DocumentIssuerService;
import io.fireflysoftware.common.config.interfaces.documentissuer.DocumentIssuerContracts.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.security.*;
import java.util.function.Supplier;
import org.fireflyframework.core.transaction.RootTransactionException;
import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/document-issuers")
@ConditionalOnProperty(prefix="firefly.configuration.document-issuer",name="enabled",havingValue="true")
@io.swagger.v3.oas.annotations.tags.Tag(name="DocumentIssuer")
@SecurityScheme(name="documentIssuerBearer",type=SecuritySchemeType.HTTP,scheme="bearer",bearerFormat="JWT")
@SecurityRequirement(name="documentIssuerBearer")
@ApiResponses({@ApiResponse(responseCode="200",useReturnTypeSchema=true),@ApiResponse(responseCode="400",description="Invalid request"),@ApiResponse(responseCode="403",description="Current permission unavailable"),@ApiResponse(responseCode="503",description="Protected result unavailable")})
public class DocumentIssuerController {
    private final DocumentIssuerService service;
    public DocumentIssuerController(DocumentIssuerService service){this.service=java.util.Objects.requireNonNull(service);}

    @DisableIdempotency
    @PostMapping(value="/source-observations",consumes="application/json",produces="application/json")
    @Operation(operationId="observeDocumentIssuerSource")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=SourceRequest.class)))
    public Mono<ResponseEntity<SourceObservation>> source(@PathVariable String tenantId,@RequestBody byte[] original){return read(()->service.observeSource(tenantId,original));}

    @DisableIdempotency
    @PostMapping(value="/preparations",consumes="application/json",produces="application/json")
    @Operation(operationId="prepareDocumentIssuer")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=Prepare.class)))
    public Mono<ResponseEntity<Receipt>> prepare(@PathVariable String tenantId,@RequestBody byte[] original){return read(()->service.prepare(tenantId,original));}

    @DisableIdempotency
    @PostMapping(value="/publications",consumes="application/json",produces="application/json")
    @Operation(operationId="publishDocumentIssuer")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=Decision.class)))
    public Mono<ResponseEntity<Receipt>> publish(@PathVariable String tenantId,@RequestBody byte[] original){return read(()->service.publish(tenantId,original));}

    @DisableIdempotency
    @PostMapping(value="/revocations",consumes="application/json",produces="application/json")
    @Operation(operationId="revokeDocumentIssuer")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=Decision.class)))
    public Mono<ResponseEntity<Receipt>> revoke(@PathVariable String tenantId,@RequestBody byte[] original){return read(()->service.revoke(tenantId,original));}

    @DisableIdempotency
    @PostMapping(value="/current-observations",consumes="application/json",produces="application/json")
    @Operation(operationId="observeCurrentDocumentIssuer")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=PublicationReference.class)))
    public Mono<ResponseEntity<Observation>> current(@PathVariable String tenantId,@RequestBody byte[] original){return read(()->service.current(DocumentIssuerCodec.decodePublicationReference(tenantId,original)));}

    @DisableIdempotency
    @PostMapping(value="/preparation-recoveries",consumes="application/json",produces="application/json")
    @Operation(operationId="recoverDocumentIssuerPreparation")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=Prepare.class)))
    public Mono<ResponseEntity<Receipt>> recoverPrepare(@PathVariable String tenantId,@RequestBody byte[] original){return read(()->service.recoverPrepare(tenantId,original));}

    @DisableIdempotency
    @PostMapping(value="/publication-recoveries",consumes="application/json",produces="application/json")
    @Operation(operationId="recoverDocumentIssuerPublication")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=Decision.class)))
    public Mono<ResponseEntity<Receipt>> recoverPublish(@PathVariable String tenantId,@RequestBody byte[] original){return read(()->service.recoverPublish(tenantId,original));}

    @DisableIdempotency
    @PostMapping(value="/revocation-recoveries",consumes="application/json",produces="application/json")
    @Operation(operationId="recoverDocumentIssuerRevocation")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",schema=@Schema(implementation=Decision.class)))
    public Mono<ResponseEntity<Receipt>> recoverRevoke(@PathVariable String tenantId,@RequestBody byte[] original){return read(()->service.recoverRevoke(tenantId,original));}

    private static <T> Mono<ResponseEntity<T>> read(Supplier<Mono<T>> source){return Mono.defer(source).switchIfEmpty(Mono.error(new IllegalStateException("DOCUMENT_ISSUER_SOURCE_UNAVAILABLE")))
        .map(value->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)).onErrorMap(RuntimeException.class,DocumentIssuerController::unavailable);}
    private static ResponseStatusException unavailable(RuntimeException error){
        if(error instanceof RootTransactionException)return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Document issuer unavailable");
        if(error instanceof IllegalArgumentException)return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid document issuer request");
        if("DOCUMENT_ISSUER_AUTHORITY_UNAVAILABLE".equals(error.getMessage()))return new ResponseStatusException(HttpStatus.FORBIDDEN,"Document issuer permission unavailable");
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Document issuer unavailable");
    }
    @Schema(name="DocumentIssuerFailure") public record Failure(String code){}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Failure> failure(ResponseStatusException error){String code=error.getStatusCode().value()==400?"INVALID_DOCUMENT_ISSUER_REQUEST":error.getStatusCode().value()==403?"DOCUMENT_ISSUER_PERMISSION_UNAVAILABLE":"DOCUMENT_ISSUER_UNAVAILABLE";return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore()).body(new Failure(code));}
}
