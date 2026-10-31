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

import io.fireflysoftware.common.config.interfaces.authority.*;
import java.util.*;

public final class InstitutionAuthorityIntentCodec {
    private InstitutionAuthorityIntentCodec() {}

    public static InstitutionAuthorityIntent<GrantIssueTarget> decodeIssue(String tenantId, byte[] bytes) {
        AuthorityWire.uuid(tenantId);
        var original = AuthorityWire.original(bytes,65536);
        var target = AuthorityWire.record(AuthorityWire.parse(original),GrantIssueTarget.class,Set.of());
        validate(target);
        var canonical = AuthorityWire.token("FF_TENANT_INSTITUTION_GRANT_V1") + AuthorityWire.recordToken(
            tenantId,"GRANT_ISSUE",target.actionId(),target.proposalId(),target.proposalRevision(),target.grantId(),target.institutionId(),target.issuerAuthorityId(),target.version(),target.expectedGrantHeadVersion(),target.uses(),target.effectiveFromEpochMillis(),target.effectiveToEpochMillis(),target.reason(),target.evidenceReferences());
        return new InstitutionAuthorityIntent<>(original,target,canonical,AuthorityWire.sha(canonical));
    }

    public static InstitutionAuthorityIntent<InstitutionAuthorityDecisionRequest> decodeDecision(String tenantId, String institutionId, String proposalId, String operation, byte[] bytes) {
        AuthorityWire.uuid(tenantId); AuthorityWire.uuid(institutionId); AuthorityWire.uuid(proposalId);
        if (!Set.of("submit","approve","reject","withdraw").contains(operation)) throw AuthorityWire.bad();
        var original = AuthorityWire.original(bytes,65536);
        var request = AuthorityWire.record(AuthorityWire.parse(original),InstitutionAuthorityDecisionRequest.class,Set.of());
        AuthorityWire.uuid(request.actionId()); AuthorityWire.digest(request.targetIntentDigest()); AuthorityWire.text(request.reason(),500);
        if (request.expectedProposalControlVersion() < 0 || request.proposalRevision() < 1) throw AuthorityWire.bad();
        var canonical = AuthorityWire.token("FF_INSTITUTION_AUTHORITY_DECISION_V1") + AuthorityWire.recordToken(tenantId,institutionId,proposalId,operation,request);
        return new InstitutionAuthorityIntent<>(original,request,canonical,AuthorityWire.sha(canonical));
    }

    private static void validate(GrantIssueTarget t) {
        for (var id : List.of(t.actionId(),t.proposalId(),t.grantId(),t.institutionId(),t.issuerAuthorityId())) AuthorityWire.uuid(id);
        if (t.proposalRevision() < 1 || t.expectedGrantHeadVersion() < 0 || t.expectedGrantHeadVersion() == Long.MAX_VALUE || t.version() != t.expectedGrantHeadVersion()+1) throw AuthorityWire.bad();
        if (t.effectiveFromEpochMillis() < -2208988800000L || t.effectiveToEpochMillis() > 253402300799999L || t.effectiveFromEpochMillis() >= t.effectiveToEpochMillis()) throw AuthorityWire.bad();
        if (t.uses().isEmpty() || t.uses().size() > 3) throw AuthorityWire.bad();
        for (int i=1;i<t.uses().size();i++) if (t.uses().get(i-1).name().compareTo(t.uses().get(i).name()) >= 0) throw AuthorityWire.bad();
        AuthorityWire.text(t.reason(),500);
        if (t.evidenceReferences().size() > 32 || new HashSet<>(t.evidenceReferences()).size() != t.evidenceReferences().size()) throw AuthorityWire.bad();
        t.evidenceReferences().forEach(s -> AuthorityWire.text(s,500));
    }
}
