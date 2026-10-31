package io.fireflysoftware.common.config.core.authority;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import reactor.core.publisher.Mono;
public interface TenantResourceBindingService {
 Mono<Current> current(R2dbcOperationBudget originalBudget);
 Mono<Completion> establish(Command command,R2dbcOperationBudget originalBudget);
 Mono<Completion> revoke(Command command,R2dbcOperationBudget originalBudget);
}
