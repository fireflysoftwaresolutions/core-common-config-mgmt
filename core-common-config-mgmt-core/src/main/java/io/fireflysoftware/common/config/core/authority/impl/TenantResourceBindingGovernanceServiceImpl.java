package io.fireflysoftware.common.config.core.authority.impl;
import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.*;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.function.Function;
import java.util.function.Consumer;
import static io.fireflysoftware.common.config.core.authority.impl.TenantResourceBindingRowsMapper.*;
public final class TenantResourceBindingGovernanceServiceImpl implements TenantResourceBindingGovernanceService {
 private final InstitutionAuthorityAuthorization governance;
 private final R2dbcTransactionManager manager;
 private final TenantResourceBindingNativeGovernance nativeAuthority;
 private final TransactionalOperator transactions;
 public TenantResourceBindingGovernanceServiceImpl(InstitutionAuthorityAuthorization governance,R2dbcTransactionManager manager,TenantResourceBindingNativeGovernance nativeAuthority){
  this.governance=Objects.requireNonNull(governance);this.manager=Objects.requireNonNull(manager);this.nativeAuthority=Objects.requireNonNull(nativeAuthority);
  if(manager.getConnectionFactory()!=nativeAuthority.connectionFactory())throw new IllegalArgumentException("Binding governance must retain its original manager/native factory");
  var definition=new DefaultTransactionDefinition();definition.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);definition.setName("configuration-tenant-resource-binding-governance");this.transactions=TransactionalOperator.create(manager,definition);
 }
 @Override public Mono<GovernanceReceipt> prepare(Preparation preparation,R2dbcOperationBudget original){return Mono.defer(()->{
  Objects.requireNonNull(preparation);var command=preparation.target();var scope=new InstitutionAuthorityAuthorization.Scope(command.definition().resourceTenant().value().toString(),null,InstitutionAuthorityAuthorization.ResourceType.TENANT_RESOURCE_BINDING_PROPOSAL,command.proposalId().toString(),InstitutionAuthorityAuthorization.Operation.TENANT_RESOURCE_BINDING_PREPARE,TenantResourceBindingCodec.preparationCanonical(preparation),TenantResourceBindingCodec.preparationDigest(preparation),true);
  return execute(scope,original,admission->nativeAuthority.prepare(admission,preparation),done->{
   if(!done.actionId().equals(preparation.actionId())||!done.intentDigest().equals(TenantResourceBindingCodec.preparationDigest(preparation))||!done.proposal().proposalId().equals(command.proposalId())||!done.proposal().targetDigest().equals(TenantResourceBindingCodec.commandDigest(command))||done.proposal().controlVersion()!=0||!done.proposal().state().equals("DRAFT")||done.proposal().notAfterEpochMillis()!=preparation.notAfterEpochMillis())throw unavailable();
  });
 });}
 @Override public Mono<GovernanceReceipt> decide(DecisionCommand decision,R2dbcOperationBudget original){return Mono.defer(()->{
  Objects.requireNonNull(decision);var scope=new InstitutionAuthorityAuthorization.Scope(decision.resourceTenant().value().toString(),null,InstitutionAuthorityAuthorization.ResourceType.TENANT_RESOURCE_BINDING_PROPOSAL,decision.proposalId().toString(),InstitutionAuthorityAuthorization.Operation.valueOf("TENANT_RESOURCE_BINDING_"+decision.operation().name()),TenantResourceBindingCodec.decisionCanonical(decision),TenantResourceBindingCodec.decisionDigest(decision),true);
  return execute(scope,original,admission->nativeAuthority.decide(admission,decision),done->{
   String state=switch(decision.operation()){case SUBMIT->"SUBMITTED";case APPROVE->"APPROVED";case REJECT->"REJECTED";case WITHDRAW->"WITHDRAWN";};
   if(!done.actionId().equals(decision.actionId())||!done.intentDigest().equals(TenantResourceBindingCodec.decisionDigest(decision))||!done.proposal().proposalId().equals(decision.proposalId())||!done.proposal().targetDigest().equals(decision.targetDigest())||done.proposal().controlVersion()!=decision.expectedControlVersion()+1||!done.proposal().state().equals(state)||done.proposal().notAfterEpochMillis()!=decision.notAfterEpochMillis())throw unavailable();
  });
 });}
 private record Done(GovernanceReceipt receipt,InstitutionAuthorityAuthorization.Admission admission){}
 private Mono<GovernanceReceipt> execute(InstitutionAuthorityAuthorization.Scope scope,R2dbcOperationBudget original,Function<InstitutionAuthorityAuthorization.Admission,Mono<GovernanceReceipt>> work,Consumer<GovernanceReceipt> validate){
  return governance.authorize(scope,original).flatMap(admission->governance.reauthorize(admission).then(Mono.defer(()->{
   admission.requireCurrent();return TransactionSynchronizationManager.forCurrentTransaction().map(TransactionSynchronizationManager::isActualTransactionActive).onErrorResume(NoTransactionException.class,error->Mono.just(false)).flatMap(active->{
    if(active)throw unavailable();return transactions.transactional(Mono.defer(()->TransactionSynchronizationManager.forCurrentTransaction().flatMap(context->{
     if(!context.isActualTransactionActive()||!context.hasResource(manager.getConnectionFactory()))throw unavailable();admission.requireCurrent();
     return Mono.defer(()->work.apply(admission)).switchIfEmpty(Mono.error(unavailable())).doOnNext(validate).flatMap(receipt->{var narrowed=admission.narrowNative(receipt.nativeUntilEpochMillis());return governance.reauthorize(narrowed).thenReturn(new Done(receipt,narrowed));});
    })).timeout(admission.remaining())).timeout(admission.remaining());
   });
  }))).flatMap(done->governance.reauthorize(done.admission()).then(Mono.fromSupplier(()->{done.admission().requireCurrent();return done.receipt();})));
 }
}
