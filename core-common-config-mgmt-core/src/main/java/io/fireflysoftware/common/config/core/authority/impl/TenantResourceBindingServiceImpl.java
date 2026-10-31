package io.fireflysoftware.common.config.core.authority.impl;

import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import io.fireflysoftware.common.config.models.entities.tenantbinding.TenantResourceBindingRows;
import io.fireflysoftware.common.config.models.repositories.tenantbinding.TenantResourceBindingStore;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.*;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.function.Supplier;
import static io.fireflysoftware.common.config.core.authority.impl.TenantResourceBindingRowsMapper.*;

public final class TenantResourceBindingServiceImpl implements TenantResourceBindingService {
 private final TenantResourceBindingLookupAuthorization lookups;
 private final InstitutionAuthorityAuthorization governance;
 private final TenantResourceBindingStore store;
 private final TenantResourceBindingNativeAuthority nativeAuthority;
 private final R2dbcTransactionManager manager;
 private final TransactionalOperator transactions;
 public TenantResourceBindingServiceImpl(TenantResourceBindingLookupAuthorization lookups,InstitutionAuthorityAuthorization governance,R2dbcTransactionManager manager,TenantResourceBindingStore store,TenantResourceBindingNativeAuthority nativeAuthority){
  this.lookups=Objects.requireNonNull(lookups);this.governance=Objects.requireNonNull(governance);this.manager=Objects.requireNonNull(manager);this.store=Objects.requireNonNull(store);this.nativeAuthority=Objects.requireNonNull(nativeAuthority);
  if(manager.getConnectionFactory()!=store.connectionFactory()||manager.getConnectionFactory()!=nativeAuthority.connectionFactory())throw new IllegalArgumentException("Binding native authority, persistence and manager must share one ConnectionFactory");
  var definition=new org.springframework.transaction.support.DefaultTransactionDefinition();definition.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);definition.setName("configuration-tenant-resource-binding");this.transactions=TransactionalOperator.create(manager,definition);
 }
 @Override public Mono<Current> current(R2dbcOperationBudget original){
  return lookups.authorize(original).flatMap(admission->lookups.reauthorize(admission,"ACQUIRE").then(Mono.defer(()->own(original,admission::remaining,()->
   nativeAuthority.openLookup(admission).switchIfEmpty(Mono.error(unavailable())).flatMap(window->{
    window(window,admission.namespace(),admission.validUntilEpochMillis());var narrowed=admission.narrowNative(window.validUntilEpochMillis());
    return store.current(window.namespaceDigest()).switchIfEmpty(Mono.error(unavailable())).map(row->TenantResourceBindingRowsMapper.current(row,admission.namespace(),window))
      .flatMap(current->lookups.reauthorize(narrowed,"COMMIT").thenReturn(new LookupResult(current,narrowed))).timeout(narrowed.remaining());
   }))))).flatMap(done->lookups.reauthorize(done.admission(),"RESPONSE").then(Mono.fromSupplier(()->{done.admission().requireCurrent();return done.current();})));
 }
 private record LookupResult(Current current,TenantResourceBindingLookupAuthorization.Admission admission) {}
 private record ControlResult(Completion completion,InstitutionAuthorityAuthorization.Admission admission) {}
 @Override public Mono<Completion> establish(Command command,R2dbcOperationBudget original){return control(command,Operation.TENANT_RESOURCE_BINDING_ESTABLISH,original);}
 @Override public Mono<Completion> revoke(Command command,R2dbcOperationBudget original){return control(command,Operation.TENANT_RESOURCE_BINDING_REVOKE,original);}
 private Mono<Completion> control(Command command,Operation operation,R2dbcOperationBudget original){
  return Mono.defer(()->{
   original.remaining();if(command==null||command.operation()!=operation)throw unavailable();var definition=command.definition();var canonical=TenantResourceBindingCodec.commandCanonical(command);
   if(operation==Operation.TENANT_RESOURCE_BINDING_ESTABLISH&&!command.expectedCurrentEligibility().equals(definition.eligibility()))throw unavailable();
   var scope=new InstitutionAuthorityAuthorization.Scope(definition.resourceTenant().value().toString(),null,InstitutionAuthorityAuthorization.ResourceType.TENANT_RESOURCE_BINDING,definition.bindingId().toString(),InstitutionAuthorityAuthorization.Operation.valueOf(operation.name()),canonical,TenantResourceBindingCodec.commandDigest(command),true);
   return governance.authorize(scope,original).flatMap(admission->governance.reauthorize(admission).then(Mono.defer(()->own(original,admission::remaining,()->
    nativeAuthority.openControl(admission,command).switchIfEmpty(Mono.error(unavailable())).flatMap(open->{
     var window=open.window();window(window,definition.namespace(),admission.validUntilEpochMillis());var nextState=operation==Operation.TENANT_RESOURCE_BINDING_ESTABLISH?State.ACTIVE:State.REVOKED;long nextVersion=command.expectedControlVersion()+1;
     if(!window.reference().equals(TenantResourceBindingCodec.reference(definition))||!window.resourceTenant().equals(definition.resourceTenant())
       ||!window.currentEligibility().equals(command.expectedCurrentEligibility())||window.controlVersion()!=nextVersion||window.state()!=nextState
       ||operation==Operation.TENANT_RESOURCE_BINDING_ESTABLISH&&(!window.eligibleNow()||window.validUntilEpochMillis()>definition.notAfterEpochMillis()))throw unavailable();
     var narrowed=admission.narrowNative(window.validUntilEpochMillis());
     return persist(command,open,nextVersion,nextState).then(Mono.defer(()->{
      narrowed.requireCurrent();if(open.replay()!=null){completion(open.replay(),command,nextVersion,nextState);return Mono.just(open.replay());}
      return nativeAuthority.finishControl(narrowed,command,TenantResourceBindingCodec.reference(definition),nextVersion,nextState).switchIfEmpty(Mono.error(unavailable()));
     })).doOnNext(done->completion(done,command,nextVersion,nextState))
       .flatMap(done->governance.reauthorize(narrowed).then(Mono.fromSupplier(()->{narrowed.requireCurrent();return new ControlResult(done,narrowed);}))).timeout(narrowed.remaining());
    })))).flatMap(done->governance.reauthorize(done.admission()).thenReturn(done.completion())));
  });
 }
 private Mono<Void> persist(Command command,TenantResourceBindingNativeAuthority.Control open,long nextVersion,State nextState){
  var definition=command.definition();var reference=TenantResourceBindingCodec.reference(definition);var namespace=TenantResourceBindingCodec.namespaceDigest(definition.namespace());
  return store.head(namespace).map(Optional::of).defaultIfEmpty(Optional.empty()).flatMap(prior->{
   if(open.replay()!=null){
    var head=prior.orElseThrow(TenantResourceBindingRowsMapper::unavailable);
    if(!head.bindingId().equals(reference.bindingId())||head.definitionVersion()!=reference.version()||!head.definitionDigest().equals(reference.definitionDigest())||head.controlVersion()!=nextVersion||!head.state().equals(nextState.name()))throw unavailable();
    return store.definition(reference.bindingId(),reference.version()).switchIfEmpty(Mono.error(unavailable())).doOnNext(row->{if(!definition.equals(TenantResourceBindingRowsMapper.definition(row)))throw unavailable();}).then();
   }
   var next=new TenantResourceBindingRows.Head(namespace,reference.bindingId(),reference.version(),reference.definitionDigest(),nextVersion,nextState.name());
   Mono<Void> change;
   if(command.operation()==Operation.TENANT_RESOURCE_BINDING_REVOKE){
    var head=prior.orElseThrow(TenantResourceBindingRowsMapper::unavailable);
    if(head.controlVersion()!=command.expectedControlVersion()||!head.bindingId().equals(reference.bindingId())||head.definitionVersion()!=reference.version()||!head.definitionDigest().equals(reference.definitionDigest())||!head.state().equals("ACTIVE"))throw unavailable();
    change=store.definition(reference.bindingId(),reference.version()).switchIfEmpty(Mono.error(unavailable())).doOnNext(row->{if(!definition.equals(TenantResourceBindingRowsMapper.definition(row)))throw unavailable();}).then(Mono.defer(()->store.advance(head,next)));
   }else{
    if(open.originalMakerIssuer()==null||open.originalMakerIssuer().isBlank()||open.originalMakerSubject()==null||open.originalMakerSubject().isBlank())throw unavailable();
    if(prior.isEmpty()){if(command.expectedControlVersion()!=0||reference.version()!=1)throw unavailable();change=Mono.defer(()->store.insertHead(next));}
    else{var head=prior.get();if(head.controlVersion()!=command.expectedControlVersion()||!head.bindingId().equals(reference.bindingId())||reference.version()!=head.definitionVersion()+1)throw unavailable();change=Mono.defer(()->store.advance(head,next));}
    var epoch=definition.eligibility();var row=new TenantResourceBindingRows.Definition(reference.bindingId(),reference.version(),namespace,definition.namespace().issuer(),definition.namespace().platformTenant(),definition.resourceTenant().value(),reference.definitionDigest(),epoch.tenantGeneration(),epoch.statusId(),epoch.statusGeneration(),definition.notAfterEpochMillis(),open.originalMakerIssuer(),open.originalMakerSubject());
    change=store.insertDefinition(row).then(change);
   }
   return change;
  });
 }
 private <T> Mono<T> own(R2dbcOperationBudget original,Supplier<java.time.Duration> remaining,Supplier<Mono<T>> work){
  return Mono.defer(()->{original.remaining();return TransactionSynchronizationManager.forCurrentTransaction().map(TransactionSynchronizationManager::isActualTransactionActive)
   .onErrorResume(NoTransactionException.class,error->Mono.just(false)).flatMap(active->{
    if(active)throw unavailable();var cap=remaining.get();
    return transactions.transactional(Mono.defer(()->TransactionSynchronizationManager.forCurrentTransaction().flatMap(context->{
     if(!context.isActualTransactionActive()||!context.hasResource(manager.getConnectionFactory()))throw unavailable();original.remaining();remaining.get();
     return Mono.defer(work).switchIfEmpty(Mono.error(unavailable())).doOnNext(result->{original.remaining();remaining.get();});
    })).timeout(remaining.get())).timeout(cap);
   });});
 }
}
