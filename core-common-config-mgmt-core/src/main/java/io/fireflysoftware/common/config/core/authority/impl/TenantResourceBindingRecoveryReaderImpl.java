package io.fireflysoftware.common.config.core.authority.impl;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.interfaces.authority.NativeAuthorityActionSnapshot;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingGovernedContracts.*;
import io.fireflysoftware.common.config.models.entities.authority.ActionRow;
import io.fireflysoftware.common.config.models.repositories.authority.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.util.Objects;
import java.util.UUID;
import static io.fireflysoftware.common.config.core.authority.TenantResourceBindingGovernedCodec.*;

/** Historical mapped reads under the existing exact native READ_ACTION RLS context; no command entrypoint. */
public final class TenantResourceBindingRecoveryReaderImpl implements TenantResourceBindingRecoveryReader {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final InstitutionAuthorityAuthorization authority;
    private final InstitutionAuthorityTransaction transactions;
    private final NativeActionRepository actions;
    private final NativeReceiptRepository receipts;
    public TenantResourceBindingRecoveryReaderImpl(InstitutionAuthorityAuthorization authority,InstitutionAuthorityTransaction transactions,NativeActionRepository actions,NativeReceiptRepository receipts){this.authority=Objects.requireNonNull(authority);this.transactions=Objects.requireNonNull(transactions);this.actions=Objects.requireNonNull(actions);this.receipts=Objects.requireNonNull(receipts);}
    @Override public Mono<Recovery> recover(ActionRequest request,R2dbcOperationBudget original){
        return Mono.defer(()->{
            original.remaining();original(request);var intent=request.intent();var tenant=tenant(intent);var action=action(intent);var read=NativeAuthorityActionReadCodec.read(tenant.toString(),action.toString());
            var scope=new InstitutionAuthorityAuthorization.Scope(tenant.toString(),null,InstitutionAuthorityAuthorization.ResourceType.ACTION,action.toString(),InstitutionAuthorityAuthorization.Operation.READ_ACTION,read.canonicalIntent(),read.intentDigest(),true);
            return authority.authorize(scope,original).flatMap(admission->authority.reauthorize(admission).then(Mono.defer(()->
                transactions.execute(admission,action.toString(),admission.validUntilEpochMillis(),()->
                    actions.findByIdTenantIdAndIdActionId(tenant,action).flatMap(row->{
                        sameActor(row,admission.actor());
                        return receipts.findByIdTenantIdAndIdActionId(tenant,action).switchIfEmpty(Mono.error(unavailable())).map(receipt->{
                            var snapshot=NativeAuthorityActionReadVerifier.retained(tenant.toString(),action.toString(),row,receipt);sameActorTenant(snapshot,admission.actor());
                            var expected=TenantResourceBindingGovernedCodec.scope(intent);
                            if(!row.canonicalIntent().equals(canonical(intent))||!row.intentDigest().equals(request.originalIntentDigest())||!row.operation().equals(expected.operation().name())||!row.resourceId().toString().equals(expected.resourceId()))return new Recovery(RecoveryStatus.INTENT_CONFLICT,action,request.originalIntentDigest(),null);
                            return new Recovery(RecoveryStatus.FOUND,action,request.originalIntentDigest(),snapshot);
                        });
                    }).defaultIfEmpty(new Recovery(RecoveryStatus.NOT_OBSERVED_AT_THIS_READ,action,request.originalIntentDigest(),null))
                    .flatMap(result->authority.reauthorize(admission).then(Mono.fromSupplier(()->{admission.requireCurrent();return result;}))))
                .timeout(admission.remaining())))
                .flatMap(result->authority.reauthorize(admission).then(Mono.fromSupplier(()->{admission.requireCurrent();return result;}))));
        }).timeout(Mono.defer(()->Mono.delay(original.remaining())));
    }
    private static void sameActor(ActionRow row,InstitutionAuthorityAuthorization.Actor actor){
        if(!Objects.equals(row.actorKind(),actor.kind())||!Objects.equals(row.actorIssuer(),actor.issuer())||!Objects.equals(row.actorSubject(),actor.subject())
                ||!Objects.equals(row.actorEmployeeId(),actor.employeeId()==null?null:UUID.fromString(actor.employeeId()))||!Objects.equals(row.actorClientId(),actor.clientId()))throw denied();
    }
    private static void sameActorTenant(NativeAuthorityActionSnapshot snapshot,InstitutionAuthorityAuthorization.Actor actor){
        try{var retained=JSON.readTree(snapshot.canonicalReceiptJson()).get("actor").get("tenantId");if(retained==null||!retained.isTextual()||!retained.textValue().equals(actor.tenantId()))throw denied();}
        catch(ResponseStatusException refusal){throw refusal;}catch(Exception error){throw unavailable();}
    }
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN);}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}
}
