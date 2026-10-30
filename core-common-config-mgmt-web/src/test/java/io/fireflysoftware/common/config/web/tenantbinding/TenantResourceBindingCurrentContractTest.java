package io.fireflysoftware.common.config.web.tenantbinding;

import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.WebFilter;
import static org.junit.jupiter.api.Assertions.*;

class TenantResourceBindingCurrentContractTest {
    @Test void currentSchemaIsUnique(){schema(Current.class,"Current");}
    @Test void referenceSchemaIsUnique(){schema(Reference.class,"Reference");}
    @Test void definitionSchemaIsUnique(){schema(Definition.class,"Definition");}
    @Test void namespaceSchemaIsUnique(){schema(Namespace.class,"Namespace");}
    @Test void resourceTenantSchemaIsUnique(){schema(ResourceTenant.class,"ResourceTenant");}
    @Test void eligibilitySchemaIsUnique(){schema(Eligibility.class,"Eligibility");}
    @Test void productionControllerExists(){assertNotNull(type("io.fireflysoftware.common.config.web.controllers.authority.TenantResourceBindingCurrentController"));}
    @Test void originalBudgetFilterExists(){assertTrue(WebFilter.class.isAssignableFrom(type("io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingCurrentBudgetFilter")));}
    @Test void exactGetOperationHasNoClientAuthorityParameters() throws Exception {
        var type=type("io.fireflysoftware.common.config.web.controllers.authority.TenantResourceBindingCurrentController");
        var method=type.getMethod("current",org.springframework.web.server.ServerWebExchange.class);
        assertArrayEquals(new String[]{"/api/v1/tenant-resource-bindings/current"},method.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value());
        assertEquals("observeCurrentTenantResourceBinding",method.getAnnotation(io.swagger.v3.oas.annotations.Operation.class).operationId());
        assertEquals("tenantResourceBindingBearer",type.getAnnotation(io.swagger.v3.oas.annotations.security.SecurityRequirement.class).name());
        assertEquals("io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts$Current",((java.lang.reflect.ParameterizedType)((java.lang.reflect.ParameterizedType)method.getGenericReturnType()).getActualTypeArguments()[0]).getActualTypeArguments()[0].getTypeName());
    }
    @Test void observationComponentsKeepOriginalWireNamesAndTypes(){
        var components=Current.class.getRecordComponents();
        assertArrayEquals(new String[]{"reference","definition","controlVersion","observedAtEpochMillis","validUntilEpochMillis"},java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new));
        assertArrayEquals(new Class<?>[]{Reference.class,Definition.class,long.class,long.class,long.class},java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
    }
    private static void schema(Class<?> type,String suffix){
        var resolved=ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
        assertEquals("#/components/schemas/TenantResourceBinding"+suffix,resolved.schema.get$ref());
    }
    private static Class<?> type(String name){return assertDoesNotThrow(()->Class.forName(name),"Required production CURRENT HTTP boundary is absent");}
}
