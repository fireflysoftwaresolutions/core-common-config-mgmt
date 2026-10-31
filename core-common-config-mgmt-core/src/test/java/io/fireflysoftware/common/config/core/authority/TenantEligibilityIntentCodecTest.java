package io.fireflysoftware.common.config.core.authority;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static io.fireflysoftware.common.config.core.authority.InstitutionAuthorityGrantV2CodecTest.*;

class TenantEligibilityIntentCodecTest {
    @Test void literalEmergencyDisableIntentHasNoApprovalOrActorImpersonation() throws Exception {
        var v=vector();var result=TenantEligibilityIntentCodec.decodeSuspend(v.get("tenantId").textValue(),bytes(v.get("tenantSuspendOriginal").textValue()));
        assertEquals(v.get("tenantSuspendCanonical").textValue(),result.canonicalIntent());assertEquals(v.get("tenantSuspendDigest").textValue(),result.intentDigest());
        for(var body:List.of(v.get("tenantSuspendOriginal").textValue().replace("{","{\"staffActorId\":null,"),v.get("tenantSuspendOriginal").textValue().replace("{","{\"approved\":true,"),v.get("tenantSuspendOriginal").textValue().replace("1792800000000","1e3"))) {
            assertThrows(IllegalArgumentException.class,()->TenantEligibilityIntentCodec.decodeSuspend(v.get("tenantId").textValue(),bytes(body)));
        }
        assertThrows(UnsupportedOperationException.class,()->result.request().evidenceReferences().clear());
    }
    @Test void transitionBindsCurrentAndDestinationStatusGenerationsAndActualState() {
        var tenant="00000000-0000-0000-0000-000000000001";
        var json="{\"actionId\":\"00000000-0000-0000-0000-000000000003\",\"proposalId\":\"00000000-0000-0000-0000-000000000004\",\"proposalRevision\":1,\"expectedEpoch\":{\"tenantGeneration\":1,\"statusId\":\"00000000-0000-0000-0000-000000000010\",\"statusGeneration\":2},\"targetStatusId\":\"00000000-0000-0000-0000-000000000011\",\"expectedTargetStatusGeneration\":3,\"targetActive\":true,\"reason\":\"Reviewed recovery\",\"evidenceReferences\":[],\"notAfterEpochMillis\":1792800000000}";
        var original=TenantEligibilityIntentCodec.decodeTransition(tenant,bytes(json));
        for(var changed:List.of(json.replace("\"targetActive\":true","\"targetActive\":false"),json.replace("\"expectedTargetStatusGeneration\":3","\"expectedTargetStatusGeneration\":4"),json.replace("\"tenantGeneration\":1","\"tenantGeneration\":2"))) {
            assertNotEquals(original.intentDigest(),TenantEligibilityIntentCodec.decodeTransition(tenant,bytes(changed)).intentDigest());
        }
    }
    @Test void statusAndTenantEmergencyCommandsHaveDifferentCanonicalDomains() throws Exception {
        var v=vector();var tenant=TenantEligibilityIntentCodec.decodeSuspend(v.get("tenantId").textValue(),bytes(v.get("tenantSuspendOriginal").textValue()));
        var status=TenantEligibilityIntentCodec.decodeStatusSuspend(v.get("statusId").textValue(),bytes(v.get("tenantSuspendOriginal").textValue()));
        assertNotEquals(tenant.intentDigest(),status.intentDigest());
    }
}
