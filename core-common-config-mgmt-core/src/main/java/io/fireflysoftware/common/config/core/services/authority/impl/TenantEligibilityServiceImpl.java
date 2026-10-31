package io.fireflysoftware.common.config.core.services.authority.impl;

import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.core.services.authority.TenantEligibilityService;
import io.fireflysoftware.common.config.interfaces.authority.*;
import io.fireflysoftware.common.config.models.entities.authority.*;
import io.fireflysoftware.common.config.models.repositories.authority.*;
import io.r2dbc.spi.R2dbcException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import static io.fireflysoftware.common.config.core.authority.InstitutionAuthorityAuthorization.*;

@org.springframework.stereotype.Service
@org.springframework.context.annotation.Profile("!openapi-gen")
public class TenantEligibilityServiceImpl implements TenantEligibilityService {
    private final InstitutionAuthorityAuthorization authorization;
    private final InstitutionAuthorityTransaction transactions;
    private final NativeAuthorityOperationsRepository operations;
    private final NativeProposalRepository proposals;
    private final NativeProposalRevisionRepository revisions;
    private final Clock clock;
    private final String platformTenant;
    public TenantEligibilityServiceImpl(InstitutionAuthorityAuthorization authorization,InstitutionAuthorityTransaction transactions,NativeAuthorityOperationsRepository operations,NativeProposalRepository proposals,NativeProposalRevisionRepository revisions,@org.springframework.beans.factory.annotation.Qualifier("institutionAuthorityClock") Clock clock) {
        this.authorization=Objects.requireNonNull(authorization);this.transactions=Objects.requireNonNull(transactions);this.operations=Objects.requireNonNull(operations);this.proposals=Objects.requireNonNull(proposals);this.revisions=Objects.requireNonNull(revisions);this.clock=Objects.requireNonNull(clock);this.platformTenant=authorization.platformTenantId();
    }
    @Override public Mono<NativeAuthorityActionResult<TenantEligibilityView>> suspendTenant(String tenantId,byte[] original) {return suspendTenant(tenantId,original,false);}
    @Override public Mono<NativeAuthorityActionResult<TenantEligibilityView>> suspendTenantAsPlatform(String tenantId,byte[] original) {return suspendTenant(tenantId,original,true);}
    private Mono<NativeAuthorityActionResult<TenantEligibilityView>> suspendTenant(String tenantId,byte[] original,boolean platformRecovery) {
        return Mono.defer(()-> {
            var intent=TenantEligibilityIntentCodec.decodeSuspend(tenantId,original);var request=intent.request();
            return execute(tenantId,tenantId,ResourceType.TENANT_ELIGIBILITY,Operation.TENANT_SUSPEND,request.actionId(),request.notAfterEpochMillis(),"TENANT_SUSPEND","APPLIED",intent,TenantEligibilityView.class,
                raw->TenantEligibilityIntentCodec.decodeSuspend(tenantId,raw),()->operations.suspendTenant(UUID.fromString(tenantId),original),platformRecovery);
        });
    }
    @Override public Mono<NativeAuthorityActionResult<PreparedTenantEligibilityTransition>> prepareTenantTransition(String tenantId,byte[] original) {return prepareTenantTransition(tenantId,original,false);}
    @Override public Mono<NativeAuthorityActionResult<PreparedTenantEligibilityTransition>> prepareTenantTransitionAsPlatform(String tenantId,byte[] original) {return prepareTenantTransition(tenantId,original,true);}
    private Mono<NativeAuthorityActionResult<PreparedTenantEligibilityTransition>> prepareTenantTransition(String tenantId,byte[] original,boolean platformRecovery) {
        return Mono.defer(()-> {
            var intent=TenantEligibilityIntentCodec.decodePrepare(tenantId,original);var request=intent.request();
            return execute(tenantId,tenantId,ResourceType.TENANT_ELIGIBILITY,Operation.PREPARE,request.actionId(),request.notAfterEpochMillis(),"TENANT_PREPARE","PREPARED",intent,PreparedTenantEligibilityTransition.class,
                raw->TenantEligibilityIntentCodec.decodePrepare(tenantId,raw),()->operations.prepareTransition(UUID.fromString(tenantId),UUID.fromString(tenantId),false,original),platformRecovery);
        });
    }
    @Override public Mono<NativeAuthorityActionResult<TenantEligibilityView>> transitionTenant(String tenantId,byte[] original) {return transitionTenant(tenantId,original,false);}
    @Override public Mono<NativeAuthorityActionResult<TenantEligibilityView>> transitionTenantAsPlatform(String tenantId,byte[] original) {return transitionTenant(tenantId,original,true);}
    private Mono<NativeAuthorityActionResult<TenantEligibilityView>> transitionTenant(String tenantId,byte[] original,boolean platformRecovery) {
        return Mono.defer(()-> {
            var intent=TenantEligibilityIntentCodec.decodeTransition(tenantId,original);var request=intent.request();
            return execute(tenantId,tenantId,ResourceType.TENANT_ELIGIBILITY,Operation.TENANT_TRANSITION,request.actionId(),request.notAfterEpochMillis(),"TENANT_TRANSITION","APPLIED",intent,TenantEligibilityView.class,
                raw->TenantEligibilityIntentCodec.decodeTransition(tenantId,raw),()->operations.transitionTenant(UUID.fromString(tenantId),original),platformRecovery);
        });
    }
    @Override public Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideLifecycleProposal(String tenantId,String proposalId,String operation,byte[] original) {return decideTenantProposal(tenantId,proposalId,operation,original,false);}
    @Override public Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideTenantProposalAsPlatform(String tenantId,String proposalId,String operation,byte[] original) {return decideTenantProposal(tenantId,proposalId,operation,original,true);}
    private Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideTenantProposal(String tenantId,String proposalId,String operation,byte[] original,boolean platformRecovery) {
        return Mono.defer(()->readProposal(tenantId,proposalId,"TENANT_TRANSITION",tenantId,platformRecovery).flatMap(retained-> {
            if(!"TENANT_TRANSITION".equals(retained.proposal().targetOperation())) return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,"Lifecycle proposal required"));
            var intent=NativeAuthorityDecisionCodec.decode(tenantId,proposalId,operation,original,retained.revision().canonicalTarget());var request=intent.request();
            return execute(tenantId,proposalId,ResourceType.PROPOSAL,Operation.valueOf(operation.toUpperCase(Locale.ROOT)),request.actionId(),retained.revision().notAfterEpochMillis(),operation.toUpperCase(Locale.ROOT),"DECIDED",intent,NativeAuthorityDecisionOutcome.class,
                raw->NativeAuthorityDecisionCodec.decode(tenantId,proposalId,operation,raw,retained.revision().canonicalTarget()),()->operations.decide(UUID.fromString(tenantId),UUID.fromString(proposalId),operation,original),platformRecovery);
        }));
    }

    @Override public Mono<NativeAuthorityActionResult<StatusEligibilityView>> suspendStatus(String statusId,byte[] original) {
        return Mono.defer(()-> {
            var intent=TenantEligibilityIntentCodec.decodeStatusSuspend(statusId,original);var request=intent.request();
            return execute(platformTenant,statusId,ResourceType.STATUS_ELIGIBILITY,Operation.STATUS_SUSPEND,request.actionId(),request.notAfterEpochMillis(),"STATUS_SUSPEND","APPLIED",intent,StatusEligibilityView.class,
                raw->TenantEligibilityIntentCodec.decodeStatusSuspend(statusId,raw),()->operations.suspendStatus(UUID.fromString(platformTenant),UUID.fromString(statusId),original),true);
        });
    }
    @Override public Mono<NativeAuthorityActionResult<PreparedStatusEligibilityTransition>> prepareStatusTransition(String statusId,byte[] original) {
        return Mono.defer(()-> {
            var intent=TenantEligibilityIntentCodec.decodeStatusPrepare(statusId,original);var request=intent.request();
            return execute(platformTenant,statusId,ResourceType.STATUS_ELIGIBILITY,Operation.PREPARE,request.actionId(),request.notAfterEpochMillis(),"STATUS_PREPARE","PREPARED",intent,PreparedStatusEligibilityTransition.class,
                raw->TenantEligibilityIntentCodec.decodeStatusPrepare(statusId,raw),()->operations.prepareTransition(UUID.fromString(platformTenant),UUID.fromString(statusId),true,original),true);
        });
    }
    @Override public Mono<NativeAuthorityActionResult<StatusEligibilityView>> transitionStatus(String statusId,byte[] original) {
        return Mono.defer(()-> {
            var intent=TenantEligibilityIntentCodec.decodeStatusTransition(statusId,original);var request=intent.request();
            return execute(platformTenant,statusId,ResourceType.STATUS_ELIGIBILITY,Operation.STATUS_TRANSITION,request.actionId(),request.notAfterEpochMillis(),"STATUS_TRANSITION","APPLIED",intent,StatusEligibilityView.class,
                raw->TenantEligibilityIntentCodec.decodeStatusTransition(statusId,raw),()->operations.transitionStatus(UUID.fromString(platformTenant),UUID.fromString(statusId),original),true);
        });
    }
    @Override public Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideStatusProposal(String statusId,String proposalId,String operation,byte[] original) {
        return Mono.defer(()->readProposal(platformTenant,proposalId,"STATUS_TRANSITION",statusId,true).flatMap(retained-> {
            var intent=NativeAuthorityDecisionCodec.decode(platformTenant,proposalId,operation,original,retained.revision().canonicalTarget());var request=intent.request();
            return execute(platformTenant,proposalId,ResourceType.PROPOSAL,Operation.valueOf(operation.toUpperCase(Locale.ROOT)),request.actionId(),retained.revision().notAfterEpochMillis(),operation.toUpperCase(Locale.ROOT),"DECIDED",intent,NativeAuthorityDecisionOutcome.class,
                raw->NativeAuthorityDecisionCodec.decode(platformTenant,proposalId,operation,raw,retained.revision().canonicalTarget()),()->operations.decide(UUID.fromString(platformTenant),UUID.fromString(proposalId),operation,original),true);
        }));
    }
    private record RetainedProposal(ProposalRow proposal,ProposalRevisionRow revision) {}
    private Mono<RetainedProposal> readProposal(String tenantId,String proposalId,String targetOperation,String resourceId,boolean platformRecovery) {
        var intent=NativeAuthorityDecisionCodec.proposalRead(tenantId,proposalId,targetOperation,resourceId);var action=UUID.randomUUID().toString();
        var scope=new Scope(tenantId,null,ResourceType.PROPOSAL,proposalId,Operation.HISTORY,intent.canonicalIntent(),intent.intentDigest(),platformRecovery);
        return authorization.authorize(scope).flatMap(admission->transactions.execute(admission,action,Math.addExact(clock.millis(),30000),()->
            proposals.findByIdTenantIdAndIdProposalIdAndTargetOperationAndResourceId(UUID.fromString(tenantId),UUID.fromString(proposalId),targetOperation,UUID.fromString(resourceId))
                .flatMap(proposal->revisions.findByIdTenantIdAndIdProposalIdAndIdRevision(UUID.fromString(tenantId),UUID.fromString(proposalId),1L).map(revision->new RetainedProposal(proposal,revision)))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Native proposal not found"))))).onErrorMap(NativeAuthorityFailureMapper::translate);
    }
    private <T> Mono<NativeAuthorityActionResult<T>> execute(String tenantId,String resourceId,ResourceType resourceType,Operation operation,String actionId,long deadline,String nativeOperation,String resultKind,InstitutionAuthorityIntent<?> intent,Class<T> resultType,Function<byte[],? extends InstitutionAuthorityIntent<?>> decoder,java.util.function.Supplier<Mono<String>> work,boolean platformRecovery) {
        var scope=new Scope(tenantId,null,resourceType,resourceId,operation,intent.canonicalIntent(),intent.intentDigest(),platformRecovery);
        return authorization.authorize(scope).flatMap(admission->transactions.execute(admission,actionId,deadline,()->work.get().map(result->NativeAuthorityResultReader.read(result,admission,actionId,nativeOperation,resultKind,intent,resultType,decoder))))
            .retryWhen(Retry.max(2).filter(TenantEligibilityServiceImpl::serializationFailure).onRetryExhaustedThrow((spec,signal)->signal.failure())).onErrorMap(NativeAuthorityFailureMapper::translate);
    }
    private static boolean serializationFailure(Throwable error) {
        for(int depth=0;error!=null && depth<16;depth++,error=error.getCause()) if(error instanceof R2dbcException nativeError && "40001".equals(nativeError.getSqlState())) return true;
        return false;
    }
}
