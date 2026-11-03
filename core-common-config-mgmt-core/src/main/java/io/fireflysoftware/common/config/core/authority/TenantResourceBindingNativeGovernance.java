package io.fireflysoftware.common.config.core.authority;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Mono;
public interface TenantResourceBindingNativeGovernance {
 ConnectionFactory connectionFactory();
 Mono<GovernanceReceipt> prepare(InstitutionAuthorityAuthorization.Admission admission,Preparation preparation);
 Mono<GovernanceReceipt> decide(InstitutionAuthorityAuthorization.Admission admission,DecisionCommand decision);
}
