package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.models.config.NativeAuthorityRepositoryConfiguration;
import io.fireflysoftware.common.config.models.repositories.TenantRepository;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeAuthorityRepositoryConfigurationTest {
    @Configuration(proxyBeanMethods=false)
    @EnableR2dbcRepositories(basePackages="io.fireflysoftware.common.config.models.repositories",entityOperationsRef="r2dbcEntityTemplate",excludeFilters=@ComponentScan.Filter(type=FilterType.REGEX,pattern="io\\.fireflysoftware\\.common\\.config\\.models\\.repositories\\.authority\\..*"))
    static class BaselineRepositories {}
    @Test void baselineAndNativeRepositoryBindingsResolveOneOperationsInstance() {
        var factory=mock(ConnectionFactory.class);when(factory.getMetadata()).thenReturn(()->"PostgreSQL");
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(ConnectionFactory.class,()->factory);context.register(NativeAuthorityRepositoryConfiguration.class,BaselineRepositories.class);context.refresh();
            assertSame(context.getBean("r2dbcEntityTemplate"),context.getBean("institutionAuthorityEntityOperations"));assertNotNull(context.getBean(TenantRepository.class));verify(factory,never()).create();
        }
    }
}
