package io.fireflysoftware.common.config.core.authority;

import io.r2dbc.spi.ConnectionFactory;
import org.fireflyframework.security.api.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.data.r2dbc.core.R2dbcEntityOperations;
import io.fireflysoftware.common.config.models.repositories.authority.NativeAuthorityOperationsRepository;
import org.springframework.transaction.reactive.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstitutionAuthorityTransactionTest {
    private static final String TENANT="00000000-0000-0000-0000-000000000001";
    private static final String EMPLOYEE="00000000-0000-0000-0000-000000000004";
    private static final String ACTION="00000000-0000-0000-0000-000000000003";
    static InstitutionAuthorityAuthorization.Admission admission() throws Exception {
        var vector=InstitutionAuthorityGrantV2CodecTest.vector();var intent=InstitutionAuthorityGrantV2Codec.decodeIssue(TENANT,InstitutionAuthorityGrantV2CodecTest.bytes(vector.get("grantV2Original").textValue()));
        var expiry=Instant.now().plusSeconds(300);
        var actor=new SecurityPrincipal("current-staff","https://issuer.example.test",TENANT,Set.of(),Set.of(),Map.of("exp",expiry,"tenant_id",TENANT,"principal_kind","STAFF","staff_actor_id",EMPLOYEE),null,null,Set.of(),Map.of());
        return new InstitutionAuthorityAuthorization(()->Mono.just(actor),(p,a,r,c)->Mono.just(Decision.permit()),Clock.systemUTC(),Set.of("https://issuer.example.test"),TENANT,true)
            .authorize(new InstitutionAuthorityAuthorization.Scope(TENANT,intent.request().grant().institutionId(),InstitutionAuthorityAuthorization.ResourceType.PROPOSAL,intent.request().grant().proposalId(),InstitutionAuthorityAuthorization.Operation.GRANT_ISSUE,intent.canonicalIntent(),intent.intentDigest(),false)).block();
    }
    static R2dbcEntityOperations operations(DatabaseClient database){var operations=mock(R2dbcEntityOperations.class);when(operations.getDatabaseClient()).thenReturn(database);return operations;}
    static InstitutionAuthorityDatabaseContext context(DatabaseClient database){return new InstitutionAuthorityDatabaseContext(operations(database),mock(NativeAuthorityOperationsRepository.class));}
    @Test void databaseContextCannotEscapeItsActualTransactionOrFactory() throws Exception {
        var db=mock(DatabaseClient.class);var factory=mock(ConnectionFactory.class);when(db.getConnectionFactory()).thenReturn(factory);
        var context=context(db);var admitted=admission();
        StepVerifier.create(context.establish(admitted,ACTION,admitted.validUntilEpochMillis())).expectError(ResponseStatusException.class).verify();
        var unrelated=TransactionSynchronizationManager.forCurrentTransaction().flatMap(manager->{manager.setActualTransactionActive(true);return context.establish(admitted,ACTION,admitted.validUntilEpochMillis());}).contextWrite(TransactionContextManager.createTransactionContext());
        StepVerifier.create(unrelated).expectError(ResponseStatusException.class).verify();
    }
    @Test void nativeTransactionRejectsDifferentConnectionFactoryAtConstruction() {
        var db=mock(DatabaseClient.class);when(db.getConnectionFactory()).thenReturn(mock(ConnectionFactory.class));
        var manager=new R2dbcTransactionManager(mock(ConnectionFactory.class));
        assertThrows(IllegalArgumentException.class,()->new InstitutionAuthorityTransaction(manager,operations(db),context(db)));
    }
    @Test void existingAmbientTransactionCannotRunAnyCommandWork() throws Exception {
        var factory=mock(ConnectionFactory.class);var db=mock(DatabaseClient.class);when(db.getConnectionFactory()).thenReturn(factory);
        var boundary=new InstitutionAuthorityTransaction(new R2dbcTransactionManager(factory),operations(db),context(db));
        var admitted=admission();var effects=new AtomicInteger();
        var call=TransactionSynchronizationManager.forCurrentTransaction().flatMap(manager->{manager.setActualTransactionActive(true);return boundary.execute(admitted,ACTION,admitted.validUntilEpochMillis(),()->{effects.incrementAndGet();return Mono.just("effect");});}).contextWrite(TransactionContextManager.createTransactionContext());
        StepVerifier.create(call).expectError(ResponseStatusException.class).verify();assertEquals(0,effects.get());
    }
}
