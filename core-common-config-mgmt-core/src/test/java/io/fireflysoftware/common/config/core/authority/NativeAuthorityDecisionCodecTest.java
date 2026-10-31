package io.fireflysoftware.common.config.core.authority;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class NativeAuthorityDecisionCodecTest {
    private static final String TENANT="00000000-0000-0000-0000-000000000001", PROPOSAL="00000000-0000-0000-0000-000000000002";
    @Test void fullRetainedTargetAndEveryDecisionFieldBindTheNativeIntent() throws Exception {
        var vector=InstitutionAuthorityGrantV2CodecTest.vector();var target=vector.get("grantV2Canonical").textValue();
        var raw="{\"actionId\":\"00000000-0000-0000-0000-000000000003\",\"expectedProposalControlVersion\":1,\"proposalRevision\":1,\"targetIntentDigest\":\""+AuthorityWire.sha(target)+"\",\"reason\":\"Independent review\"}";
        var intent=NativeAuthorityDecisionCodec.decode(TENANT,PROPOSAL,"approve",raw.getBytes(StandardCharsets.UTF_8),target);
        assertTrue(intent.canonicalIntent().endsWith(AuthorityWire.token(target)));assertEquals(AuthorityWire.sha(intent.canonicalIntent()),intent.intentDigest());
        assertNotEquals(intent.intentDigest(),NativeAuthorityDecisionCodec.decode(TENANT,PROPOSAL,"approve",raw.replace("Independent review","Second review").getBytes(StandardCharsets.UTF_8),target).intentDigest());
        assertThrows(IllegalArgumentException.class,()->NativeAuthorityDecisionCodec.decode(TENANT,PROPOSAL,"approve",raw.getBytes(StandardCharsets.UTF_8),target+"R0:"));
        assertThrows(IllegalArgumentException.class,()->NativeAuthorityDecisionCodec.decode(TENANT,PROPOSAL,"approve",raw.replace("\"reason\":","\"extra\":true,\"reason\":").getBytes(StandardCharsets.UTF_8),target));
    }
}
