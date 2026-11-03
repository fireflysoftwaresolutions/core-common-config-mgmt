package io.fireflysoftware.common.config.core.authority;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingGovernedContracts.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import reactor.core.publisher.Mono;
public interface TenantResourceBindingRecoveryReader {
    Mono<Recovery> recover(ActionRequest request,R2dbcOperationBudget original);
}
