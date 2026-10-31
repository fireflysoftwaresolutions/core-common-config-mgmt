package io.fireflysoftware.common.config.interfaces.authority;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"grantId","version","issuerAuthorityId","expectedControlVersion","expectedState","targetState","reason","evidenceReferences"})
public record GrantControlDraft(String grantId,long version,String issuerAuthorityId,long expectedControlVersion,String expectedState,String targetState,String reason,java.util.List<String> evidenceReferences) {
    public GrantControlDraft { evidenceReferences=java.util.List.copyOf(evidenceReferences); }
}
