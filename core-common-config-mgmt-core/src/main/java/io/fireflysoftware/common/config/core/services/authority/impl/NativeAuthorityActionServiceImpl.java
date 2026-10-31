package io.fireflysoftware.common.config.core.services.authority.impl;

import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.core.services.authority.NativeAuthorityActionService;
import io.fireflysoftware.common.config.interfaces.authority.NativeAuthorityActionSnapshot;
import io.fireflysoftware.common.config.models.repositories.authority.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.time.Clock;
import java.util.*;
import static io.fireflysoftware.common.config.core.authority.InstitutionAuthorityAuthorization.*;

@org.springframework.stereotype.Service
@org.springframework.context.annotation.Profile("!openapi-gen")
public class NativeAuthorityActionServiceImpl implements NativeAuthorityActionService {
    private final InstitutionAuthorityAuthorization authorization;
    private final InstitutionAuthorityTransaction transactions;
    private final NativeActionRepository actions;
    private final NativeAuthorityOperationsRepository operations;
    private final NativeReceiptRepository receipts;
    private final Clock clock;
    public NativeAuthorityActionServiceImpl(InstitutionAuthorityAuthorization authorization,InstitutionAuthorityTransaction transactions,NativeActionRepository actions,NativeReceiptRepository receipts,NativeAuthorityOperationsRepository operations,@org.springframework.beans.factory.annotation.Qualifier("institutionAuthorityClock") Clock clock) {
        this.authorization=Objects.requireNonNull(authorization);this.transactions=Objects.requireNonNull(transactions);this.actions=Objects.requireNonNull(actions);this.receipts=Objects.requireNonNull(receipts);this.operations=Objects.requireNonNull(operations);this.clock=Objects.requireNonNull(clock);
    }
    @Override public Mono<NativeAuthorityActionSnapshot> read(String tenant,String action){return read(tenant,action,false);}
    @Override public Mono<NativeAuthorityActionSnapshot> readAsPlatform(String tenant,String action){return read(tenant,action,true);}
    @Override public Mono<NativeAuthorityActionSnapshot> reconcileGrant(String tenant,String action,byte[] original){return reconcileGrant(tenant,action,original,false);}
    @Override public Mono<NativeAuthorityActionSnapshot> reconcileGrantAsPlatform(String tenant,String action,byte[] original){return reconcileGrant(tenant,action,original,true);}
    private Mono<NativeAuthorityActionSnapshot> reconcileGrant(String tenant,String action,byte[] original,boolean platform) {
        return Mono.defer(()-> {
            var target=NativeAuthorityGrantReconciliationCodec.target(tenant,action,original);var intent=target.intent();var scope=new Scope(tenant,target.institutionId(),ResourceType.ACTION,action,Operation.RECONCILE,intent.canonicalIntent(),intent.intentDigest(),platform);
            return authorization.authorize(scope).flatMap(admission->transactions.execute(admission,action,Math.addExact(clock.millis(),30000),()->operations.reconcileGrant(UUID.fromString(tenant),UUID.fromString(action),original).then(retained(tenant,action))));
        }).retryWhen(reactor.util.retry.Retry.max(2).filter(NativeAuthorityActionServiceImpl::serializationFailure).onRetryExhaustedThrow((spec,signal)->signal.failure())).onErrorMap(NativeAuthorityFailureMapper::translate);
    }
    private Mono<NativeAuthorityActionSnapshot> retained(String tenant,String action) {
        return actions.findByIdTenantIdAndIdActionId(UUID.fromString(tenant),UUID.fromString(action)).flatMap(row->
            receipts.findByIdTenantIdAndIdActionId(UUID.fromString(tenant),UUID.fromString(action)).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Retained native action receipt required"))).map(receipt->NativeAuthorityActionReadVerifier.retained(tenant,action,row,receipt)))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Retained native reconciliation required")));
    }
    private static boolean serializationFailure(Throwable error) {
        for(int depth=0;error!=null && depth<16;depth++,error=error.getCause()) if(error instanceof io.r2dbc.spi.R2dbcException nativeError && "40001".equals(nativeError.getSqlState())) return true;return false;
    }
    private Mono<NativeAuthorityActionSnapshot> read(String tenant,String action,boolean platform) {
        return Mono.defer(()-> {
            var intent=NativeAuthorityActionReadCodec.read(tenant,action);var scope=new Scope(tenant,null,ResourceType.ACTION,action,Operation.READ_ACTION,intent.canonicalIntent(),intent.intentDigest(),platform);
            return authorization.authorize(scope).flatMap(admission->transactions.execute(admission,UUID.randomUUID().toString(),Math.addExact(clock.millis(),30000),()->
                actions.findByIdTenantIdAndIdActionId(UUID.fromString(tenant),UUID.fromString(action)).flatMap(row->
                    receipts.findByIdTenantIdAndIdActionId(UUID.fromString(tenant),UUID.fromString(action)).switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Retained native action receipt required"))).map(receipt->NativeAuthorityActionReadVerifier.retained(tenant,action,row,receipt)))
                    .defaultIfEmpty(NativeAuthorityActionReadVerifier.absent(tenant,action))));
        }).onErrorMap(NativeAuthorityFailureMapper::translate);
    }
}
