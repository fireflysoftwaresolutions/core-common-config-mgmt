package io.fireflysoftware.common.config.core.authority;

import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class TenantResourceBindingCodec {
    private TenantResourceBindingCodec() {}
    public static String namespaceDigest(Namespace namespace){return AuthorityWire.sha("FF_PLATFORM_TENANT_NAMESPACE_V1"+field(namespace.issuer())+field(namespace.platformTenant()));}
    public static String definitionCanonical(Definition definition){
        Objects.requireNonNull(definition);var epoch=definition.eligibility();
        return "FF_TENANT_RESOURCE_BINDING_DEFINITION_V1"+field(definition.bindingId())+field(definition.namespace().issuer())
            +field(definition.namespace().platformTenant())+field(definition.resourceTenant().value())+field(definition.version())
            +field(epoch.tenantGeneration())+field(epoch.statusId())+field(epoch.statusGeneration())+field(definition.notAfterEpochMillis());
    }
    public static String definitionDigest(Definition definition){return AuthorityWire.sha(definitionCanonical(definition));}
    public static Reference reference(Definition definition){return new Reference(definition.bindingId(),definition.version(),definitionDigest(definition));}
    public static String commandCanonical(Command command){
        Objects.requireNonNull(command);return "FF_TENANT_RESOURCE_BINDING_COMMAND_V1"+field(command.actionId())+field(command.proposalId())
            +field(command.operation().name())+field(command.expectedControlVersion())+field(command.expectedCurrentEligibility().tenantGeneration())
            +field(command.expectedCurrentEligibility().statusId())+field(command.expectedCurrentEligibility().statusGeneration())+field(definitionCanonical(command.definition()));
    }
    public static String commandDigest(Command command){return AuthorityWire.sha(commandCanonical(command));}

    public static String preparationCanonical(Preparation preparation){return "FF_TENANT_RESOURCE_BINDING_PREPARE_V1"+field(preparation.actionId())+field(preparation.notAfterEpochMillis())+field(commandCanonical(preparation.target()));}
    public static String preparationDigest(Preparation preparation){return AuthorityWire.sha(preparationCanonical(preparation));}
    public static String decisionCanonical(DecisionCommand decision){return "FF_TENANT_RESOURCE_BINDING_DECISION_V1"+field(decision.actionId())+field(decision.resourceTenant().value())+field(decision.proposalId())+field(decision.operation().name())+field(decision.expectedControlVersion())+field(decision.targetDigest())+field(decision.notAfterEpochMillis());}
    public static String decisionDigest(DecisionCommand decision){return AuthorityWire.sha(decisionCanonical(decision));}
    private static String field(Object value){var text=Objects.requireNonNull(value).toString();return "S"+text.getBytes(StandardCharsets.UTF_8).length+":"+text;}
}
