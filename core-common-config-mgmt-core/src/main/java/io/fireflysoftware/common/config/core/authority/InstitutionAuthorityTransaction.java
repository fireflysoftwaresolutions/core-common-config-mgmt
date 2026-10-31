package io.fireflysoftware.common.config.core.authority;

import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.util.Objects;
import java.util.function.Supplier;

public final class InstitutionAuthorityTransaction {
    private final TransactionalOperator transactions;
    private final InstitutionAuthorityDatabaseContext context;
    public InstitutionAuthorityTransaction(R2dbcTransactionManager manager,R2dbcEntityOperations operations,InstitutionAuthorityDatabaseContext context) {
        if(manager.getConnectionFactory()!=operations.getDatabaseClient().getConnectionFactory() || manager.getConnectionFactory()!=context.connectionFactory()) throw new IllegalArgumentException("Native institution manager/entity operations/context must share one ConnectionFactory");
        this.transactions=TransactionalOperator.create(manager);this.context=Objects.requireNonNull(context);
    }
    public <T> Mono<T> execute(InstitutionAuthorityAuthorization.Admission admission,String actionId,long deadline,Supplier<Mono<T>> work) {
        return TransactionSynchronizationManager.forCurrentTransaction().map(TransactionSynchronizationManager::isActualTransactionActive)
            .onErrorResume(NoTransactionException.class,error->Mono.just(false))
            .flatMap(active-> {
                if(active) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,"Native institution operation requires its own transaction"));
                return Mono.defer(()-> {
                    admission.requireCurrent();
                    return transactions.transactional(Mono.defer(()->context.establish(admission,actionId,deadline)
                        .then(Mono.defer(work)).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Retained native institution result required")))
                        .doOnNext(result->admission.requireCurrent())));
                });
            });
    }
}
