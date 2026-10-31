package io.fireflysoftware.common.config.web.config;

import org.fireflyframework.core.auditing.UtcOffsetDateTimeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import java.time.Clock;

@Configuration(proxyBeanMethods=false)
public class ConfigurationAuditingConfiguration {
    @Bean
    public DateTimeProvider configurationAuditDateTimeProvider() {
        return new UtcOffsetDateTimeProvider(Clock.systemUTC());
    }
}
