/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.common.config.sdk;

import static org.junit.jupiter.api.Assertions.*;
import io.fireflysoftware.common.config.sdk.model.DocumentIssuerUse;
import org.junit.jupiter.api.Test;

/** Requires the unchanged generator to consume the actual updated controller-produced schema. */
class DocumentIssuerQuotePurposeGeneratedContractTest {
    @Test void originalGeneratedSdkKeepsDepositAndAddsDistinctQuotePurpose(){assertEquals("DEPOSIT_ACCOUNT_ENROLLMENT",DocumentIssuerUse.PurposeEnum.valueOf("DEPOSIT_ACCOUNT_ENROLLMENT").name());assertEquals("INSURANCE_TERM_PC_QUOTE",DocumentIssuerUse.PurposeEnum.valueOf("INSURANCE_TERM_PC_QUOTE").name());assertEquals(2,DocumentIssuerUse.PurposeEnum.values().length);}
    @Test void issuerSdkDoesNotInventPolicyIssuanceOrSignaturePermission(){for(var value:java.util.List.of("INSURANCE_POLICY_ISSUANCE","INSURANCE_CONTRACT_SIGNATURE","TERM_PC_INSURANCE_QUOTE"))assertThrows(IllegalArgumentException.class,()->DocumentIssuerUse.PurposeEnum.valueOf(value));}
}
