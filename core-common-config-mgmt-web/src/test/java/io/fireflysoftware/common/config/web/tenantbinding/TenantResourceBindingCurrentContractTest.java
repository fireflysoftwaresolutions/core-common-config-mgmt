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
        var responses=java.util.Arrays.stream(method.getAnnotation(io.swagger.v3.oas.annotations.responses.ApiResponses.class).value()).collect(java.util.stream.Collectors.toMap(io.swagger.v3.oas.annotations.responses.ApiResponse::responseCode,value->value));
        assertTrue(responses.get("200").useReturnTypeSchema());
        assertEquals("Security authentication failure may have an empty body; post-authentication CURRENT failure returns code-only JSON.",responses.get("401").description());
        for(String status:new String[]{"400","401","403","503"}){
            var error=responses.get(status);assertFalse(error.useReturnTypeSchema());assertEquals(1,error.content().length);assertEquals("application/json",error.content()[0].mediaType());assertEquals(io.fireflysoftware.common.config.web.controllers.authority.TenantResourceBindingCurrentController.Failure.class,error.content()[0].schema().implementation());
        }
        assertEquals("Security denial may have an empty body; CURRENT lookup denial returns code-only JSON.",responses.get("403").description());
        var failure=ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(io.fireflysoftware.common.config.web.controllers.authority.TenantResourceBindingCurrentController.Failure.class).resolveAsRef(true));
        assertEquals("#/components/schemas/TenantResourceBindingCurrentFailure",failure.schema.get$ref());
        assertEquals(java.util.Set.of("code"),failure.referencedSchemas.get("TenantResourceBindingCurrentFailure").getProperties().keySet());
    }
    @Test void observationComponentsKeepOriginalWireNamesAndTypes(){
        var components=Current.class.getRecordComponents();
        assertArrayEquals(new String[]{"reference","definition","controlVersion","observedAtEpochMillis","validUntilEpochMillis"},java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName).toArray(String[]::new));
        assertArrayEquals(new Class<?>[]{Reference.class,Definition.class,long.class,long.class,long.class},java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
    }
    @Test void genuineCustomizersExposeCurrentWithoutIdempotencyInput() throws Exception {
        var controllerType=io.fireflysoftware.common.config.web.controllers.authority.TenantResourceBindingCurrentController.class;
        var method=controllerType.getMethod("current",org.springframework.web.server.ServerWebExchange.class);
        var controller=new io.fireflysoftware.common.config.web.controllers.authority.TenantResourceBindingCurrentController(budget->{throw new AssertionError("Metadata must not invoke CURRENT");});
        var operation=new io.swagger.v3.oas.models.Operation().operationId(method.getAnnotation(io.swagger.v3.oas.annotations.Operation.class).operationId());
        new org.fireflyframework.web.openapi.IdempotencyOperationCustomizer().customize(operation,new org.springframework.web.method.HandlerMethod(controller,method));
        var api=new io.swagger.v3.oas.models.OpenAPI().paths(new io.swagger.v3.oas.models.Paths().addPathItem("/api/v1/tenant-resource-bindings/current",new io.swagger.v3.oas.models.PathItem().get(operation)));
        new org.fireflyframework.web.openapi.IdempotencyOpenAPICustomizer(new org.fireflyframework.web.idempotency.config.IdempotencyProperties()).customise(api);
        assertNotNull(operation.getExtensions());assertEquals(Boolean.TRUE,operation.getExtensions().get("x-disable-idempotency"));
        assertTrue(operation.getParameters()==null||operation.getParameters().isEmpty());assertNull(operation.getRequestBody());
        assertEquals("tenantResourceBindingBearer",controllerType.getAnnotation(io.swagger.v3.oas.annotations.security.SecurityRequirement.class).name());
        assertEquals("observeCurrentTenantResourceBinding",operation.getOperationId());
    }
    private static void schema(Class<?> type,String suffix){
        var resolved=ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type).resolveAsRef(true));
        assertEquals("#/components/schemas/TenantResourceBinding"+suffix,resolved.schema.get$ref());
    }
    private static Class<?> type(String name){return assertDoesNotThrow(()->Class.forName(name),"Required production CURRENT HTTP boundary is absent");}
}
