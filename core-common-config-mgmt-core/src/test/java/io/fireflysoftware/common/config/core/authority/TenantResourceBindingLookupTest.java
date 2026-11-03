package io.fireflysoftware.common.config.core.authority;
import org.fireflyframework.security.api.domain.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class TenantResourceBindingLookupTest {
 private static final String TENANT="opaque / Tenant:alpha";
 @Test void authenticatedOpaqueTenantIsPassedToPolicyUntouched(){
  var time=new InstitutionAuthorityBudgetTest.Time();var original=InstitutionAuthorityBudgetTest.budget(time,800);var actor=InstitutionAuthorityBudgetTest.principal(TENANT);var seen=new AtomicReference<SecurityPrincipal>();var kind=new AtomicReference<Object>();
  var auth=new TenantResourceBindingLookupAuthorization(()->Mono.just(actor),(p,a,r,c)->{seen.set(p);kind.set(c.get("contextKind"));assertEquals(TENANT,c.get("platformTenant"));assertFalse(c.containsKey("resourceTenantId"));return Mono.just(Decision.permit());},time,Set.of(actor.issuer()));
  var permit=auth.authorize(original).block();assertSame(actor,seen.get());assertSame(original,permit.originalBudget());assertEquals(TENANT,permit.namespace().platformTenant());assertEquals("TENANT_RESOURCE_BINDING_LOOKUP",kind.get());assertEquals(InstitutionAuthorityBudgetTest.NOW.plusMillis(800).toEpochMilli(),permit.validUntilEpochMillis());
  assertNotNull(auth.reauthorize(permit,"RESPONSE").block());
 }
 @Test void lookupNeverAcceptsClaimAliasesOrUntrustedIssuer(){
  var time=new InstitutionAuthorityBudgetTest.Time();var actor=InstitutionAuthorityBudgetTest.principal(TENANT);
  for(var principal:List.of(actor.toBuilder().tenantId("other").build(),actor.toBuilder().issuer("https://other.example.test").build())){
   var calls=new AtomicInteger();var auth=new TenantResourceBindingLookupAuthorization(()->Mono.just(principal),(p,a,r,c)->{calls.incrementAndGet();return Mono.just(Decision.permit());},time,Set.of(actor.issuer()));
   assertThrows(Exception.class,()->auth.authorize(InstitutionAuthorityBudgetTest.budget(time,800)).block());assertEquals(0,calls.get());
  }
 }
 @Test void missingPrincipalPolicyOrUnsupportedObligationRefuses(){
  var time=new InstitutionAuthorityBudgetTest.Time();var actor=InstitutionAuthorityBudgetTest.principal(TENANT);
  var missing=new TenantResourceBindingLookupAuthorization(Mono::empty,(p,a,r,c)->Mono.just(Decision.permit()),time,Set.of(actor.issuer()));
  assertThrows(Exception.class,()->missing.authorize(InstitutionAuthorityBudgetTest.budget(time,800)).block());
  for(var decision:List.of(Decision.deny("revoked"),Decision.permit(List.of(Obligation.of("unsupported",Map.of()))))){
   var auth=new TenantResourceBindingLookupAuthorization(()->Mono.just(actor),(p,a,r,c)->Mono.just(decision),time,Set.of(actor.issuer()));assertThrows(Exception.class,()->auth.authorize(InstitutionAuthorityBudgetTest.budget(time,800)).block());
  }
 }
 @Test void currentCallerAndPolicyRecheckCannotRenewOrReplaceOriginal(){
  var time=new InstitutionAuthorityBudgetTest.Time();var current=new AtomicReference<>(InstitutionAuthorityBudgetTest.principal(TENANT));var allow=new AtomicBoolean(true);
  var auth=new TenantResourceBindingLookupAuthorization(()->Mono.just(current.get()),(p,a,r,c)->Mono.just(allow.get()?Decision.permit():Decision.deny("revoked")),time,Set.of(InstitutionAuthorityBudgetTest.I));
  var permit=auth.authorize(InstitutionAuthorityBudgetTest.budget(time,100)).block();allow.set(false);assertThrows(Exception.class,()->auth.reauthorize(permit,"COMMIT").block());allow.set(true);
  current.set(current.get().toBuilder().subject("other-executor").build());assertThrows(Exception.class,()->auth.reauthorize(permit,"RESPONSE").block());current.set(InstitutionAuthorityBudgetTest.principal(TENANT));
  time.advance(100);assertThrows(Exception.class,permit::requireCurrent);
 }
 @Test void expiryBeforeSubscriptionDoesNotReadPrincipal(){
  var time=new InstitutionAuthorityBudgetTest.Time();var original=InstitutionAuthorityBudgetTest.budget(time,10);var reads=new AtomicInteger();var auth=new TenantResourceBindingLookupAuthorization(()->{reads.incrementAndGet();return Mono.just(InstitutionAuthorityBudgetTest.principal(TENANT));},(p,a,r,c)->Mono.just(Decision.permit()),time,Set.of(InstitutionAuthorityBudgetTest.I));
  var operation=auth.authorize(original);time.advance(10);assertThrows(Exception.class,operation::block);assertEquals(0,reads.get());
 }
 @Test void longCallerBudgetCannotExtendTheFirstPrincipalWaitBeyondIngressFiveSeconds(){var time=new InstitutionAuthorityBudgetTest.Time();var original=org.fireflyframework.core.transaction.R2dbcOperationBudget.observed(time.instant(),time.instant().plusSeconds(20),time.nanos.get(),java.time.Duration.ofSeconds(25),time,time.nanos::get);var reads=new AtomicInteger();var policies=new AtomicInteger();var auth=new TenantResourceBindingLookupAuthorization(()->{reads.incrementAndGet();return Mono.never();},(p,a,r,c)->{policies.incrementAndGet();return Mono.just(Decision.permit());},time,Set.of(InstitutionAuthorityBudgetTest.I));reactor.test.StepVerifier.withVirtualTime(()->auth.authorize(original)).thenAwait(java.time.Duration.ofMillis(5001)).expectError(org.springframework.web.server.ResponseStatusException.class).verify(java.time.Duration.ofMillis(300));assertEquals(1,reads.get());assertEquals(0,policies.get());}
 @Test void delayedPrincipalAndAdmissionPolicyShareTheSameOriginalFiveSecondCap(){var time=new InstitutionAuthorityBudgetTest.Time();var original=org.fireflyframework.core.transaction.R2dbcOperationBudget.observed(time.instant(),time.instant().plusSeconds(20),time.nanos.get(),java.time.Duration.ofSeconds(25),time,time.nanos::get);var actor=InstitutionAuthorityBudgetTest.principal(TENANT);var policies=new AtomicInteger();var auth=new TenantResourceBindingLookupAuthorization(()->Mono.delay(java.time.Duration.ofSeconds(4)).map(ignored->{time.advance(4000);return actor;}),(p,a,r,c)->{policies.incrementAndGet();return Mono.never();},time,Set.of(actor.issuer()));reactor.test.StepVerifier.withVirtualTime(()->auth.authorize(original)).thenAwait(java.time.Duration.ofMillis(5001)).expectError(org.springframework.web.server.ResponseStatusException.class).verify(java.time.Duration.ofMillis(300));assertEquals(1,policies.get());assertEquals(java.time.Duration.ofSeconds(16),original.remaining());}

}
