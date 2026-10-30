package io.fireflysoftware.common.config.interfaces.authority;

import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** The authenticated platform namespace is deliberately distinct from a Configuration resource tenant. */
public final class TenantResourceBindingContracts {
    private TenantResourceBindingContracts() {}
    public enum Operation { TENANT_RESOURCE_BINDING_ESTABLISH, TENANT_RESOURCE_BINDING_REVOKE }
    public enum State { ACTIVE, REVOKED }
    public enum DecisionOperation { SUBMIT, APPROVE, REJECT, WITHDRAW }
    public record Preparation(UUID actionId,Command target,long notAfterEpochMillis) {
        public Preparation {id(actionId);Objects.requireNonNull(target);positive(notAfterEpochMillis);if(notAfterEpochMillis>253402300799999L||actionId.equals(target.actionId()))throw invalid();}
    }
    public record DecisionCommand(UUID actionId,ResourceTenant resourceTenant,UUID proposalId,DecisionOperation operation,
            long expectedControlVersion,String targetDigest,long notAfterEpochMillis) {
        public DecisionCommand {id(actionId);Objects.requireNonNull(resourceTenant);id(proposalId);Objects.requireNonNull(operation);if(expectedControlVersion<0||expectedControlVersion>=Long.MAX_VALUE-1)throw invalid();digest(targetDigest);positive(notAfterEpochMillis);if(notAfterEpochMillis>253402300799999L)throw invalid();}
    }
    public record Proposal(UUID proposalId,String targetDigest,String state,long controlVersion,long notAfterEpochMillis) {
        public Proposal {id(proposalId);digest(targetDigest);if(!java.util.Set.of("DRAFT","SUBMITTED","APPROVED","REJECTED","WITHDRAWN","EXECUTION_ADMITTED").contains(state)||controlVersion<0||controlVersion==Long.MAX_VALUE)throw invalid();positive(notAfterEpochMillis);}
    }
    public record GovernanceReceipt(UUID actionId,String intentDigest,Proposal proposal,long nativeUntilEpochMillis,UUID receiptId,UUID auditId,UUID eventId) {
        public GovernanceReceipt {id(actionId);digest(intentDigest);Objects.requireNonNull(proposal);positive(nativeUntilEpochMillis);id(receiptId);id(auditId);id(eventId);}
    }

    @Schema(name="TenantResourceBindingNamespace")
    public record Namespace(String issuer,String platformTenant) {
        public Namespace {
            text(issuer,500);text(platformTenant,1024);
            var uri=URI.create(issuer);
            if(!uri.isAbsolute()||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw invalid();
        }
        @Override public String toString(){return "PlatformTenantNamespace";}
    }
    @Schema(name="TenantResourceBindingResourceTenant")
    public record ResourceTenant(UUID value) {
        public ResourceTenant {id(value);}
    }
    @Schema(name="TenantResourceBindingEligibility")
    public record Eligibility(long tenantGeneration,UUID statusId,long statusGeneration) {
        public Eligibility {if(tenantGeneration<0||tenantGeneration==Long.MAX_VALUE||statusGeneration<0||statusGeneration==Long.MAX_VALUE)throw invalid();id(statusId);}
    }
    @Schema(name="TenantResourceBindingDefinition")
    public record Definition(UUID bindingId,Namespace namespace,ResourceTenant resourceTenant,long version,
            Eligibility eligibility,long notAfterEpochMillis) {
        public Definition {id(bindingId);Objects.requireNonNull(namespace);Objects.requireNonNull(resourceTenant);positive(version);Objects.requireNonNull(eligibility);positive(notAfterEpochMillis);if(notAfterEpochMillis>253402300799999L)throw invalid();}
    }
    @Schema(name="TenantResourceBindingReference")
    public record Reference(UUID bindingId,long version,String definitionDigest) {
        public Reference {id(bindingId);positive(version);digest(definitionDigest);}
    }
    public record Command(UUID actionId,UUID proposalId,Operation operation,Definition definition,
            Eligibility expectedCurrentEligibility,long expectedControlVersion) {
        public Command {id(actionId);id(proposalId);Objects.requireNonNull(operation);Objects.requireNonNull(definition);Objects.requireNonNull(expectedCurrentEligibility);if(expectedControlVersion<0||expectedControlVersion>=Long.MAX_VALUE-1)throw invalid();}
    }
    public record Completion(UUID actionId,String intentDigest,Reference reference,long controlVersion,State state,
            UUID receiptId,UUID auditId,UUID eventId) {
        public Completion {id(actionId);digest(intentDigest);Objects.requireNonNull(reference);positive(controlVersion);Objects.requireNonNull(state);id(receiptId);id(auditId);id(eventId);}
    }
    @Schema(name="TenantResourceBindingCurrent")
    public record Current(Reference reference,Definition definition,long controlVersion,long observedAtEpochMillis,long validUntilEpochMillis) {
        public Current {Objects.requireNonNull(reference);Objects.requireNonNull(definition);positive(controlVersion);positive(observedAtEpochMillis);if(validUntilEpochMillis<=observedAtEpochMillis)throw invalid();}
    }
    private static void text(String value,int max){
        if(value==null||value.isBlank()||value.getBytes(StandardCharsets.UTF_8).length>max||value.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF))throw invalid();
    }
    private static void id(UUID value){if(value==null||value.equals(new UUID(0,0)))throw invalid();}
    private static void positive(long value){if(value<1||value==Long.MAX_VALUE)throw invalid();}
    private static void digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Exact tenant resource binding required");}
}
