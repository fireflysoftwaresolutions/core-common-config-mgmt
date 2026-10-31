package io.fireflysoftware.common.config.web.tenantbinding;

import io.fireflysoftware.common.config.core.authority.*;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.*;
import io.fireflysoftware.common.config.web.tenantbinding.impl.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class TenantResourceBindingCurrentIngressTest {
    @Test void sameOriginalReachesExistingCoreService(){
        var time=new TenantResourceBindingCurrentBudgetTest.Time();var original=budget(time);var calls=new AtomicInteger();var expected=current(time);
        var ingress=ingress(b->{calls.incrementAndGet();assertSame(original,b);return Mono.just(expected);});assertSame(expected,ingress.current(original).block());assertEquals(1,calls.get());
    }
    @Test void expiredOriginalRefusesBeforeCore(){
        var time=new TenantResourceBindingCurrentBudgetTest.Time();var original=budget(time);time.advance(Duration.ofSeconds(5));var calls=new AtomicInteger();
        assertThrows(RuntimeException.class,()->ingress(b->{calls.incrementAndGet();return Mono.just(current(time));}).current(original).block());assertEquals(0,calls.get());
    }
    @Test void lateCoreResultRefuses(){
        var time=new TenantResourceBindingCurrentBudgetTest.Time();var original=budget(time);var value=current(time);
        assertThrows(RuntimeException.class,()->ingress(b->{time.advance(Duration.ofSeconds(5));return Mono.just(value);}).current(original).block());
    }
    @Test void emptyCoreNeverBecomesSuccess(){var time=new TenantResourceBindingCurrentBudgetTest.Time();assertThrows(RuntimeException.class,()->ingress(b->Mono.empty()).current(budget(time)).block());}
    @Test void actualOwnerCutoffAlsoBindsPreCommit(){
        var time=new TenantResourceBindingCurrentBudgetTest.Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=TenantResourceBindingCurrentBudgetTest.exchange();
        filter.filter(exchange,e->{TenantResourceBindingCurrentBudgetFilter.retainCurrent(e,current(time));time.advance(Duration.ofSeconds(2));e.getResponse().setStatusCode(HttpStatus.OK);return e.getResponse().setComplete();}).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());
    }
    @Test void aLaterObservationCannotExtendAnEarlierOwnerCutoff(){
        var time=new TenantResourceBindingCurrentBudgetTest.Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=TenantResourceBindingCurrentBudgetTest.exchange();
        filter.filter(exchange,e->{TenantResourceBindingCurrentBudgetFilter.retainCurrent(e,current(time));time.advance(Duration.ofSeconds(1));TenantResourceBindingCurrentBudgetFilter.retainCurrent(e,current(time));time.advance(Duration.ofSeconds(1));return e.getResponse().setComplete();}).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());
    }
    @Test void futureOrOverlongObservationIsRefused(){
        var time=new TenantResourceBindingCurrentBudgetTest.Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=TenantResourceBindingCurrentBudgetTest.exchange();var value=current(time);
        filter.filter(exchange,e->{assertThrows(IllegalStateException.class,()->TenantResourceBindingCurrentBudgetFilter.retainCurrent(e,new Current(value.reference(),value.definition(),1,time.millis()+1,time.millis()+1000)));assertThrows(IllegalStateException.class,()->TenantResourceBindingCurrentBudgetFilter.retainCurrent(e,new Current(value.reference(),value.definition(),1,time.millis(),time.millis()+5001)));return Mono.empty();}).block();
    }
    @Test void expiredQueuedCurrentBodyIsDiscardedAndReplaced(){
        var time=new TenantResourceBindingCurrentBudgetTest.Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=TenantResourceBindingCurrentBudgetTest.exchange();
        filter.filter(exchange,e->{TenantResourceBindingCurrentBudgetFilter.retainCurrent(e,current(time));e.getResponse().setStatusCode(HttpStatus.OK);return e.getResponse().writeWith(Mono.defer(()->{time.advance(Duration.ofSeconds(2));return Mono.just(e.getResponse().bufferFactory().wrap("{\"definition\":\"private namespace resourceTenant\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));}));}).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());assertEquals("{\"code\":\"TENANT_RESOURCE_BINDING_UNAVAILABLE\"}",exchange.getResponse().getBodyAsString().block());
    }
    @Test void ownerCutoffCannotBeExtendedByWallClockRollback(){
        var time=new TenantResourceBindingCurrentBudgetTest.Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=TenantResourceBindingCurrentBudgetTest.exchange();
        filter.filter(exchange,e->{time.advance(Duration.ofSeconds(1));TenantResourceBindingCurrentBudgetFilter.retainCurrent(e,current(time));time.nanos.addAndGet(Duration.ofSeconds(2).toNanos());time.instant=time.instant.plusMillis(500);return e.getResponse().setComplete();}).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());assertEquals("{\"code\":\"TENANT_RESOURCE_BINDING_UNAVAILABLE\"}",exchange.getResponse().getBodyAsString().block());
    }
    static R2dbcOperationBudget budget(TenantResourceBindingCurrentBudgetTest.Time time){return R2dbcOperationBudget.observed(time.instant(),time.instant().plusSeconds(5),time.nanos.get(),Duration.ofSeconds(5),time,time.nanos::get);}
    static Current current(java.time.Clock clock){var definition=new Definition(UUID.fromString("11111111-1111-1111-1111-111111111111"),new Namespace("https://issuer.example"," tenant, Ω "),new ResourceTenant(UUID.fromString("22222222-2222-2222-2222-222222222222")),1,new Eligibility(1,UUID.fromString("33333333-3333-3333-3333-333333333333"),1),clock.millis()+60000);return new Current(TenantResourceBindingCodec.reference(definition),definition,1,clock.millis(),clock.millis()+2000);}
    static TenantResourceBindingCurrentIngress ingress(Function<R2dbcOperationBudget,Mono<Current>> read){return new TenantResourceBindingCurrentIngressImpl(new TenantResourceBindingService(){public Mono<Current> current(R2dbcOperationBudget budget){return read.apply(budget);}public Mono<Completion> establish(Command command,R2dbcOperationBudget budget){throw new AssertionError("No mutation is allowed");}public Mono<Completion> revoke(Command command,R2dbcOperationBudget budget){throw new AssertionError("No mutation is allowed");}});}
}
