/*
 * Copyright 2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityIntentCodecTest {
    private static final String TENANT = "00000000-0000-0000-0000-000000000001";
    private static final String INSTITUTION = "00000000-0000-0000-0000-000000000002";
    private static final String PROPOSAL = "00000000-0000-0000-0000-000000000004";

    private static com.fasterxml.jackson.databind.JsonNode vector() throws Exception {
        try (var stream = InstitutionAuthorityIntentCodecTest.class.getResourceAsStream("/authority/institution-authority-v1-golden.json")) {
            return new JsonMapper().readTree(stream);
        }
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test void literalGrantVectorBindsInstitutionWithoutBankOrCustomerEntity() throws Exception {
        var v = vector();
        var result = InstitutionAuthorityIntentCodec.decodeIssue(TENANT, bytes(v.get("original").textValue()));
        assertEquals(v.get("canonical").textValue(), result.canonicalIntent());
        assertEquals(v.get("digest").textValue(), result.intentDigest());
        assertEquals(v.get("original").textValue(), result.originalJson());
        assertEquals(INSTITUTION, result.request().institutionId());
        assertEquals(List.of("ACCOUNTING_BOOK", "PRODUCT_ISSUANCE"), result.request().uses().stream().map(Enum::name).toList());
        assertThrows(UnsupportedOperationException.class, () -> result.request().uses().clear());
    }

    @ParameterizedTest @ValueSource(strings = {"1.0", "1e0", "-0", "9223372036854775808", "null", "\"1\"", "-1"})
    void rejectsNoncanonicalOrInvalidVersion(String token) throws Exception {
        var json = vector().get("original").textValue().replace("\"version\":1", "\"version\":" + token);
        assertThrows(IllegalArgumentException.class, () -> InstitutionAuthorityIntentCodec.decodeIssue(TENANT, bytes(json)));
    }

    @Test void rejectsUnknownDuplicateMissingAndNullFields() throws Exception {
        var original = vector().get("original").textValue();
        for (var body : List.of(original.replace("{", "{\"bankId\":null,"), original.replace("{", "{\"version\":1,"), original.replace("\"version\":1,", ""), original.replace("Approved institution", "\\ud800"))) {
            assertThrows(IllegalArgumentException.class, () -> InstitutionAuthorityIntentCodec.decodeIssue(TENANT, bytes(body)));
        }
        assertThrows(IllegalArgumentException.class, () -> InstitutionAuthorityIntentCodec.decodeIssue(TENANT, new byte[]{(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> InstitutionAuthorityIntentCodec.decodeIssue(TENANT, bytes(original + " {}")));
    }

    @Test void checksUseOrderLifetimeAndExpectedHead() throws Exception {
        var original = vector().get("original").textValue();
        for (var body : List.of(original.replace("ACCOUNTING_BOOK\",\"PRODUCT_ISSUANCE", "PRODUCT_ISSUANCE\",\"ACCOUNTING_BOOK"), original.replace("1824249600000", "1792713600000"), original.replace("\"expectedGrantHeadVersion\":0", "\"expectedGrantHeadVersion\":1"), original.replace("PRODUCT_ISSUANCE", "ACCOUNTING_BOOK"))) {
            assertThrows(IllegalArgumentException.class, () -> InstitutionAuthorityIntentCodec.decodeIssue(TENANT, bytes(body)));
        }
    }

    @Test void bindsScopeActionAndEveryTargetField() throws Exception {
        var original = vector().get("original").textValue();
        var digest = InstitutionAuthorityIntentCodec.decodeIssue(TENANT, bytes(original)).intentDigest();
        assertNotEquals(digest, InstitutionAuthorityIntentCodec.decodeIssue("00000000-0000-0000-0000-000000000009", bytes(original)).intentDigest());
        for (var body : List.of(original.replace("000000000003", "000000000007"), original.replace("Approved institution", "Revised reason"), original.replace("case:fictional-1", "case:fictional-2"), original.replace("000000000006", "000000000008"))) {
            assertNotEquals(digest, InstitutionAuthorityIntentCodec.decodeIssue(TENANT, bytes(body)).intentDigest());
        }
        assertThrows(IllegalArgumentException.class, () -> InstitutionAuthorityIntentCodec.decodeIssue("00000000-0000-0000-0000-000000000000", bytes(original)));
    }

    @Test void decisionBindsCurrentOperationProposalAndCompleteTargetDigest() {
        var json = "{\"actionId\":\"00000000-0000-0000-0000-000000000003\",\"expectedProposalControlVersion\":0,\"proposalRevision\":1,\"targetIntentDigest\":\"" + "a".repeat(64) + "\",\"reason\":\"Independent review\"}";
        var approved = InstitutionAuthorityIntentCodec.decodeDecision(TENANT, INSTITUTION, PROPOSAL, "approve", bytes(json));
        assertNotEquals(approved.intentDigest(), InstitutionAuthorityIntentCodec.decodeDecision(TENANT, INSTITUTION, PROPOSAL, "reject", bytes(json)).intentDigest());
        assertThrows(IllegalArgumentException.class, () -> InstitutionAuthorityIntentCodec.decodeDecision(TENANT, INSTITUTION, PROPOSAL, "impersonate", bytes(json)));
    }
}
