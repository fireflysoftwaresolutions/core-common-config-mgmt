package io.fireflysoftware.common.config.core.authority;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingGovernedContracts.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import reactor.core.publisher.Mono;
public interface TenantResourceBindingGovernedActions {
    Mono<PreparedIntent> prepareIntent(Intent intent,R2dbcOperationBudget original);
    Mono<ActionResult> execute(ActionRequest request,R2dbcOperationBudget original);
    Mono<Recovery> recover(ActionRequest request,R2dbcOperationBudget original);
}
