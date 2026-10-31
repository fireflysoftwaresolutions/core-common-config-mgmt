package io.fireflysoftware.common.config.core.authority;

import org.springframework.http.HttpStatus;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import io.fireflysoftware.common.config.models.repositories.authority.NativeAuthorityOperationsRepository;
import io.r2dbc.spi.ConnectionFactory;
import java.util.UUID;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.util.Objects;

public final class InstitutionAuthorityDatabaseContext {
    private final R2dbcEntityOperations operations;
    private final NativeAuthorityOperationsRepository repository;
    public InstitutionAuthorityDatabaseContext(R2dbcEntityOperations operations,NativeAuthorityOperationsRepository repository) {
        this.operations=Objects.requireNonNull(operations);this.repository=Objects.requireNonNull(repository);
    }
    ConnectionFactory connectionFactory(){return operations.getDatabaseClient().getConnectionFactory();}
    private static UUID uuid(String value){return value==null?null:UUID.fromString(value);}

    public Mono<Void> establish(InstitutionAuthorityAuthorization.Admission admission,String actionId,long commandDeadline) {
        return TransactionSynchronizationManager.forCurrentTransaction()
            .onErrorMap(NoTransactionException.class,error->unavailable())
            .flatMap(manager-> {
                if(!manager.isActualTransactionActive() || !manager.hasResource(connectionFactory())) return Mono.error(unavailable());
                return Mono.defer(()-> {
                    admission.requireCurrent();AuthorityWire.uuid(actionId);
                    var scope=admission.scope();var actor=admission.actor();
                    if(scope.operation()==InstitutionAuthorityAuthorization.Operation.GRANT_ISSUE) {
                        var prefix=AuthorityWire.token("FF_TENANT_INSTITUTION_GRANT_V2")+"R3:"+AuthorityWire.token(scope.tenantId())+AuthorityWire.token("GRANT_ISSUE")+"R2:R13:"+AuthorityWire.token(actionId);
                        if(!scope.canonicalIntent().startsWith(prefix)) return Mono.error(denied());
                    }
                    if(commandDeadline>253402300799999L || commandDeadline < -2208988800000L) return Mono.error(denied());
                    return repository.environment(scope.operation().name())
                        .filter(Boolean.TRUE::equals).switchIfEmpty(Mono.error(unavailable()))
                        .then(Mono.defer(()-> {
                            admission.requireCurrent();
                            // Native effects enforce their retained deadline; receipt replay uses fresh current admission.
                            return repository.establishContext(UUID.fromString(scope.tenantId()),uuid(scope.institutionId()),scope.operation().name(),scope.resourceType().name(),UUID.fromString(scope.resourceId()),UUID.fromString(actionId),scope.intentDigest(),actor.kind(),actor.issuer(),actor.subject(),UUID.fromString(actor.tenantId()),uuid(actor.employeeId()),actor.clientId(),scope.platformRecovery(),admission.validUntilEpochMillis(),admission.validUntilEpochMillis())
                                .filter(Boolean.TRUE::equals).switchIfEmpty(Mono.error(unavailable())).then();
                        }));
                });
            }).onErrorMap(error->error instanceof ResponseStatusException?error:unavailable());
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Restricted native institution transaction required");}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"Exact native institution intent required");}
}
