package io.fireflysoftware.common.config.core.authority;

import org.fireflyframework.security.api.domain.*;
import org.fireflyframework.security.spi.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityAuthorizationTest {
    private static final String TENANT="00000000-0000-0000-0000-000000000001";
    private static final String PLATFORM="00000000-0000-0000-0000-000000000002";
    private static final String INSTITUTION="00000000-0000-0000-0000-000000000003";
    private static final String EMPLOYEE="00000000-0000-0000-0000-000000000004";
    private static final String ISSUER="https://issuer.example.test";
    private static final Instant NOW=Instant.parse("2026-10-23T12:00:00Z");
    private static final String CANONICAL="S4:TEST";
    private static final String DIGEST=AuthorityWire.sha(CANONICAL);
    private static SecurityPrincipal principal(String kind,String tenant,String subject,String employee) {
        var claims=new HashMap<String,Object>();claims.put("exp",NOW.plusSeconds(300));claims.put("tenant_id",tenant);claims.put("principal_kind",kind);
        if(employee!=null) claims.put("staff_actor_id",employee);if(kind.equals("MACHINE")) claims.put("client_id","scheduler-client");
        return new SecurityPrincipal(subject,ISSUER,tenant,Set.of(),Set.of(),claims,null,null,Set.of(),Map.of());
    }
    private static InstitutionAuthorityAuthorization.Scope scope(InstitutionAuthorityAuthorization.Operation op,boolean recovery) {
        return new InstitutionAuthorityAuthorization.Scope(TENANT,INSTITUTION,InstitutionAuthorityAuthorization.ResourceType.PROPOSAL,INSTITUTION,op,CANONICAL,DIGEST,recovery);
    }
    private static InstitutionAuthorityAuthorization authorization(SecurityPrincipal actor,PolicyDecisionPort policies,Clock clock) {
        return new InstitutionAuthorityAuthorization(()->Mono.just(actor),policies,clock,Set.of(ISSUER),PLATFORM,true);
    }
    @Test void projectsSignedStaffAndFullIntentIntoCurrentPolicyContext() {
        var input=new AtomicReference<Map<String,Object>>();
        var auth=authorization(principal("STAFF",TENANT,"staff-subject",EMPLOYEE),(p,a,r,c)->{input.set(c);return Mono.just(Decision.permit());},Clock.fixed(NOW,ZoneOffset.UTC));
        var admission=auth.authorize(scope(InstitutionAuthorityAuthorization.Operation.APPROVE,false)).block();
        assertEquals(EMPLOYEE,admission.actor().employeeId());assertEquals("STAFF",admission.actor().kind());
        assertEquals(ISSUER,input.get().get("actorIssuer"));assertEquals("staff-subject",input.get().get("actorSubject"));assertEquals(CANONICAL,input.get().get("canonicalIntent"));assertEquals(DIGEST,input.get().get("intentDigest"));
        assertEquals(NOW.plusSeconds(30).toEpochMilli(),admission.validUntilEpochMillis());
    }
    @Test void machineExecutionKeepsItsActualIdentityAndCannotMakeOrCheck() {
        var auth=authorization(principal("MACHINE",TENANT,"actual-machine-subject",null),(p,a,r,c)->Mono.just(Decision.permit()),Clock.fixed(NOW,ZoneOffset.UTC));
        var admission=auth.authorize(scope(InstitutionAuthorityAuthorization.Operation.GRANT_ISSUE,false)).block();
        assertEquals("MACHINE",admission.actor().kind());assertEquals("actual-machine-subject",admission.actor().subject());assertEquals("scheduler-client",admission.actor().clientId());assertNull(admission.actor().employeeId());
        for(var op:List.of(InstitutionAuthorityAuthorization.Operation.PREPARE,InstitutionAuthorityAuthorization.Operation.SUBMIT,InstitutionAuthorityAuthorization.Operation.APPROVE,InstitutionAuthorityAuthorization.Operation.REJECT,InstitutionAuthorityAuthorization.Operation.WITHDRAW)) {
            StepVerifier.create(auth.authorize(scope(op,false))).expectError(ResponseStatusException.class).verify();
        }
    }
    @Test void deniesEmptyFalseErrorAndUnsupportedPolicyObligations() {
        for(PolicyDecisionPort policy:List.<PolicyDecisionPort>of((p,a,r,c)->Mono.empty(),(p,a,r,c)->Mono.just(Decision.deny("revoked")),(p,a,r,c)->Mono.error(new IllegalStateException("unavailable")),(p,a,r,c)->Mono.just(Decision.permit(List.of(Obligation.of("unsupported",Map.of())))))) {
            StepVerifier.create(authorization(principal("STAFF",TENANT,"staff",EMPLOYEE),policy,Clock.fixed(NOW,ZoneOffset.UTC)).authorize(scope(InstitutionAuthorityAuthorization.Operation.APPROVE,false))).expectError(ResponseStatusException.class).verify();
        }
    }
    @Test void currentPrincipalCannotBeReplacedByActorAttributesOrExpiredClaims() {
        var ordinary=principal("STAFF",TENANT,"staff",EMPLOYEE);
        var claims=new HashMap<>(ordinary.claims());claims.remove("staff_actor_id");
        var forged=ordinary.toBuilder().claims(claims).attributes(Map.of("staffActorId",EMPLOYEE)).build();
        var expired=ordinary.toBuilder().claims(Map.of("exp",NOW,"tenant_id",TENANT,"principal_kind","STAFF","staff_actor_id",EMPLOYEE)).build();
        var wrongIssuer=ordinary.toBuilder().issuer("https://other.example.test").build();
        var wrongTenant=ordinary.toBuilder().tenantId(PLATFORM).build();
        for(var actor:List.of(forged,expired,wrongIssuer,wrongTenant)) StepVerifier.create(authorization(actor,(p,a,r,c)->Mono.just(Decision.permit()),Clock.fixed(NOW,ZoneOffset.UTC)).authorize(scope(InstitutionAuthorityAuthorization.Operation.APPROVE,false))).expectError(ResponseStatusException.class).verify();
    }
    @Test void onlyExplicitPlatformRecoveryAllowsTargetTenantMismatch() {
        var actor=principal("STAFF",PLATFORM,"platform-staff",EMPLOYEE);
        var auth=authorization(actor,(p,a,r,c)->Mono.just(Decision.permit()),Clock.fixed(NOW,ZoneOffset.UTC));
        assertNotNull(auth.authorize(scope(InstitutionAuthorityAuthorization.Operation.TENANT_SUSPEND,true)).block());
        StepVerifier.create(auth.authorize(scope(InstitutionAuthorityAuthorization.Operation.TENANT_SUSPEND,false))).expectError(ResponseStatusException.class).verify();
        StepVerifier.create(authorization(principal("STAFF",TENANT,"ordinary",EMPLOYEE),(p,a,r,c)->Mono.just(Decision.permit()),Clock.fixed(NOW,ZoneOffset.UTC)).authorize(scope(InstitutionAuthorityAuthorization.Operation.TENANT_SUSPEND,true))).expectError(ResponseStatusException.class).verify();
    }
    @Test void retainedAdmissionExpiresAndAlternateLoginCannotSelfApprove() {
        var clock=new MutableClock();var auth=authorization(principal("STAFF",TENANT,"maker-login",EMPLOYEE),(p,a,r,c)->Mono.just(Decision.permit()),clock);
        var maker=auth.authorize(scope(InstitutionAuthorityAuthorization.Operation.PREPARE,false)).block();
        var checker=authorization(principal("STAFF",TENANT,"another-login",EMPLOYEE),(p,a,r,c)->Mono.just(Decision.permit()),clock).authorize(scope(InstitutionAuthorityAuthorization.Operation.APPROVE,false)).block();
        assertThrows(ResponseStatusException.class,()->InstitutionAuthorityAuthorization.requireIndependentStaff(maker.actor(),checker.actor()));
        clock.now=NOW.plusSeconds(30);assertThrows(ResponseStatusException.class,maker::requireCurrent);
    }
    @Test void policyWaitCannotExtendFiniteAdmissionOrAcceptChangedIntent() {
        var clock=new MutableClock();var auth=authorization(principal("STAFF",TENANT,"staff",EMPLOYEE),(p,a,r,c)->{clock.now=NOW.plusSeconds(31);return Mono.just(Decision.permit());},clock);
        StepVerifier.create(auth.authorize(scope(InstitutionAuthorityAuthorization.Operation.APPROVE,false))).expectError(ResponseStatusException.class).verify();
        var altered=new InstitutionAuthorityAuthorization.Scope(TENANT,INSTITUTION,InstitutionAuthorityAuthorization.ResourceType.PROPOSAL,INSTITUTION,InstitutionAuthorityAuthorization.Operation.APPROVE,CANONICAL,"a".repeat(64),false);
        StepVerifier.create(authorization(principal("STAFF",TENANT,"staff",EMPLOYEE),(p,a,r,c)->Mono.just(Decision.permit()),Clock.fixed(NOW,ZoneOffset.UTC)).authorize(altered)).expectError(ResponseStatusException.class).verify();
    }
    @Test void strictPolicyMustBeExplicitAndMissingPrincipalDenies() {
        var actor=principal("STAFF",TENANT,"staff",EMPLOYEE);
        var disabled=new InstitutionAuthorityAuthorization(()->Mono.just(actor),(p,a,r,c)->Mono.just(Decision.permit()),Clock.fixed(NOW,ZoneOffset.UTC),Set.of(ISSUER),PLATFORM,false);
        var missing=new InstitutionAuthorityAuthorization(Mono::empty,(p,a,r,c)->Mono.just(Decision.permit()),Clock.fixed(NOW,ZoneOffset.UTC),Set.of(ISSUER),PLATFORM,true);
        StepVerifier.create(disabled.authorize(scope(InstitutionAuthorityAuthorization.Operation.APPROVE,false))).expectError(ResponseStatusException.class).verify();
        StepVerifier.create(missing.authorize(scope(InstitutionAuthorityAuthorization.Operation.APPROVE,false))).expectError(ResponseStatusException.class).verify();
    }
    private static final class MutableClock extends Clock {
        Instant now=NOW;
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
