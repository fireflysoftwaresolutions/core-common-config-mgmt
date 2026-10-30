package io.fireflysoftware.common.config.web.tenantbinding;

import io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingCurrentBudgetFilter;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import static org.junit.jupiter.api.Assertions.*;

class TenantResourceBindingCurrentBudgetTest {
    @Test void createsBudgetBeforeAuthenticationSubscription(){
        var time=new Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=exchange();var called=new AtomicInteger();
        var work=filter.filter(exchange,e->{called.incrementAndGet();assertNotNull(TenantResourceBindingCurrentBudgetFilter.requireBudget(e));return Mono.empty();});
        assertNotNull(TenantResourceBindingCurrentBudgetFilter.requireBudget(exchange));assertEquals(0,called.get());work.block();assertEquals(1,called.get());
        assertEquals(Ordered.HIGHEST_PRECEDENCE,filter.getOrder());
    }
    @Test void delayedSubscriptionDoesNotRenewIngress(){
        var time=new Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=exchange();var calls=new AtomicInteger();
        var work=filter.filter(exchange,e->{calls.incrementAndGet();return Mono.empty();});time.advance(Duration.ofSeconds(5));work.block();
        assertEquals(0,calls.get());assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());
    }
    @Test void repeatedFilterInvocationRetainsIdenticalOriginal(){
        var time=new Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=exchange();
        filter.filter(exchange,e->Mono.empty()).block();var original=TenantResourceBindingCurrentBudgetFilter.requireBudget(exchange);time.advance(Duration.ofSeconds(4));
        filter.filter(exchange,e->{assertSame(original,TenantResourceBindingCurrentBudgetFilter.requireBudget(e));assertEquals(Duration.ofSeconds(1),original.remaining());return Mono.empty();}).block();
        assertSame(original,TenantResourceBindingCurrentBudgetFilter.requireBudget(exchange));
    }
    @Test void expiredResubscriptionCannotInvokeDownstream(){
        var time=new Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=exchange();var calls=new AtomicInteger();
        var work=filter.filter(exchange,e->{calls.incrementAndGet();return Mono.empty();});work.block();time.advance(Duration.ofSeconds(5));work.block();assertEquals(1,calls.get());
    }
    @Test void stalledAuthenticationIsCancelledAtFiveSeconds(){
        var filter=new TenantResourceBindingCurrentBudgetFilter(Clock.systemUTC(),System::nanoTime);var exchange=exchange();var cancelled=new AtomicInteger();
        StepVerifier.withVirtualTime(()->filter.filter(exchange,e->Mono.<Void>never().doOnCancel(cancelled::incrementAndGet))).thenAwait(Duration.ofSeconds(5)).verifyComplete();
        assertEquals(1,cancelled.get());assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());assertEquals("no-store",exchange.getResponse().getHeaders().getCacheControl());
    }
    @Test void expiryBeforeCommitCannotCommitSuccess() throws Exception{
        var time=new Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=exchange();
        filter.filter(exchange,e->{e.getResponse().setStatusCode(HttpStatus.OK);time.advance(Duration.ofSeconds(5));return e.getResponse().setComplete();}).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());
        assertEquals("no-store",exchange.getResponse().getHeaders().getCacheControl());assertEquals(org.springframework.http.MediaType.APPLICATION_JSON,exchange.getResponse().getHeaders().getContentType());
        var failure=new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getResponse().getBodyAsString().block());assertEquals(1,failure.size());assertEquals("TENANT_RESOURCE_BINDING_UNAVAILABLE",failure.path("code").asText());
    }
    @Test void wallClockRollbackRefusesWithoutWork(){
        var time=new Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=exchange();var calls=new AtomicInteger();
        var work=filter.filter(exchange,e->{calls.incrementAndGet();return Mono.empty();});time.instant=time.instant.minusMillis(1);work.block();assertEquals(0,calls.get());
    }
    @Test void monotonicExhaustionRefusesEvenIfWallHasNotAdvanced(){
        var time=new Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=exchange();var calls=new AtomicInteger();
        var work=filter.filter(exchange,e->{calls.incrementAndGet();return Mono.empty();});time.nanos.addAndGet(Duration.ofSeconds(5).toNanos());work.block();assertEquals(0,calls.get());
    }
    @Test void otherRoutesAreUnchanged(){
        var time=new Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/tenants"));
        filter.filter(exchange,e->e.getResponse().setComplete()).block();assertThrows(IllegalStateException.class,()->TenantResourceBindingCurrentBudgetFilter.requireBudget(exchange));assertFalse(exchange.getResponse().getHeaders().containsHeader("Cache-Control"));
    }
    @Test void nonGetIsNotAdmitted(){
        var time=new Time();var filter=new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);var exchange=MockServerWebExchange.from(MockServerHttpRequest.post(TenantResourceBindingCurrentBudgetFilter.PATH));
        filter.filter(exchange,e->Mono.empty()).block();assertThrows(IllegalStateException.class,()->TenantResourceBindingCurrentBudgetFilter.requireBudget(exchange));
    }
    @Test void earlierOwnerWindowCancelsStalledDownstream(){
        var clock=Clock.systemUTC();var filter=new TenantResourceBindingCurrentBudgetFilter(clock,System::nanoTime);var exchange=exchange();var cancelled=new AtomicInteger();
        StepVerifier.withVirtualTime(()->filter.filter(exchange,e->{TenantResourceBindingCurrentBudgetFilter.retainCurrent(e,TenantResourceBindingCurrentIngressTest.current(clock));return Mono.<Void>never().doOnCancel(cancelled::incrementAndGet);})).thenAwait(Duration.ofSeconds(2)).expectComplete().verify(Duration.ofSeconds(1));
        assertEquals(1,cancelled.get());assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());
    }
    static MockServerWebExchange exchange(){return MockServerWebExchange.from(MockServerHttpRequest.get(TenantResourceBindingCurrentBudgetFilter.PATH));}
    static final class Time extends Clock {
        Instant instant=Instant.parse("2026-10-29T12:00:00Z");final AtomicLong nanos=new AtomicLong(10);
        void advance(Duration duration){instant=instant.plus(duration);nanos.addAndGet(duration.toNanos());}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return instant;}
    }
}
