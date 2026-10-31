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

@Table("cfg_native_proposal_control")
public record ProposalControlRow(@Id @Embedded(onEmpty=Embedded.OnEmpty.USE_NULL) ProposalKey id, String state, Long controlVersion, UUID decisionId, UUID executionActionId) {}
