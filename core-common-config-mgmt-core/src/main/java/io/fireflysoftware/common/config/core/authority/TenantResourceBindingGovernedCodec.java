package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingGovernedContracts.*;
import java.util.Objects;
import java.util.UUID;

/** Selects the existing single-owner codecs without duplicating canonicalization. */
public final class TenantResourceBindingGovernedCodec {
    private TenantResourceBindingGovernedCodec(){}
    public static UUID action(Intent intent){return switch(intent.kind()){case PREPARATION->intent.preparation().actionId();case DECISION->intent.decision().actionId();case CONTROL->intent.control().actionId();};}
    public static UUID tenant(Intent intent){return switch(intent.kind()){case PREPARATION->intent.preparation().target().definition().resourceTenant().value();case DECISION->intent.decision().resourceTenant().value();case CONTROL->intent.control().definition().resourceTenant().value();};}
    public static String canonical(Intent intent){return switch(intent.kind()){case PREPARATION->TenantResourceBindingCodec.preparationCanonical(intent.preparation());case DECISION->TenantResourceBindingCodec.decisionCanonical(intent.decision());case CONTROL->TenantResourceBindingCodec.commandCanonical(intent.control());};}
    public static String digest(Intent intent){return switch(intent.kind()){case PREPARATION->TenantResourceBindingCodec.preparationDigest(intent.preparation());case DECISION->TenantResourceBindingCodec.decisionDigest(intent.decision());case CONTROL->TenantResourceBindingCodec.commandDigest(intent.control());};}
    public static void original(ActionRequest request){Objects.requireNonNull(request);if(!digest(request.intent()).equals(request.originalIntentDigest()))throw new IllegalArgumentException("Exact original owner binding intent required");}
    public static InstitutionAuthorityAuthorization.Scope scope(Intent intent){
        Objects.requireNonNull(intent);String operation;UUID resource;InstitutionAuthorityAuthorization.ResourceType type;
        switch(intent.kind()){
            case PREPARATION->{operation="TENANT_RESOURCE_BINDING_PREPARE";resource=intent.preparation().target().proposalId();type=InstitutionAuthorityAuthorization.ResourceType.TENANT_RESOURCE_BINDING_PROPOSAL;}
            case DECISION->{operation="TENANT_RESOURCE_BINDING_"+intent.decision().operation().name();resource=intent.decision().proposalId();type=InstitutionAuthorityAuthorization.ResourceType.TENANT_RESOURCE_BINDING_PROPOSAL;}
            case CONTROL->{operation=intent.control().operation().name();resource=intent.control().definition().bindingId();type=InstitutionAuthorityAuthorization.ResourceType.TENANT_RESOURCE_BINDING;}
            default->throw new IllegalArgumentException("Exact binding operation required");
        }
        return new InstitutionAuthorityAuthorization.Scope(tenant(intent).toString(),null,type,resource.toString(),InstitutionAuthorityAuthorization.Operation.valueOf(operation),canonical(intent),digest(intent),true);
    }
}
