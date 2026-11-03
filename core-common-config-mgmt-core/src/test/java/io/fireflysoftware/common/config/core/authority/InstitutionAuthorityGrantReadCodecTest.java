package io.fireflysoftware.common.config.core.authority;

import org.junit.jupiter.api.Test;
import io.fireflysoftware.common.config.interfaces.authority.InstitutionAuthorityUse;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityGrantReadCodecTest {
    static final String TENANT="00000000-0000-0000-0000-000000000001",INSTITUTION="00000000-0000-0000-0000-000000000002",ISSUER="00000000-0000-0000-0000-000000000003",GRANT="00000000-0000-0000-0000-000000000004";
    @Test void currentObservationBindsExactIssuerUseAndTenantWhileHistoryBindsExactVersion() {
        var current=InstitutionAuthorityGrantReadCodec.observe(TENANT,INSTITUTION,ISSUER,InstitutionAuthorityUse.ACCOUNTING_BOOK);
        assertNotEquals(current.intentDigest(),InstitutionAuthorityGrantReadCodec.observe(TENANT,INSTITUTION,ISSUER,InstitutionAuthorityUse.CASH_OPERATIONS).intentDigest());
        var history=InstitutionAuthorityGrantReadCodec.history(TENANT,INSTITUTION,ISSUER,GRANT,1);assertNotEquals(current.intentDigest(),history.intentDigest());assertNotEquals(history.intentDigest(),InstitutionAuthorityGrantReadCodec.history(TENANT,INSTITUTION,ISSUER,GRANT,2).intentDigest());
        assertThrows(IllegalArgumentException.class,()->InstitutionAuthorityGrantReadCodec.history(TENANT,INSTITUTION,ISSUER,GRANT,0));assertThrows(IllegalArgumentException.class,()->InstitutionAuthorityGrantReadCodec.observe(TENANT,INSTITUTION,ISSUER,null));
    }
}
