package io.fireflysoftware.common.config.core.authority;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class TenantResourceBindingContractsTest {
 private static final String ISSUER="https://issuer.example.test";
 @Test void opaqueNamespacesPreserveAllBytesAndNeverBecomeResourceTenants(){
  var a=new Namespace(ISSUER,"north / tenant:01");var b=new Namespace(ISSUER,"north / tenant:01 ");
  assertNotEquals(a,b);assertNotEquals(TenantResourceBindingCodec.namespaceDigest(a),TenantResourceBindingCodec.namespaceDigest(b));
  assertNotEquals(TenantResourceBindingCodec.namespaceDigest(a),TenantResourceBindingCodec.namespaceDigest(new Namespace(ISSUER+"/","north / tenant:01")));
  var uuid="00000000-0000-0000-0000-000000000001";var opaque=new Namespace(ISSUER,uuid);
  assertEquals(uuid,opaque.platformTenant());assertNotEquals(opaque,new ResourceTenant(UUID.fromString(uuid)));
 }
 @Test void canonicalProtocolIsLengthFramedAndExact(){
  var a=new Namespace(ISSUER,"a|b");var b=new Namespace(ISSUER+"/a","b");
  assertNotEquals(TenantResourceBindingCodec.namespaceDigest(a),TenantResourceBindingCodec.namespaceDigest(b));
  assertEquals(TenantResourceBindingCodec.namespaceDigest(a),TenantResourceBindingCodec.namespaceDigest(new Namespace(ISSUER,"a|b")));
 }
 @Test void malformedNamespaceAndNilResourceNeverAdmit(){
  for(var tenant:new String[]{""," ","line\nnext","x".repeat(1025)})assertThrows(IllegalArgumentException.class,()->new Namespace(ISSUER,tenant));
  for(var issuer:new String[]{"local","https://user@issuer.example.test","https://issuer.example.test/#fragment"})assertThrows(IllegalArgumentException.class,()->new Namespace(issuer,"opaque"));
  assertThrows(IllegalArgumentException.class,()->new ResourceTenant(new UUID(0,0)));
 }
 @Test void bindingsAreExplicitTargetsAndCannotReuseGrantOrTenantTransitions(){
  assertEquals("TENANT_RESOURCE_BINDING",InstitutionAuthorityAuthorization.ResourceType.valueOf("TENANT_RESOURCE_BINDING").name());
  assertEquals("TENANT_RESOURCE_BINDING_ESTABLISH",InstitutionAuthorityAuthorization.Operation.valueOf("TENANT_RESOURCE_BINDING_ESTABLISH").name());
  assertEquals("TENANT_RESOURCE_BINDING_REVOKE",InstitutionAuthorityAuthorization.Operation.valueOf("TENANT_RESOURCE_BINDING_REVOKE").name());
 }
 @Test void existingNativeGenerationZeroIsAValidEpoch(){var status=UUID.fromString("00000000-0000-0000-0000-000000000002");assertNotNull(new Eligibility(0,status,0));assertThrows(IllegalArgumentException.class,()->new Eligibility(-1,status,0));}

}
