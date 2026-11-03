package io.fireflysoftware.common.config.core.authority;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.fireflyframework.security.api.domain.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class InstitutionAuthorityBudgetTest {
 static final String T="00000000-0000-0000-0000-000000000001", E="00000000-0000-0000-0000-000000000002", I="https://issuer.example.test";
 static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");
 static final class Time extends Clock {Instant now=NOW;final AtomicLong nanos=new AtomicLong();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}void advance(long ms){now=now.plusMillis(ms);nanos.addAndGet(ms*1000000);}}
 static R2dbcOperationBudget budget(Time time,long millis){return R2dbcOperationBudget.observed(time.instant(),time.instant().plusMillis(millis),time.nanos.get(),Duration.ofSeconds(5),time,time.nanos::get);}
 static SecurityPrincipal principal(String tenant){return new SecurityPrincipal("actual-subject",I,tenant,Set.of(),Set.of(),Map.of("tenant_id",tenant,"exp",NOW.plusSeconds(300),"principal_kind","STAFF","staff_actor_id",E),null,null,Set.of(),Map.of());}
 static InstitutionAuthorityAuthorization.Scope scope(){var canonical="S4:TEST";return new InstitutionAuthorityAuthorization.Scope(T,null,InstitutionAuthorityAuthorization.ResourceType.PROPOSAL,E,InstitutionAuthorityAuthorization.Operation.PREPARE,canonical,AuthorityWire.sha(canonical),false);}
 @Test void expiredOriginalRefusesBeforePrincipalOrPolicy(){
  var time=new Time();var b=budget(time,10);time.advance(10);var reads=new AtomicInteger();var policies=new AtomicInteger();
  var auth=new InstitutionAuthorityAuthorization(()->{reads.incrementAndGet();return Mono.just(principal(T));},(p,a,r,c)->{policies.incrementAndGet();return Mono.just(Decision.permit());},time,Set.of(I),T,true);
  assertThrows(Exception.class,()->auth.authorize(scope(),b).block());assertEquals(0,reads.get());assertEquals(0,policies.get());
 }
 @Test void policyAndRetainedAdmissionCannotRenewOriginalBudget(){
  var time=new Time();var b=budget(time,100);var auth=new InstitutionAuthorityAuthorization(()->Mono.just(principal(T)),(p,a,r,c)->{time.advance(40);return Mono.just(Decision.permit());},time,Set.of(I),T,true);
  var admission=auth.authorize(scope(),b).block();assertSame(b,admission.originalBudget());assertEquals(NOW.plusMillis(100).toEpochMilli(),admission.validUntilEpochMillis());
  time.advance(60);assertThrows(Exception.class,admission::requireCurrent);
 }
 @Test void originalBudgetExhaustedDuringPolicyRefuses(){
  var time=new Time();var b=budget(time,10);var auth=new InstitutionAuthorityAuthorization(()->Mono.just(principal(T)),(p,a,r,c)->{time.advance(11);return Mono.just(Decision.permit());},time,Set.of(I),T,true);
  assertThrows(Exception.class,()->auth.authorize(scope(),b).block());
 }
 @Test void legacyGovernanceStillRejectsOpaqueActorTenant(){
  var time=new Time();var auth=new InstitutionAuthorityAuthorization(()->Mono.just(principal("opaque / tenant")),(p,a,r,c)->Mono.just(Decision.permit()),time,Set.of(I),T,true);
  assertThrows(Exception.class,()->auth.authorize(scope(),budget(time,100)).block());
 }
 @Test void technicalThirtySecondCapStartsBeforePrincipalObservationForLongCallerBudget(){var time=new Time();var original=R2dbcOperationBudget.observed(time.instant(),time.instant().plusSeconds(40),time.nanos.get(),Duration.ofSeconds(50),time,time.nanos::get);var auth=new InstitutionAuthorityAuthorization(()->{time.advance(10000);return Mono.just(principal(T));},(p,a,r,c)->Mono.just(Decision.permit()),time,Set.of(I),T,true);var admission=auth.authorize(scope(),original).block();assertSame(original,admission.originalBudget());assertEquals(NOW.plusSeconds(30).toEpochMilli(),admission.validUntilEpochMillis());}

 @Test void bindingKindsCannotReuseTheLegacyFreshLeaseOverload(){var time=new Time();var command=TenantResourceBindingServiceTest.command(io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Operation.TENANT_RESOURCE_BINDING_ESTABLISH,0);var scope=new InstitutionAuthorityAuthorization.Scope(T,null,InstitutionAuthorityAuthorization.ResourceType.TENANT_RESOURCE_BINDING,command.definition().bindingId().toString(),InstitutionAuthorityAuthorization.Operation.TENANT_RESOURCE_BINDING_ESTABLISH,TenantResourceBindingCodec.commandCanonical(command),TenantResourceBindingCodec.commandDigest(command),true);var reads=new AtomicInteger();var auth=new InstitutionAuthorityAuthorization(()->{reads.incrementAndGet();return Mono.just(principal(T));},(p,a,r,c)->Mono.just(Decision.permit()),time,Set.of(I),T,true);assertThrows(Exception.class,()->auth.authorize(scope).block());assertEquals(0,reads.get());assertNotNull(auth.authorize(scope,budget(time,800)).block());}

}
