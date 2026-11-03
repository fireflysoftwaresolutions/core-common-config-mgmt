package io.fireflysoftware.common.config.core.authority;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityGrantPrepareCodecTest {
    private static final String TENANT="00000000-0000-0000-0000-000000000001";
    static String body(){return "{\"actionId\":\"00000000-0000-0000-0000-000000000002\",\"targetActionId\":\"00000000-0000-0000-0000-000000000003\",\"notAfterEpochMillis\":1893456000000,\"draft\":{\"existingInstitutionId\":null,\"existingIssuerAuthorityId\":null,\"expectedGrantHeadVersion\":0,\"uses\":[\"ACCOUNTING_BOOK\",\"PRODUCT_ISSUANCE\"],\"effectiveFromEpochMillis\":1767225600000,\"effectiveToEpochMillis\":1924992000000,\"reason\":\"Reviewed native institution admission\",\"evidenceReferences\":[]}}";}
    @Test void actualNativeIdentityAllocationNeedsNoBankOrCustomerIdentity() {
        var intent=InstitutionAuthorityGrantPrepareCodec.decode(TENANT,body().getBytes(StandardCharsets.UTF_8));assertNull(intent.request().draft().existingInstitutionId());assertNull(intent.request().draft().existingIssuerAuthorityId());assertEquals(0,intent.request().draft().expectedGrantHeadVersion());assertTrue(intent.canonicalIntent().contains("FF_TENANT_INSTITUTION_GRANT_PREPARE_V2"));
    }
    @Test void callerCannotSmuggleEligibilityOrUseAnIssuerWithoutItsInstitution() {
        for(var raw:java.util.List.of(body().replace("\"draft\":{","\"eligibilityEpoch\":{},\"draft\":{"),body().replace("\"existingIssuerAuthorityId\":null","\"existingIssuerAuthorityId\":\"00000000-0000-0000-0000-000000000004\""),body().replace("\"expectedGrantHeadVersion\":0","\"expectedGrantHeadVersion\":1"),body().replace("\"uses\":[\"ACCOUNTING_BOOK\",\"PRODUCT_ISSUANCE\"]","\"uses\":[\"PRODUCT_ISSUANCE\",\"ACCOUNTING_BOOK\"]"))) assertThrows(IllegalArgumentException.class,()->InstitutionAuthorityGrantPrepareCodec.decode(TENANT,raw.getBytes(StandardCharsets.UTF_8)));
    }
}
