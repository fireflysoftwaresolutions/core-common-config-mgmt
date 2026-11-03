package io.fireflysoftware.common.config.web.tenantbinding;

import io.fireflysoftware.common.config.models.repositories.tenantbinding.TenantResourceBindingNativeRepository;
import io.fireflysoftware.common.config.web.CommonPlatformConfigMgmtApplication;
import io.fireflysoftware.common.config.web.config.TenantResourceBindingConfiguration;
import java.beans.Introspector;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import static org.junit.jupiter.api.Assertions.*;

class TenantResourceBindingMainRepositoryTest {
    private static String name(){return Introspector.decapitalize(TenantResourceBindingNativeRepository.class.getSimpleName());}
    private static DefaultListableBeanFactory registry(){var result=new DefaultListableBeanFactory();result.setAllowBeanDefinitionOverriding(false);return result;}
    private static void register(Class<?> owner,DefaultListableBeanFactory registry)throws ReflectiveOperationException {
        Class<?> type=Class.forName("org.springframework.data.r2dbc.repository.config.R2dbcRepositoriesRegistrar");
        Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object value=constructor.newInstance();
        ((EnvironmentAware)value).setEnvironment(new StandardEnvironment());((ResourceLoaderAware)value).setResourceLoader(new DefaultResourceLoader());
        ((ImportBeanDefinitionRegistrar)value).registerBeanDefinitions(AnnotationMetadata.introspect(owner),registry);
    }
    @Test void defaultMainDoesNotDiscoverTheOptInNativeRepository()throws Exception {
        var registry=registry();register(CommonPlatformConfigMgmtApplication.class,registry);
        assertFalse(registry.containsBeanDefinition(name()));assertEquals(0,registry.getSingletonCount());
        var condition=TenantResourceBindingConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertArrayEquals(new String[]{"firefly.configuration.tenant-resource-binding.enabled"},condition.name());
        assertEquals("true",condition.havingValue());assertFalse(condition.matchIfMissing());
    }
    @Test void enabledDedicatedRegistrationIsUniqueAndUsesItsOwningTemplate()throws Exception {
        var registry=registry();register(CommonPlatformConfigMgmtApplication.class,registry);
        assertDoesNotThrow(()->register(TenantResourceBindingConfiguration.class,registry));
        assertTrue(registry.containsBeanDefinition(name()));
        var reference=assertInstanceOf(RuntimeBeanReference.class,registry.getBeanDefinition(name()).getPropertyValues().get("entityOperations"));
        assertEquals("institutionAuthorityEntityOperations",reference.getBeanName());assertEquals(0,registry.getSingletonCount());
    }
    @Test void mainExcludesOnlyTheTwoDedicatedAuthorityRepositoryNamespaces(){
        var filters=CommonPlatformConfigMgmtApplication.class.getAnnotation(EnableR2dbcRepositories.class).excludeFilters();
        assertEquals(1,filters.length);assertArrayEquals(new String[]{"io\\.fireflysoftware\\.common\\.config\\.models\\.repositories\\.(authority|tenantbinding)\\..*"},filters[0].pattern());
    }
}
