package io.fireflysoftware.common.config.web.tenantbinding.impl;

import io.fireflysoftware.common.config.core.authority.TenantResourceBindingService;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Current;
import io.fireflysoftware.common.config.web.tenantbinding.TenantResourceBindingCurrentIngress;
import java.util.Objects;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import reactor.core.publisher.Mono;

public final class TenantResourceBindingCurrentIngressImpl implements TenantResourceBindingCurrentIngress {
    private final TenantResourceBindingService service;
    public TenantResourceBindingCurrentIngressImpl(TenantResourceBindingService service){this.service=Objects.requireNonNull(service);}
    @Override public Mono<Current> current(R2dbcOperationBudget originalBudget){
        Objects.requireNonNull(originalBudget);
        return Mono.defer(()->{
            originalBudget.remaining();
            return service.current(originalBudget).switchIfEmpty(Mono.error(new IllegalStateException("Current tenant resource binding unavailable")))
                    .doOnNext(value->originalBudget.remaining()).timeout(originalBudget.remaining());
        });
    }
}
