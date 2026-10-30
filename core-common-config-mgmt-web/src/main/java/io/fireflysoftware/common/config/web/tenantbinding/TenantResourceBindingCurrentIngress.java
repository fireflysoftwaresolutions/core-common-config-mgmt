package io.fireflysoftware.common.config.web.tenantbinding;

import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Current;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import reactor.core.publisher.Mono;

public interface TenantResourceBindingCurrentIngress {
    Mono<Current> current(R2dbcOperationBudget originalBudget);
}
