package io.fireflysoftware.common.config.interfaces.authority;

import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthorityRequestSchemaTest {
    @Test void nativeJsonRequestRecordsDeclareTheirExactClosedRequiredShape() {
        for(var type:List.of(GrantControlDraft.class,GrantControlPrepareRequest.class,GrantControlTarget.class,GrantIssueDraft.class,GrantIssueTarget.class,GrantIssueTargetV2.class,GrantPrepareRequest.class,InstitutionAuthorityDecisionRequest.class,NativeGrantActionReconciliationRequest.class,TenantEligibilityEpoch.class,TenantEligibilityPrepareRequest.class,TenantEligibilitySuspendRequest.class,TenantEligibilityTransitionDraft.class,TenantEligibilityTransitionTarget.class,StatusEligibilityPrepareRequest.class,StatusEligibilitySuspendRequest.class,StatusEligibilityTransitionDraft.class,StatusEligibilityTransitionTarget.class)) {
            var schema=type.getAnnotation(Schema.class);assertNotNull(schema,type.getSimpleName()+" must describe the actual strict JSON request record");assertEquals(Schema.AdditionalPropertiesValue.FALSE,schema.additionalProperties());assertEquals(Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet()),Set.of(schema.requiredProperties()));
        }
    }
    @Test void bootstrapIdentityReferencesAreRequiredNullableStrings() throws Exception {
        for(var field:List.of("existingInstitutionId","existingIssuerAuthorityId")) {var schema=GrantIssueDraft.class.getDeclaredField(field).getAnnotation(Schema.class);assertNotNull(schema);assertEquals(Set.of("string","null"),Set.of(schema.types()));assertTrue(schema.nullable());assertEquals(Schema.RequiredMode.REQUIRED,schema.requiredMode());}
    }
}
