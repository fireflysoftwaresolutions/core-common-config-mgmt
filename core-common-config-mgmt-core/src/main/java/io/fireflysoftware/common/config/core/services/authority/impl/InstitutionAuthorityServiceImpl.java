package io.fireflysoftware.common.config.core.services.authority.impl;

import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.core.services.authority.InstitutionAuthorityService;
import io.fireflysoftware.common.config.interfaces.authority.*;
import io.fireflysoftware.common.config.models.repositories.authority.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import static io.fireflysoftware.common.config.core.authority.InstitutionAuthorityAuthorization.*;

@org.springframework.stereotype.Service
@org.springframework.context.annotation.Profile("!openapi-gen")
public class InstitutionAuthorityServiceImpl implements InstitutionAuthorityService {
    private final InstitutionAuthorityAuthorization authorization;
    private final InstitutionAuthorityTransaction transactions;
    private final NativeAuthorityOperationsRepository operations;
    private final NativeProposalRepository proposals;
    private final NativeProposalRevisionRepository revisions;
    private final Clock clock;
    public InstitutionAuthorityServiceImpl(InstitutionAuthorityAuthorization authorization,InstitutionAuthorityTransaction transactions,NativeAuthorityOperationsRepository operations,NativeProposalRepository proposals,NativeProposalRevisionRepository revisions,@org.springframework.beans.factory.annotation.Qualifier("institutionAuthorityClock") Clock clock) {
        this.authorization=Objects.requireNonNull(authorization);this.transactions=Objects.requireNonNull(transactions);this.operations=Objects.requireNonNull(operations);this.proposals=Objects.requireNonNull(proposals);this.revisions=Objects.requireNonNull(revisions);this.clock=Objects.requireNonNull(clock);
    }
    @Override public Mono<NativeAuthorityActionResult<PreparedInstitutionAuthorityGrant>> prepareBootstrapGrant(String tenant,byte[] original){return prepare(tenant,original,true);}
    @Override public Mono<NativeAuthorityActionResult<PreparedInstitutionAuthorityGrant>> prepareGrant(String tenant,byte[] original){return prepare(tenant,original,false);}
    private Mono<NativeAuthorityActionResult<PreparedInstitutionAuthorityGrant>> prepare(String tenant,byte[] original,boolean platform) {
        return Mono.defer(()-> {
            var intent=InstitutionAuthorityGrantPrepareCodec.decode(tenant,original);var request=intent.request();
            return command(tenant,request.draft().existingInstitutionId(),tenant,ResourceType.INSTITUTION_AUTHORITY,Operation.PREPARE,request.actionId(),request.notAfterEpochMillis(),"GRANT_PREPARE","PREPARED",intent,PreparedInstitutionAuthorityGrant.class,raw->InstitutionAuthorityGrantPrepareCodec.decode(tenant,raw),()->operations.prepareGrant(UUID.fromString(tenant),original),platform);
        });
    }
    @Override public Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideBootstrapProposal(String tenant,String institution,String proposal,String operation,byte[] original){return decide(tenant,institution,proposal,operation,original,true,"GRANT_ISSUE",institution);}
    @Override public Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideProposal(String tenant,String institution,String proposal,String operation,byte[] original){return decide(tenant,institution,proposal,operation,original,false,"GRANT_ISSUE",institution);}
    private Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decide(String tenant,String institution,String proposal,String operation,byte[] original,boolean platform,String targetOperation,String resource) {
        return Mono.defer(()-> {
            var read=NativeAuthorityDecisionCodec.proposalRead(tenant,proposal,targetOperation,resource);var readScope=scope(tenant,institution,proposal,ResourceType.PROPOSAL,Operation.HISTORY,read,platform);
            return authorization.authorize(readScope).flatMap(admission->transactions.execute(admission,UUID.randomUUID().toString(),Math.addExact(clock.millis(),30000),()->
                proposals.findByIdTenantIdAndIdProposalIdAndTargetOperationAndResourceId(UUID.fromString(tenant),UUID.fromString(proposal),targetOperation,UUID.fromString(resource))
                    .flatMap(header->revisions.findByIdTenantIdAndIdProposalIdAndIdRevision(UUID.fromString(tenant),UUID.fromString(proposal),1L))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Native grant proposal not found")))))
                .flatMap(retained->{
                    var intent=NativeAuthorityDecisionCodec.decode(tenant,proposal,operation,original,retained.canonicalTarget());var request=intent.request();
                    return command(tenant,institution,proposal,ResourceType.PROPOSAL,Operation.valueOf(operation.toUpperCase(Locale.ROOT)),request.actionId(),retained.notAfterEpochMillis(),operation.toUpperCase(Locale.ROOT),"DECIDED",intent,NativeAuthorityDecisionOutcome.class,raw->NativeAuthorityDecisionCodec.decode(tenant,proposal,operation,raw,retained.canonicalTarget()),()->operations.decide(UUID.fromString(tenant),UUID.fromString(proposal),operation,original),platform);
                });
        }).onErrorMap(NativeAuthorityFailureMapper::translate);
    }
    @Override public Mono<NativeAuthorityActionResult<TenantInstitutionGrantDefinition>> issueBootstrapGrant(String tenant,String institution,byte[] original){return issue(tenant,institution,original,true);}
    @Override public Mono<NativeAuthorityActionResult<TenantInstitutionGrantDefinition>> issueGrant(String tenant,String institution,byte[] original){return issue(tenant,institution,original,false);}
    private Mono<NativeAuthorityActionResult<TenantInstitutionGrantDefinition>> issue(String tenant,String institution,byte[] original,boolean platform) {
        return Mono.defer(()-> {
            var intent=InstitutionAuthorityGrantV2Codec.decodeIssue(tenant,original);var grant=intent.request().grant();if(!institution.equals(grant.institutionId())) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,"Exact grant institution required"));
            var read=NativeAuthorityDecisionCodec.proposalRead(tenant,grant.proposalId(),"GRANT_ISSUE",institution);var readScope=scope(tenant,institution,grant.proposalId(),ResourceType.PROPOSAL,Operation.HISTORY,read,platform);
            return authorization.authorize(readScope).flatMap(admission->transactions.execute(admission,UUID.randomUUID().toString(),Math.addExact(clock.millis(),30000),()->
                proposals.findByIdTenantIdAndIdProposalIdAndTargetOperationAndResourceId(UUID.fromString(tenant),UUID.fromString(grant.proposalId()),"GRANT_ISSUE",UUID.fromString(institution))
                    .flatMap(header->revisions.findByIdTenantIdAndIdProposalIdAndIdRevision(UUID.fromString(tenant),UUID.fromString(grant.proposalId()),grant.proposalRevision()))
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,"Native grant proposal not found")))))
                .flatMap(retained->command(tenant,institution,grant.proposalId(),ResourceType.PROPOSAL,Operation.GRANT_ISSUE,grant.actionId(),retained.notAfterEpochMillis(),"GRANT_ISSUE","APPLIED",intent,TenantInstitutionGrantDefinition.class,raw->InstitutionAuthorityGrantV2Codec.decodeIssue(tenant,raw),()->operations.issueGrant(UUID.fromString(tenant),original),platform));
        }).onErrorMap(NativeAuthorityFailureMapper::translate);
    }
    @Override public Mono<InstitutionAuthorityGrantObservation> observe(String tenant,String institution,String issuer,InstitutionAuthorityUse use) {return observe(tenant,institution,issuer,use,false);}
    @Override public Mono<InstitutionAuthorityGrantObservation> observeAsPlatform(String tenant,String institution,String issuer,InstitutionAuthorityUse use) {return observe(tenant,institution,issuer,use,true);}
    private Mono<InstitutionAuthorityGrantObservation> observe(String tenant,String institution,String issuer,InstitutionAuthorityUse use,boolean platform) {
        return Mono.defer(()-> {
            var intent=InstitutionAuthorityGrantReadCodec.observe(tenant,institution,issuer,use);var scope=scope(tenant,institution,issuer,ResourceType.GRANT,Operation.OBSERVE,intent,platform);
            return authorization.authorize(scope).flatMap(admission->transactions.execute(admission,UUID.randomUUID().toString(),Math.addExact(clock.millis(),30000),()->operations.observeGrant(UUID.fromString(tenant),UUID.fromString(institution),UUID.fromString(issuer),use.name()).map(raw->{
                var result=InstitutionAuthorityGrantReadVerifier.observation(tenant,intent.request(),raw);if(result.validUntilEpochMillis()!=null && result.validUntilEpochMillis()>admission.validUntilEpochMillis()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Finite current grant observation required");return result;
            })));
        }).onErrorMap(NativeAuthorityFailureMapper::translate);
    }
    @Override public Mono<InstitutionAuthorityGrantHistory> history(String tenant,String institution,String issuer,String grant,long version) {return history(tenant,institution,issuer,grant,version,false);}
    @Override public Mono<InstitutionAuthorityGrantHistory> historyAsPlatform(String tenant,String institution,String issuer,String grant,long version) {return history(tenant,institution,issuer,grant,version,true);}
    private Mono<InstitutionAuthorityGrantHistory> history(String tenant,String institution,String issuer,String grant,long version,boolean platform) {
        return Mono.defer(()-> {
            var intent=InstitutionAuthorityGrantReadCodec.history(tenant,institution,issuer,grant,version);var scope=scope(tenant,institution,grant,ResourceType.GRANT,Operation.HISTORY,intent,platform);
            return authorization.authorize(scope).flatMap(admission->transactions.execute(admission,UUID.randomUUID().toString(),Math.addExact(clock.millis(),30000),()->operations.readGrantHistory(UUID.fromString(tenant),UUID.fromString(institution),UUID.fromString(issuer),UUID.fromString(grant),version).map(raw->InstitutionAuthorityGrantReadVerifier.history(tenant,intent.request(),raw))));
        }).onErrorMap(NativeAuthorityFailureMapper::translate);
    }

    @Override public Mono<NativeAuthorityActionResult<PreparedInstitutionAuthorityControl>> prepareControl(String tenant,String institution,byte[] original){return prepareControl(tenant,institution,original,false);}
    @Override public Mono<NativeAuthorityActionResult<PreparedInstitutionAuthorityControl>> prepareControlAsPlatform(String tenant,String institution,byte[] original){return prepareControl(tenant,institution,original,true);}
    private Mono<NativeAuthorityActionResult<PreparedInstitutionAuthorityControl>> prepareControl(String tenant,String institution,byte[] original,boolean platform) {
        return Mono.defer(()-> {
            var intent=InstitutionAuthorityGrantControlCodec.decodePrepare(tenant,institution,original);var request=intent.request();return command(tenant,institution,request.draft().grantId(),ResourceType.GRANT,Operation.PREPARE,request.actionId(),request.notAfterEpochMillis(),"GRANT_CONTROL_PREPARE","PREPARED",intent,PreparedInstitutionAuthorityControl.class,raw->InstitutionAuthorityGrantControlCodec.decodePrepare(tenant,institution,raw),()->operations.prepareGrantControl(UUID.fromString(tenant),UUID.fromString(institution),original),platform);
        });
    }
    @Override public Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideControlProposal(String tenant,String institution,String grant,String proposal,String operation,byte[] original){return decide(tenant,institution,proposal,operation,original,false,"GRANT_CONTROL",grant);}
    @Override public Mono<NativeAuthorityActionResult<NativeAuthorityDecisionOutcome>> decideControlProposalAsPlatform(String tenant,String institution,String grant,String proposal,String operation,byte[] original){return decide(tenant,institution,proposal,operation,original,true,"GRANT_CONTROL",grant);}
    @Override public Mono<NativeAuthorityActionResult<GrantAuthorityControlView>> control(String tenant,String institution,byte[] original){return control(tenant,institution,original,false);}
    @Override public Mono<NativeAuthorityActionResult<GrantAuthorityControlView>> controlAsPlatform(String tenant,String institution,byte[] original){return control(tenant,institution,original,true);}
    private Mono<NativeAuthorityActionResult<GrantAuthorityControlView>> control(String tenant,String institution,byte[] original,boolean platform) {
        return Mono.defer(()-> {
            var intent=InstitutionAuthorityGrantControlCodec.decodeTarget(tenant,original);var request=intent.request();if(!institution.equals(request.institutionId())) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,"Exact grant-control institution required"));return command(tenant,institution,request.grantId(),ResourceType.GRANT,Operation.GRANT_CONTROL,request.actionId(),request.notAfterEpochMillis(),"GRANT_CONTROL","APPLIED",intent,GrantAuthorityControlView.class,raw->InstitutionAuthorityGrantControlCodec.decodeTarget(tenant,raw),()->operations.controlGrant(UUID.fromString(tenant),original),platform);
        });
    }
    private static Scope scope(String tenant,String institution,String resource,ResourceType type,Operation operation,InstitutionAuthorityIntent<?> intent,boolean platform) {return new Scope(tenant,institution,type,resource,operation,intent.canonicalIntent(),intent.intentDigest(),platform);}
    private <T> Mono<NativeAuthorityActionResult<T>> command(String tenant,String institution,String resource,ResourceType type,Operation operation,String action,long deadline,String nativeOperation,String resultKind,InstitutionAuthorityIntent<?> intent,Class<T> resultType,Function<byte[],? extends InstitutionAuthorityIntent<?>> decoder,Supplier<Mono<String>> work,boolean platform) {
        return authorization.authorize(scope(tenant,institution,resource,type,operation,intent,platform))
            .flatMap(admission->transactions.execute(admission,action,deadline,()->work.get().map(raw->NativeAuthorityResultReader.read(raw,admission,action,nativeOperation,resultKind,intent,resultType,decoder))))
            .retryWhen(Retry.max(2).filter(InstitutionAuthorityServiceImpl::serializationFailure).onRetryExhaustedThrow((spec,signal)->signal.failure())).onErrorMap(NativeAuthorityFailureMapper::translate);
    }
    private static boolean serializationFailure(Throwable error) {
        for(int depth=0;error!=null && depth<16;depth++,error=error.getCause()) if(error instanceof io.r2dbc.spi.R2dbcException nativeError && "40001".equals(nativeError.getSqlState())) return true;return false;
    }
}
