package io.fireflysoftware.common.config.core.authority;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Mono;
/** Native lifecycle contract; its mapped adapter accepts only actual native entrypoint results. */
public interface TenantResourceBindingNativeAuthority {
 record Window(String namespaceDigest,ResourceTenant resourceTenant,Reference reference,Eligibility currentEligibility,
   long controlVersion,State state,boolean eligibleNow,long observedAtEpochMillis,long validUntilEpochMillis,String effectiveSessionPrincipal) {}
 record Control(Window window,String originalMakerIssuer,String originalMakerSubject,Completion replay) {}
 ConnectionFactory connectionFactory();
 Mono<Window> openLookup(TenantResourceBindingLookupAuthorization.Admission admission);
 Mono<Control> openControl(InstitutionAuthorityAuthorization.Admission admission,Command command);
 Mono<Completion> finishControl(InstitutionAuthorityAuthorization.Admission admission,Command command,Reference reference,long controlVersion,State state);
}
