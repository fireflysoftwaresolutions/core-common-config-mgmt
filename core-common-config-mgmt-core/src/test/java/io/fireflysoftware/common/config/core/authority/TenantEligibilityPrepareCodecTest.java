package io.fireflysoftware.common.config.core.authority;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TenantEligibilityPrepareCodecTest {
    static final String TENANT="00000000-0000-0000-0000-000000000001",ACTION="00000000-0000-0000-0000-000000000002",TARGET="00000000-0000-0000-0000-000000000003",STATUS="00000000-0000-0000-0000-000000000004";
    static String tenant(){return "{\"actionId\":\""+ACTION+"\",\"targetActionId\":\""+TARGET+"\",\"notAfterEpochMillis\":1893456000000,\"draft\":{\"targetStatusId\":\""+STATUS+"\",\"targetActive\":true,\"reason\":\"Reviewed recovery\",\"evidenceReferences\":[]}}";}
    @Test void prepareBindsSeparateTargetActionAndCompleteDraftWithNoCallerEpoch() {
        var result=TenantEligibilityIntentCodec.decodePrepare(TENANT,tenant().getBytes(StandardCharsets.UTF_8));
        assertEquals(TARGET,result.request().targetActionId());assertEquals(STATUS,result.request().draft().targetStatusId());assertTrue(result.canonicalIntent().contains("FF_TENANT_ELIGIBILITY_PREPARE_V1"));
        for(var raw:java.util.List.of(tenant().replace(TARGET,ACTION),tenant().replace("\"draft\":{","\"draft\":{\"expectedEpoch\":{},"),tenant().replace("\"targetActive\":true","\"targetActive\":\"true\""))) assertThrows(IllegalArgumentException.class,()->TenantEligibilityIntentCodec.decodePrepare(TENANT,raw.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void statusPreparationUsesItsOwnNativeSourceAndCanonicalDomain() {
        var raw=tenant().replace("\"targetStatusId\":\""+STATUS+"\",","");
        var result=TenantEligibilityIntentCodec.decodeStatusPrepare(STATUS,raw.getBytes(StandardCharsets.UTF_8));assertTrue(result.canonicalIntent().contains("FF_STATUS_ELIGIBILITY_PREPARE_V1"));
        assertThrows(IllegalArgumentException.class,()->TenantEligibilityIntentCodec.decodePrepare(TENANT,raw.getBytes(StandardCharsets.UTF_8)));
    }
}
