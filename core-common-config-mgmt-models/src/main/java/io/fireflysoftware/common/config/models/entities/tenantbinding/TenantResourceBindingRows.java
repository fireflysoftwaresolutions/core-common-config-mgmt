package io.fireflysoftware.common.config.models.entities.tenantbinding;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
public final class TenantResourceBindingRows {
 private TenantResourceBindingRows() {}
 @Table(value="cfg_tenant_resource_binding_definition",schema="public")
 public record Definition(@Id UUID bindingId,long version,String namespaceDigest,String issuer,String platformTenant,UUID resourceTenantId,
   String definitionDigest,long tenantGeneration,UUID statusId,long statusGeneration,long notAfterEpochMillis,String makerIssuer,String makerSubject){
  @Override public String toString(){return "TenantResourceBindingDefinitionRow";}
 }
 @Table(value="cfg_tenant_resource_binding_head",schema="public")
 public record Head(@Id String namespaceDigest,UUID bindingId,long definitionVersion,String definitionDigest,long controlVersion,String state){
  @Override public String toString(){return "TenantResourceBindingHeadRow";}
 }
 @Table(value="cfg_tenant_resource_binding_current",schema="public")
 public record Snapshot(@Id String namespaceDigest,UUID bindingId,long definitionVersion,String definitionDigest,long controlVersion,String state,
   String issuer,String platformTenant,UUID resourceTenantId,long tenantGeneration,UUID statusId,long statusGeneration,long notAfterEpochMillis,
   long liveTenantGeneration,UUID liveStatusId,long liveStatusGeneration,boolean eligibleNow){
  @Override public String toString(){return "TenantResourceBindingSnapshotRow";}
 }
}
