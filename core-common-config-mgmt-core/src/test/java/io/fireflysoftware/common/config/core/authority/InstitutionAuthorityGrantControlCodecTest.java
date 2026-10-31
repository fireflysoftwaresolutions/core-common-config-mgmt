package io.fireflysoftware.common.config.core.authority;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityGrantControlCodecTest {
    static final String TENANT="00000000-0000-0000-0000-000000000001";
    static String target(){return "{\"actionId\":\"00000000-0000-0000-0000-000000000002\",\"proposalId\":\"00000000-0000-0000-0000-000000000003\",\"proposalRevision\":1,\"grantId\":\"00000000-0000-0000-0000-000000000004\",\"version\":1,\"institutionId\":\"00000000-0000-0000-0000-000000000005\",\"issuerAuthorityId\":\"00000000-0000-0000-0000-000000000006\",\"expectedControlVersion\":0,\"expectedState\":\"ACTIVE\",\"targetState\":\"SUSPENDED\",\"expectedEpoch\":{\"tenantGeneration\":0,\"statusId\":\"00000000-0000-0000-0000-000000000007\",\"statusGeneration\":0},\"reason\":\"Reviewed grant suspension\",\"evidenceReferences\":[],\"notAfterEpochMillis\":1893456000000}";}
    @Test void controlIntentBindsCompleteGrantVersionStateAndEligibilityEpoch() {
        var intent=InstitutionAuthorityGrantControlCodec.decodeTarget(TENANT,target().getBytes(StandardCharsets.UTF_8));assertEquals("SUSPENDED",intent.request().targetState());assertTrue(intent.canonicalIntent().contains("FF_INSTITUTION_GRANT_CONTROL_V2"));
        assertNotEquals(intent.intentDigest(),InstitutionAuthorityGrantControlCodec.decodeTarget(TENANT,target().replace("\"tenantGeneration\":0","\"tenantGeneration\":1").getBytes(StandardCharsets.UTF_8)).intentDigest());
        for(var invalid:java.util.List.of(target().replace("\"expectedState\":\"ACTIVE\"","\"expectedState\":\"REVOKED\""),target().replace("\"targetState\":\"SUSPENDED\"","\"targetState\":\"ACTIVE\""),target().replace("\"expectedControlVersion\":0","\"expectedControlVersion\":-1"))) assertThrows(IllegalArgumentException.class,()->InstitutionAuthorityGrantControlCodec.decodeTarget(TENANT,invalid.getBytes(StandardCharsets.UTF_8)));
    }
}
