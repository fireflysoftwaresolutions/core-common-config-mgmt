package io.fireflysoftware.common.config.core.authority;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import reactor.core.publisher.Mono;
public interface TenantResourceBindingGovernanceService {
 Mono<GovernanceReceipt> prepare(Preparation preparation,R2dbcOperationBudget originalBudget);
 Mono<GovernanceReceipt> decide(DecisionCommand decision,R2dbcOperationBudget originalBudget);
}
