package io.fireflysoftware.common.config.web.controllers.authority;

import org.fireflyframework.web.idempotency.annotation.DisableIdempotency;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import static org.junit.jupiter.api.Assertions.*;

class NativeAuthorityRouteBoundaryTest {
    @Test void everyNativeMutationReentersItsCurrentPolicyAndDurableActionGate() {
        int mutations=0;
        for(var controller:java.util.List.of(TenantEligibilityController.class,StatusEligibilityController.class,PlatformTenantEligibilityController.class,InstitutionAuthorityController.class,PlatformInstitutionAuthorityController.class,NativeAuthorityActionController.class,PlatformNativeAuthorityActionController.class)) for(var method:controller.getDeclaredMethods()) {
            if(method.isAnnotationPresent(PostMapping.class)) {mutations++;assertTrue(method.isAnnotationPresent(DisableIdempotency.class),controller.getSimpleName()+"."+method.getName()+" must not return a generic cached response before the current native policy gate");}
        }
        assertTrue(mutations>0);
    }
}
