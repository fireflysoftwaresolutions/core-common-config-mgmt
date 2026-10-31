/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.models.entities.authority;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.util.UUID;
import org.springframework.data.relational.core.mapping.Embedded;
import java.time.OffsetDateTime;

@Table("cfg_native_grant_version")
public record GrantVersionRow(@Id @Embedded(onEmpty=Embedded.OnEmpty.USE_NULL) GrantVersionKey id, UUID institutionId, UUID issuerAuthorityId, String[] uses, Long effectiveFromEpochMillis, Long effectiveToEpochMillis, Long tenantGeneration, UUID statusId, Long statusGeneration, UUID proposalId, Long proposalRevision, UUID issueActionId, String definitionEncoding, String canonicalDefinition, String definitionDigest, OffsetDateTime recordedAt) {}
