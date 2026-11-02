package io.fireflysoftware.common.config.core.authority.impl;

import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingGovernedContracts.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import reactor.core.publisher.Mono;
import java.util.Objects;
import static io.fireflysoftware.common.config.core.authority.TenantResourceBindingGovernedCodec.*;

public final class TenantResourceBindingGovernedActionsImpl implements TenantResourceBindingGovernedActions {
    private final InstitutionAuthorityAuthorization authority;
    private final TenantResourceBindingGovernanceService governance;
    private final TenantResourceBindingService controls;
    private final TenantResourceBindingRecoveryReader recovery;
    public TenantResourceBindingGovernedActionsImpl(InstitutionAuthorityAuthorization authority,TenantResourceBindingGovernanceService governance,TenantResourceBindingService controls,TenantResourceBindingRecoveryReader recovery){this.authority=Objects.requireNonNull(authority);this.governance=Objects.requireNonNull(governance);this.controls=Objects.requireNonNull(controls);this.recovery=Objects.requireNonNull(recovery);}
    @Override public Mono<PreparedIntent> prepareIntent(Intent intent,R2dbcOperationBudget original){
        return Mono.defer(()->{original.remaining();var prepared=new PreparedIntent(action(intent),digest(intent));return authority.authorize(scope(intent),original).flatMap(admission->authority.reauthorize(admission).then(Mono.fromSupplier(()->{admission.requireCurrent();return prepared;})));}).timeout(Mono.defer(()->Mono.delay(original.remaining())));
    }
    @Override public Mono<ActionResult> execute(ActionRequest request,R2dbcOperationBudget original){
        return Mono.defer(()->{
            original.remaining();original(request);var intent=request.intent();var unknown=new ActionResult(ActionStatus.OUTCOME_UNKNOWN,action(intent),request.originalIntentDigest(),null,null);var committed=new java.util.concurrent.atomic.AtomicBoolean();
            return authority.authorize(scope(intent),original).flatMap(admission->authority.reauthorize(admission).then(Mono.defer(()->{
                admission.requireCurrent();
                // The observed owner commit survives loss of response authority; the protected receipt does not.
                return dispatch(intent,original).switchIfEmpty(Mono.error(new IllegalStateException("Original binding outcome unavailable"))).doOnNext(result->committed.set(true)).flatMap(result->authority.reauthorize(admission).then(Mono.fromSupplier(()->{admission.requireCurrent();return result;})))
                    .timeout(admission.remaining()).onErrorResume(error->Mono.just(committed.get()?new ActionResult(ActionStatus.COMMITTED_RESPONSE_UNAVAILABLE,action(intent),request.originalIntentDigest(),null,null):unknown));
            })));
        });
    }
    private Mono<ActionResult> dispatch(Intent intent,R2dbcOperationBudget original){
        return switch(intent.kind()){
            case PREPARATION->governance.prepare(intent.preparation(),original).map(receipt->governanceResult(intent,receipt));
            case DECISION->governance.decide(intent.decision(),original).map(receipt->governanceResult(intent,receipt));
            case CONTROL->{var call=intent.control().operation()==Operation.TENANT_RESOURCE_BINDING_ESTABLISH?controls.establish(intent.control(),original):controls.revoke(intent.control(),original);yield call.map(completion->{if(!completion.actionId().equals(action(intent))||!completion.intentDigest().equals(digest(intent)))throw new IllegalStateException("Original binding completion required");return new ActionResult(ActionStatus.COMMITTED,action(intent),digest(intent),null,completion);});}
        };
    }
    private ActionResult governanceResult(Intent intent,GovernanceReceipt receipt){if(!receipt.actionId().equals(action(intent))||!receipt.intentDigest().equals(digest(intent)))throw new IllegalStateException("Original binding governance receipt required");return new ActionResult(ActionStatus.COMMITTED,action(intent),digest(intent),receipt,null);}
    @Override public Mono<Recovery> recover(ActionRequest request,R2dbcOperationBudget original){return Mono.defer(()->{original.remaining();original(request);return recovery.recover(request,original);});}
}
