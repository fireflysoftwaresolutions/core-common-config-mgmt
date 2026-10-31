package io.fireflysoftware.common.config.core.authority;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeAuthorityActionReadCodecTest {
    @Test void exactActionReadHasLiteralDomainAndCannotCertifyAbsence() {
        var tenant="00000000-0000-0000-0000-000000000001";var action="00000000-0000-0000-0000-000000000002";
        var read=NativeAuthorityActionReadCodec.read(tenant,action);
        assertEquals("S34:FF_NATIVE_AUTHORITY_ACTION_READ_V1R2:S36:"+tenant+"S36:"+action,read.canonicalIntent());
        assertNotEquals(read.intentDigest(),NativeAuthorityActionReadCodec.read(action,tenant).intentDigest());
        var absent=NativeAuthorityActionReadVerifier.absent(tenant,action);assertEquals("UNKNOWN",absent.state().name());assertNull(absent.canonicalReceiptJson());assertNull(absent.receiptDigest());assertTrue(absent.historicalOnly());
        assertThrows(IllegalArgumentException.class,()->NativeAuthorityActionReadCodec.read(tenant,"bad"));
    }
}
