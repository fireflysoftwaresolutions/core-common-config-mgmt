package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fireflysoftware.common.config.interfaces.authority.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Set;

public final class InstitutionAuthorityGrantReadVerifier {
    private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private InstitutionAuthorityGrantReadVerifier() {}
    public static InstitutionAuthorityGrantObservation observation(String tenant,GrantObservationRequest request,String original) {
        try {
            var value=JSON.convertValue(AuthorityWire.parse(original),InstitutionAuthorityGrantObservation.class);
            if(!tenant.equals(value.tenantId()) || !request.institutionId().equals(value.institutionId()) || !request.issuerAuthorityId().equals(value.issuerAuthorityId()) || request.use()!=value.requestedUse() || !Set.of("CURRENT_AUTHORIZED","NO_CURRENT_AUTHORITY").contains(value.state()) || value.observedAtEpochMillis() < -2208988800000L || value.observedAtEpochMillis()>253402300799999L) throw AuthorityWire.bad();
            if(value.definition()!=null) definition(tenant,request.institutionId(),request.issuerAuthorityId(),value.definition());
            if(value.control()!=null){if(!Set.of("ACTIVE","SUSPENDED","REVOKED").contains(value.control().state()) || value.control().controlVersion()<0) throw AuthorityWire.bad();AuthorityWire.uuid(value.control().lastActionId());}
            if(value.state().equals("CURRENT_AUTHORIZED")) {
                var grant=value.definition().target().grant();var expected=value.definition().target().eligibilityEpoch();var live=value.tenantEligibility();long observed=value.observedAtEpochMillis();
                if(value.control()==null || !value.control().state().equals("ACTIVE") || value.headVersion()==null || value.headVersion()!=grant.version() || !grant.uses().contains(request.use())
                    || live==null || !tenant.equals(live.tenantId()) || !live.eligibleNow() || !live.tenantActive() || !live.statusEnabled() || !"ACTIVE".equals(live.statusCode())
                    || expected.tenantGeneration()!=live.tenantGeneration() || !expected.statusId().equals(live.statusId()) || expected.statusGeneration()!=live.statusGeneration()
                    || observed<grant.effectiveFromEpochMillis() || observed>=grant.effectiveToEpochMillis() || value.validUntilEpochMillis()==null || value.validUntilEpochMillis()<=observed || value.validUntilEpochMillis()>Math.min(Math.addExact(observed,30000),grant.effectiveToEpochMillis()) || value.denialReason()!=null) throw AuthorityWire.bad();
            } else if(value.validUntilEpochMillis()!=null || value.denialReason()==null || value.denialReason().isBlank()) throw AuthorityWire.bad();
            return value;
        } catch(Exception error) {throw unavailable();}
    }
    public static InstitutionAuthorityGrantHistory history(String tenant,GrantHistoryRequest request,String original) {
        try {
            var value=JSON.convertValue(AuthorityWire.parse(original),InstitutionAuthorityGrantHistory.class);if(!value.historicalOnly()) throw AuthorityWire.bad();definition(tenant,request.institutionId(),request.issuerAuthorityId(),value.definition());
            if(!request.grantId().equals(value.definition().target().grant().grantId()) || request.version()!=value.definition().target().grant().version()) throw AuthorityWire.bad();return value;
        } catch(Exception error) {throw unavailable();}
    }
    private static void definition(String tenant,String institution,String issuer,TenantInstitutionGrantDefinition value) throws Exception {
        var verified=InstitutionAuthorityGrantDefinitionCodec.decodeDefinition(tenant,JSON.writeValueAsBytes(value));if(!institution.equals(verified.target().grant().institutionId()) || !issuer.equals(verified.target().grant().issuerAuthorityId())) throw AuthorityWire.bad();
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Retained grant facts failed validation");}
}
