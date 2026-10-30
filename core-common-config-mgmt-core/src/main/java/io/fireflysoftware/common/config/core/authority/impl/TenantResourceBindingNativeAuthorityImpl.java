package io.fireflysoftware.common.config.core.authority.impl;
import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import io.fireflysoftware.common.config.models.repositories.tenantbinding.TenantResourceBindingNativeRepository;
import io.r2dbc.spi.ConnectionFactory;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static io.fireflysoftware.common.config.core.authority.impl.TenantResourceBindingRowsMapper.*;

/** Calls real native entrypoints; Java never creates a successful authority window or completion. */
public final class TenantResourceBindingNativeAuthorityImpl implements TenantResourceBindingNativeAuthority,TenantResourceBindingNativeGovernance {
 private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65536).maxNumberLength(64).build()).build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
 private final R2dbcEntityTemplate template;
 private final TenantResourceBindingNativeRepository repository;
 public TenantResourceBindingNativeAuthorityImpl(R2dbcEntityTemplate template,TenantResourceBindingNativeRepository repository){this.template=Objects.requireNonNull(template);this.repository=Objects.requireNonNull(repository);}
 @Override public ConnectionFactory connectionFactory(){return template.getDatabaseClient().getConnectionFactory();}
 @Override public Mono<Window> openLookup(TenantResourceBindingLookupAuthorization.Admission admission){
  return Mono.defer(()->inside(admission::requireCurrent,()->repository.openLookup(admission.namespace().issuer(),admission.subject(),admission.namespace().platformTenant(),admission.validUntilEpochMillis()))
   .map(raw->{var result=decode(raw,Window.class);window(result,admission.namespace(),admission.validUntilEpochMillis());admission.narrowNative(result.validUntilEpochMillis()).requireCurrent();return result;}).timeout(admission.remaining()));
 }
 @Override public Mono<Control> openControl(InstitutionAuthorityAuthorization.Admission admission,Command command){
  return Mono.defer(()->inside(admission::requireCurrent,()->{
   scope(admission,command);var actor=admission.actor();return repository.openControl(original(command),actor.kind(),actor.issuer(),actor.subject(),UUID.fromString(actor.tenantId()),actor.employeeId()==null?null:UUID.fromString(actor.employeeId()),actor.clientId(),admission.validUntilEpochMillis());
  }).map(raw->{var result=decode(raw,Control.class);var current=result.window();window(current,command.definition().namespace(),admission.validUntilEpochMillis());
    if(!current.reference().equals(TenantResourceBindingCodec.reference(command.definition()))||!current.resourceTenant().equals(command.definition().resourceTenant())||!current.currentEligibility().equals(command.expectedCurrentEligibility()))throw unavailable();
    admission.narrowNative(current.validUntilEpochMillis()).requireCurrent();return result;}).timeout(admission.remaining()));
 }
 @Override public Mono<Completion> finishControl(InstitutionAuthorityAuthorization.Admission admission,Command command,Reference reference,long controlVersion,State state){
  return Mono.defer(()->inside(admission::requireCurrent,()->{scope(admission,command);if(!TenantResourceBindingCodec.reference(command.definition()).equals(reference)||controlVersion!=command.expectedControlVersion()+1)throw unavailable();return repository.finishControl(original(command));})
   .map(raw->{var result=decode(raw,Completion.class);completion(result,command,controlVersion,state);admission.requireCurrent();return result;}).timeout(admission.remaining()));
 }

 @Override public Mono<GovernanceReceipt> prepare(InstitutionAuthorityAuthorization.Admission admission,Preparation preparation){
  return governance(admission,preparation,TenantResourceBindingCodec.preparationCanonical(preparation),TenantResourceBindingCodec.preparationDigest(preparation),preparation.target().definition().resourceTenant(),preparation.target().proposalId(),"TENANT_RESOURCE_BINDING_PREPARE");
 }
 @Override public Mono<GovernanceReceipt> decide(InstitutionAuthorityAuthorization.Admission admission,DecisionCommand decision){
  return governance(admission,decision,TenantResourceBindingCodec.decisionCanonical(decision),TenantResourceBindingCodec.decisionDigest(decision),decision.resourceTenant(),decision.proposalId(),"TENANT_RESOURCE_BINDING_"+decision.operation().name());
 }
 private Mono<GovernanceReceipt> governance(InstitutionAuthorityAuthorization.Admission admission,Object request,String canonical,String digest,ResourceTenant tenant,UUID proposal,String operation){
  return Mono.defer(()->inside(admission::requireCurrent,()->{
   var scope=admission.scope();if(admission.originalBudget()==null||scope.resourceType()!=InstitutionAuthorityAuthorization.ResourceType.TENANT_RESOURCE_BINDING_PROPOSAL||!scope.platformRecovery()||!scope.resourceId().equals(proposal.toString())||!scope.tenantId().equals(tenant.value().toString())||!scope.operation().name().equals(operation)||!scope.canonicalIntent().equals(canonical)||!scope.intentDigest().equals(digest))throw unavailable();
   var actor=admission.actor();if(!actor.kind().equals("STAFF"))throw unavailable();byte[] bytes=original(request);
   return request instanceof Preparation?repository.prepare(bytes,actor.kind(),actor.issuer(),actor.subject(),UUID.fromString(actor.tenantId()),UUID.fromString(actor.employeeId()),actor.clientId(),admission.validUntilEpochMillis()):repository.decide(bytes,actor.kind(),actor.issuer(),actor.subject(),UUID.fromString(actor.tenantId()),UUID.fromString(actor.employeeId()),actor.clientId(),admission.validUntilEpochMillis());
  }).map(raw->{var done=decode(raw,GovernanceReceipt.class);if(!done.intentDigest().equals(digest)||!done.proposal().proposalId().equals(proposal))throw unavailable();admission.narrowNative(done.nativeUntilEpochMillis()).requireCurrent();return done;}).timeout(admission.remaining()));
 }
 private <T> Mono<T> inside(Runnable current,Supplier<Mono<T>> call){
  return Mono.defer(()->{current.run();return TransactionSynchronizationManager.forCurrentTransaction().flatMap(context->{
    if(!context.isActualTransactionActive()||!context.hasResource(connectionFactory()))throw unavailable();current.run();return Mono.defer(call).switchIfEmpty(Mono.error(unavailable())).doOnNext(result->current.run());
   });});
 }
 private static byte[] original(Object command){try{return JSON.writeValueAsBytes(command);}catch(Exception error){throw unavailable();}}
 private static <T> T decode(String raw,Class<T> type){try{if(raw==null||raw.getBytes(StandardCharsets.UTF_8).length>65536)throw unavailable();return JSON.readValue(raw,type);}catch(Exception error){throw unavailable();}}
 private static void scope(InstitutionAuthorityAuthorization.Admission admission,Command command){
  var scope=admission.scope();if(admission.originalBudget()==null||command==null||scope.resourceType()!=InstitutionAuthorityAuthorization.ResourceType.TENANT_RESOURCE_BINDING||!scope.platformRecovery()
   ||!scope.resourceId().equals(command.definition().bindingId().toString())||!scope.tenantId().equals(command.definition().resourceTenant().value().toString())
   ||!scope.operation().name().equals(command.operation().name())||!scope.canonicalIntent().equals(TenantResourceBindingCodec.commandCanonical(command))||!scope.intentDigest().equals(TenantResourceBindingCodec.commandDigest(command)))throw unavailable();
 }
}
