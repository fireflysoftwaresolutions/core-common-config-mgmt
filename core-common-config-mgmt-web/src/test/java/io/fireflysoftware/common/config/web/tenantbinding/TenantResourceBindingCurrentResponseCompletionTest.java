package io.fireflysoftware.common.config.web.tenantbinding;

import io.fireflysoftware.common.config.web.controllers.authority.TenantResourceBindingCurrentController;
import io.fireflysoftware.common.config.web.tenantbinding.impl.TenantResourceBindingCurrentBudgetFilter;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.InvocableHandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

class TenantResourceBindingCurrentResponseCompletionTest {
    private static final String CODE="{\"code\":\"TENANT_RESOURCE_BINDING_UNAVAILABLE\"}";

    @Test void onTimeOwnedRefusalCompletesWithExactBody(){
        var time=new Time();var exchange=exchange();
        filter(time).filter(exchange,e->refusal(exchange,HttpStatus.SERVICE_UNAVAILABLE)).block();
        exactFailure(exchange);
    }

    @Test void ownedRefusalCommittedBeforeExpiryCompletesWithoutSecondError(){
        var time=new Time();var exchange=exchange();
        filter(time).filter(exchange,e->refusal(exchange,HttpStatus.SERVICE_UNAVAILABLE).then(Mono.fromRunnable(time::expire))).block();
        exactFailure(exchange);
    }

    @Test void unmarkedCommitted503IsNotAnOwnedRefusal(){
        var time=new Time();var exchange=exchange();
        var work=filter(time).filter(exchange,e->write(exchange,HttpStatus.SERVICE_UNAVAILABLE).then(Mono.fromRunnable(time::expire)));
        assertThrows(IllegalStateException.class,work::block);
    }

    @Test void committedSuccessIsStillRejectedAtExpiry(){
        var time=new Time();var exchange=exchange();
        var work=filter(time).filter(exchange,e->write(exchange,HttpStatus.OK).then(Mono.fromRunnable(time::expire)));
        assertThrows(IllegalStateException.class,work::block);
        assertEquals(HttpStatus.OK,exchange.getResponse().getStatusCode());
    }

    @Test void ownedRefusalMarkerCannotBlessAnAlreadyCommittedSuccess(){
        var time=new Time();var exchange=exchange();
        var work=filter(time).filter(exchange,e->write(exchange,HttpStatus.OK).then(invokeRefusal(exchange)).then(Mono.fromRunnable(time::expire)));
        assertThrows(IllegalStateException.class,work::block);
    }

    @Test void ownedRefusalStatusCannotBeChangedToSuccessBeforeCommit(){
        var time=new Time();var exchange=exchange();
        filter(time).filter(exchange,e->refusal(exchange,HttpStatus.OK)).block();
        exactFailure(exchange);
    }

    @Test void expiredUncommittedWorkStillReturnsExact503WithoutCallingDownstream(){
        var time=new Time();var exchange=exchange();var calls=new AtomicInteger();
        var work=filter(time).filter(exchange,e->{calls.incrementAndGet();return Mono.empty();});
        time.expire();work.block();assertEquals(0,calls.get());exactFailure(exchange);
    }

    @Test void errorAfterCommittedOwnedRefusalIsNotSwallowed(){
        var time=new Time();var exchange=exchange();
        var work=filter(time).filter(exchange,e->refusal(exchange,HttpStatus.SERVICE_UNAVAILABLE).then(Mono.error(new IllegalStateException("Synthetic write failure"))));
        assertThrows(IllegalStateException.class,work::block);
    }

    @Test void failedUncommittedOwnedRefusalCanBecomeTheFiltersExact503(){
        var time=new Time();var exchange=exchange();
        filter(time).filter(exchange,e->invokeRefusal(exchange,HttpStatus.BAD_REQUEST)
                .then(Mono.error(new IllegalStateException("Synthetic encoding failure")))).block();
        exactFailure(exchange);
    }

    @Test void legacyPublicFailureMethodKeepsItsResponseContract(){
        var response=controller().failure(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,response.getStatusCode());
        assertEquals(new TenantResourceBindingCurrentController.Failure("TENANT_RESOURCE_BINDING_UNAVAILABLE"),response.getBody());
        assertEquals("no-store",response.getHeaders().getCacheControl());
    }

    private static Mono<Void> refusal(MockServerWebExchange exchange,HttpStatus actualStatus){
        return invokeRefusal(exchange).flatMap(response->{
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,response.getStatusCode());
            assertEquals(new TenantResourceBindingCurrentController.Failure("TENANT_RESOURCE_BINDING_UNAVAILABLE"),response.getBody());
            return write(exchange,actualStatus);
        });
    }

    private static Mono<ResponseEntity<?>> invokeRefusal(MockServerWebExchange exchange){
        return invokeRefusal(exchange,HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static Mono<ResponseEntity<?>> invokeRefusal(MockServerWebExchange exchange,HttpStatus status){
        var error=new ResponseStatusException(status);
        var method=new ExceptionHandlerMethodResolver(TenantResourceBindingCurrentController.class).resolveMethod(error);
        assertNotNull(method);
        return new InvocableHandlerMethod(controller(),method).invoke(exchange,new BindingContext(),error,exchange)
                .map(result->assertInstanceOf(ResponseEntity.class,result.getReturnValue()));
    }

    private static Mono<Void> write(MockServerWebExchange exchange,HttpStatus status){
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(CODE.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    private static TenantResourceBindingCurrentController controller(){
        return new TenantResourceBindingCurrentController(budget->Mono.error(new AssertionError("No lookup is needed to emit a refusal")));
    }
    private static TenantResourceBindingCurrentBudgetFilter filter(Time time){return new TenantResourceBindingCurrentBudgetFilter(time,time.nanos::get);}
    private static MockServerWebExchange exchange(){return MockServerWebExchange.from(MockServerHttpRequest.get(TenantResourceBindingCurrentBudgetFilter.PATH));}
    private static void exactFailure(MockServerWebExchange exchange){
        assertTrue(exchange.getResponse().isCommitted());assertEquals(HttpStatus.SERVICE_UNAVAILABLE,exchange.getResponse().getStatusCode());
        assertEquals(CODE,exchange.getResponse().getBodyAsString().block());
    }
    private static final class Time extends Clock{
        private Instant now=Instant.parse("2026-10-31T00:00:00Z");private final AtomicLong nanos=new AtomicLong(10);
        void expire(){now=now.plusSeconds(5);nanos.addAndGet(Duration.ofSeconds(5).toNanos());}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
