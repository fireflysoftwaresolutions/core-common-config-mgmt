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
package io.fireflysoftware.common.config.interfaces.authority;

import java.util.List;

@io.swagger.v3.oas.annotations.media.Schema(additionalProperties=io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE,requiredProperties={"existingInstitutionId","existingIssuerAuthorityId","expectedGrantHeadVersion","uses","effectiveFromEpochMillis","effectiveToEpochMillis","reason","evidenceReferences"})
public record GrantIssueDraft(@io.swagger.v3.oas.annotations.media.Schema(types={"string","null"},nullable=true,requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) String existingInstitutionId, @io.swagger.v3.oas.annotations.media.Schema(types={"string","null"},nullable=true,requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) String existingIssuerAuthorityId, long expectedGrantHeadVersion, List<InstitutionAuthorityUse> uses, long effectiveFromEpochMillis, long effectiveToEpochMillis, String reason, List<String> evidenceReferences) {
    public GrantIssueDraft { uses = List.copyOf(uses); evidenceReferences = List.copyOf(evidenceReferences); }
}
