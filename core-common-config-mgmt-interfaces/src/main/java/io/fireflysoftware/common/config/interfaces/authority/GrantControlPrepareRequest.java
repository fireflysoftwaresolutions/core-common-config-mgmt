package io.fireflysoftware.common.config.interfaces.authority;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"actionId","targetActionId","notAfterEpochMillis","draft"})
public record GrantControlPrepareRequest(String actionId,String targetActionId,long notAfterEpochMillis,GrantControlDraft draft) {}
