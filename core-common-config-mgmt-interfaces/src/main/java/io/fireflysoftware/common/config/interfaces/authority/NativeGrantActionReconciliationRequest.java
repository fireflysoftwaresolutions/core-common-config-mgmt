package io.fireflysoftware.common.config.interfaces.authority;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"operation","originalBase64"})
public record NativeGrantActionReconciliationRequest(NativeGrantActionOperation operation,String originalBase64) {}
