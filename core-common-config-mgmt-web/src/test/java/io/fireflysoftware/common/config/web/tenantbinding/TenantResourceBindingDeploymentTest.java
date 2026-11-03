package io.fireflysoftware.common.config.web.tenantbinding;
import io.fireflysoftware.common.config.core.authority.TenantResourceBindingService;
import io.fireflysoftware.common.config.core.authority.TenantResourceBindingLookupAuthorization;
import io.fireflysoftware.common.config.web.config.TenantResourceBindingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TenantResourceBindingDeploymentTest {
 @Test void defaultAndFalseCreateNoMappingBeansOrDependencies(){for(var properties:List.<Map<String,Object>>of(Map.of(),Map.of("firefly.configuration.tenant-resource-binding.enabled","false"))){try(var context=new AnnotationConfigApplicationContext()){context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("control",properties));context.register(TenantResourceBindingConfiguration.class);context.refresh();assertTrue(context.getBeansOfType(TenantResourceBindingService.class).isEmpty());assertTrue(context.getBeansOfType(TenantResourceBindingLookupAuthorization.class).isEmpty());}}}
 @Test void explicitEnableWithoutGenuineProvidersRefusesStartup(){try(var context=new AnnotationConfigApplicationContext()){context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("control",Map.of("firefly.configuration.tenant-resource-binding.enabled","true")));context.register(TenantResourceBindingConfiguration.class);assertThrows(Exception.class,context::refresh);}}
 @Test void metadataGenerationProfileCannotActivateMapping(){try(var context=new AnnotationConfigApplicationContext()){context.getEnvironment().setActiveProfiles("openapi-gen");context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("control",Map.of("firefly.configuration.tenant-resource-binding.enabled","true")));context.register(TenantResourceBindingConfiguration.class);context.refresh();assertTrue(context.getBeansOfType(TenantResourceBindingService.class).isEmpty());}}
 @Test void nativeRepositoryUsesTheOriginalNamedEntityTemplate(){var repositories=TenantResourceBindingConfiguration.class.getAnnotation(org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories.class);assertNotNull(repositories);assertEquals("institutionAuthorityEntityOperations",repositories.entityOperationsRef());assertArrayEquals(new Class<?>[]{io.fireflysoftware.common.config.models.repositories.tenantbinding.TenantResourceBindingNativeRepository.class},repositories.basePackageClasses());}

}
