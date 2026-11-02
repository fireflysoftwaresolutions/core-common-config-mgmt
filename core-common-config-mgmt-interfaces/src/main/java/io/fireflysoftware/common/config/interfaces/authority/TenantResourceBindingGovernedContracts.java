package io.fireflysoftware.common.config.interfaces.authority;

import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.UUID;

/** Binding governance inputs carry proposed resources, never caller identity or native authority. */
public final class TenantResourceBindingGovernedContracts {
    private TenantResourceBindingGovernedContracts(){}
    public enum Kind { PREPARATION, DECISION, CONTROL }
    public enum ActionStatus { COMMITTED, COMMITTED_RESPONSE_UNAVAILABLE, OUTCOME_UNKNOWN }
    public enum RecoveryStatus { FOUND, NOT_OBSERVED_AT_THIS_READ, INTENT_CONFLICT }
    @Schema(name="TenantResourceBindingGovernedIntent",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Intent(Kind kind,Preparation preparation,DecisionCommand decision,Command control) {
        public Intent {
            Objects.requireNonNull(kind);
            if((preparation==null?0:1)+(decision==null?0:1)+(control==null?0:1)!=1
                    ||(kind==Kind.PREPARATION)!=(preparation!=null)||(kind==Kind.DECISION)!=(decision!=null)||(kind==Kind.CONTROL)!=(control!=null))throw invalid();
        }
        @Override public String toString(){return "TenantResourceBindingGovernedIntent";}
    }
    @Schema(name="TenantResourceBindingPreparedIntent",description="Read-only owner digest; no reservation, grant, native preparation or guarantee of later admission.")
    public record PreparedIntent(UUID actionId,String intentDigest) {
        public PreparedIntent {id(actionId);digest(intentDigest);}
    }
    @Schema(name="TenantResourceBindingGovernedActionRequest",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record ActionRequest(Intent intent,String originalIntentDigest) {
        public ActionRequest {Objects.requireNonNull(intent);digest(originalIntentDigest);}
        @Override public String toString(){return "TenantResourceBindingGovernedActionRequest";}
    }
    @Schema(name="TenantResourceBindingGovernedActionResult")
    public record ActionResult(ActionStatus status,UUID actionId,String intentDigest,GovernanceReceipt governanceReceipt,Completion completion) {
        public ActionResult {Objects.requireNonNull(status);id(actionId);digest(intentDigest);int results=(governanceReceipt==null?0:1)+(completion==null?0:1);if(status==ActionStatus.COMMITTED?results!=1:results!=0)throw invalid();}
    }
    @Schema(name="TenantResourceBindingActionRecovery",description="Historical original-action observation only; absence at this read does not permit another mutation or establish a current binding.")
    public record Recovery(RecoveryStatus status,UUID actionId,String intentDigest,NativeAuthorityActionSnapshot snapshot) {
        public Recovery {Objects.requireNonNull(status);id(actionId);digest(intentDigest);if((status==RecoveryStatus.FOUND)!=(snapshot!=null)||snapshot!=null&&!snapshot.historicalOnly())throw invalid();}
    }
    private static void id(UUID value){if(value==null||value.equals(new UUID(0,0)))throw invalid();}
    private static void digest(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Exact original binding governance intent required");}
}
