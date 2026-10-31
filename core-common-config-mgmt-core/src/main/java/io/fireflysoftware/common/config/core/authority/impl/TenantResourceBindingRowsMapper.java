package io.fireflysoftware.common.config.core.authority.impl;
import io.fireflysoftware.common.config.core.authority.TenantResourceBindingCodec;
import io.fireflysoftware.common.config.core.authority.TenantResourceBindingNativeAuthority.Window;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import io.fireflysoftware.common.config.models.entities.tenantbinding.TenantResourceBindingRows;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Objects;
final class TenantResourceBindingRowsMapper {
 private TenantResourceBindingRowsMapper() {}
 static Definition definition(TenantResourceBindingRows.Definition row){
  var value=new Definition(row.bindingId(),new Namespace(row.issuer(),row.platformTenant()),new ResourceTenant(row.resourceTenantId()),row.version(),new Eligibility(row.tenantGeneration(),row.statusId(),row.statusGeneration()),row.notAfterEpochMillis());
  if(!TenantResourceBindingCodec.namespaceDigest(value.namespace()).equals(row.namespaceDigest())||!TenantResourceBindingCodec.definitionDigest(value).equals(row.definitionDigest()))throw unavailable();return value;
 }
 static Current current(TenantResourceBindingRows.Snapshot row,Namespace namespace,Window window){
  var value=new Definition(row.bindingId(),new Namespace(row.issuer(),row.platformTenant()),new ResourceTenant(row.resourceTenantId()),row.definitionVersion(),new Eligibility(row.tenantGeneration(),row.statusId(),row.statusGeneration()),row.notAfterEpochMillis());
  var reference=TenantResourceBindingCodec.reference(value);var live=new Eligibility(row.liveTenantGeneration(),row.liveStatusId(),row.liveStatusGeneration());
  if(!namespace.equals(value.namespace())||!TenantResourceBindingCodec.namespaceDigest(namespace).equals(row.namespaceDigest())||!row.definitionDigest().equals(reference.definitionDigest())
    ||!"ACTIVE".equals(row.state())||!row.eligibleNow()||!value.eligibility().equals(live)||!live.equals(window.currentEligibility())
    ||!window.eligibleNow()||!reference.equals(window.reference())||!value.resourceTenant().equals(window.resourceTenant())||window.controlVersion()!=row.controlVersion()
    ||window.state()!=State.ACTIVE||window.validUntilEpochMillis()>value.notAfterEpochMillis())throw unavailable();
  return new Current(reference,value,row.controlVersion(),window.observedAtEpochMillis(),window.validUntilEpochMillis());
 }
 static void window(Window window,Namespace namespace,long admissionUntil){
  if(window==null||!Objects.equals(TenantResourceBindingCodec.namespaceDigest(namespace),window.namespaceDigest())||window.resourceTenant()==null||window.reference()==null||window.currentEligibility()==null
    ||window.state()==null||window.controlVersion()<1||window.observedAtEpochMillis()<1||window.validUntilEpochMillis()<=window.observedAtEpochMillis()||window.validUntilEpochMillis()>admissionUntil
    ||window.effectiveSessionPrincipal()==null||window.effectiveSessionPrincipal().isBlank())throw unavailable();
 }
 static void completion(Completion done,Command command,long nextVersion,State nextState){
  if(done==null||!done.actionId().equals(command.actionId())||!done.intentDigest().equals(TenantResourceBindingCodec.commandDigest(command))
    ||!done.reference().equals(TenantResourceBindingCodec.reference(command.definition()))||done.controlVersion()!=nextVersion||done.state()!=nextState)throw unavailable();
 }
 static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Exact current tenant resource binding required");}
}
