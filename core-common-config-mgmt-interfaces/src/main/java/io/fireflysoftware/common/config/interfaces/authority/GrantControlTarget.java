package io.fireflysoftware.common.config.interfaces.authority;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"actionId","proposalId","proposalRevision","grantId","version","institutionId","issuerAuthorityId","expectedControlVersion","expectedState","targetState","expectedEpoch","reason","evidenceReferences","notAfterEpochMillis"})
public record GrantControlTarget(String actionId,String proposalId,long proposalRevision,String grantId,long version,String institutionId,String issuerAuthorityId,long expectedControlVersion,String expectedState,String targetState,TenantEligibilityEpoch expectedEpoch,String reason,java.util.List<String> evidenceReferences,long notAfterEpochMillis) {
    public GrantControlTarget { evidenceReferences=java.util.List.copyOf(evidenceReferences); }
}
