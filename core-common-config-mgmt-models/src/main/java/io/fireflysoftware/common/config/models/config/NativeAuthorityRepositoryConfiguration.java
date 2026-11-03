package io.fireflysoftware.common.config.models.config;

import io.fireflysoftware.common.config.models.repositories.authority.NativeAuthorityOperationsRepository;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@Configuration(proxyBeanMethods=false)
@EnableR2dbcRepositories(basePackageClasses=NativeAuthorityOperationsRepository.class,entityOperationsRef="institutionAuthorityEntityOperations")
public class NativeAuthorityRepositoryConfiguration {
    @Bean({"institutionAuthorityEntityOperations","r2dbcEntityTemplate"}) public R2dbcEntityTemplate institutionAuthorityEntityOperations(ConnectionFactory connectionFactory) {
        return new R2dbcEntityTemplate(connectionFactory);
    }
}
