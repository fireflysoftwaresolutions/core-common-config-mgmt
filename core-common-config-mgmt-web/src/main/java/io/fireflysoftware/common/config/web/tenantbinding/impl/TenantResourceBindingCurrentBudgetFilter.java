package io.fireflysoftware.common.config.web.tenantbinding.impl;

import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Current;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Starts the technical deadline before authentication; it grants no caller or native authority. */
public final class TenantResourceBindingCurrentBudgetFilter implements WebFilter,Ordered {
    public static final String PATH="/api/v1/tenant-resource-bindings/current";
    private static final String SCOPE=TenantResourceBindingCurrentBudgetFilter.class.getName()+".original";
    private static final Duration LIMIT=Duration.ofSeconds(5);
    private static final byte[] UNAVAILABLE="{\"code\":\"TENANT_RESOURCE_BINDING_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);
    private final Clock clock;
    private final LongSupplier nanos;
    public TenantResourceBindingCurrentBudgetFilter(Clock clock,LongSupplier nanos){this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);}
    @Override public int getOrder(){return Ordered.HIGHEST_PRECEDENCE;}
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){
        if(exchange.getRequest().getMethod()!=HttpMethod.GET||!PATH.equals(exchange.getRequest().getPath().pathWithinApplication().value()))return chain.filter(exchange);
        // Exchange ownership survives delayed subscriptions and repeated filter invocations.
        final Scope scope;
        try{scope=(Scope)exchange.getAttributes().computeIfAbsent(SCOPE,key->original());}
        catch(RuntimeException error){return unavailable(exchange,null);}
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        exchange.getResponse().beforeCommit(()->Mono.defer(()->{scope.requireCommit(exchange.getResponse());return Mono.empty();}));
        return Mono.defer(()->{
            scope.requireCurrent();
            return Mono.firstWithSignal(Mono.defer(()->chain.filter(exchange)).doOnSuccess(ignored->scope.requireCompletion(exchange.getResponse())),scope.expiration());
        }).onErrorResume(error->unavailable(exchange,scope));
    }
    public static R2dbcOperationBudget requireBudget(ServerWebExchange exchange){return scope(exchange).budget;}
    public static void retainCurrent(ServerWebExchange exchange,Current current){scope(exchange).retain(current);}
    public static void retainOwnedRefusal(ServerWebExchange exchange,HttpStatusCode status){
        int code=Objects.requireNonNull(status).value();
        if(code!=400&&code!=401&&code!=403&&code!=503)throw new IllegalArgumentException("Exact CURRENT refusal status required");
        var value=exchange.getAttribute(SCOPE);
        if(value==null)return;
        if(!(value instanceof Scope scope))throw new IllegalStateException("Original CURRENT ingress budget required");
        scope.retainRefusal(code);
    }
    private static Scope scope(ServerWebExchange exchange){
        var value=exchange.getAttribute(SCOPE);
        if(!(value instanceof Scope scope))throw new IllegalStateException("Original CURRENT ingress budget required");
        return scope;
    }
    private Scope original(){
        long before=nanos.getAsLong();var observed=clock.instant();
        return new Scope(R2dbcOperationBudget.observed(observed,observed.plus(LIMIT),before,LIMIT,clock,nanos),clock,nanos);
    }
    private static Mono<Void> unavailable(ServerWebExchange exchange,Scope scope){
        var response=exchange.getResponse();
        if(response.isCommitted())return Mono.error(new IllegalStateException("Current tenant resource binding unavailable"));
        if(scope!=null)scope.failureResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);response.getHeaders().setCacheControl("no-store");response.getHeaders().setContentType(MediaType.APPLICATION_JSON);response.getHeaders().setContentLength(UNAVAILABLE.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(UNAVAILABLE)));
    }
    private static final class Scope {
        private final R2dbcOperationBudget budget;
        private final Clock clock;
        private final LongSupplier nanos;
        private long ownerUntil=Long.MAX_VALUE;
        private long ownerBefore,ownerDuration=Long.MAX_VALUE;
        private boolean failureResponse;
        private int refusalStatus;
        private final Sinks.Many<Boolean> narrowed=Sinks.many().replay().latest();
        private Scope(R2dbcOperationBudget budget,Clock clock,LongSupplier nanos){this.budget=budget;this.clock=clock;this.nanos=nanos;}
        private synchronized void retain(Current value){
            Objects.requireNonNull(value);requireCurrent();long before=nanos.getAsLong();var now=clock.instant();
            if(value.observedAtEpochMillis()>now.toEpochMilli()||value.validUntilEpochMillis()<=now.toEpochMilli()||value.validUntilEpochMillis()-value.observedAtEpochMillis()>LIMIT.toMillis())throw new IllegalStateException("Finite current owner observation required");
            long duration=Duration.between(now,Instant.ofEpochMilli(value.validUntilEpochMillis())).toNanos();
            if(duration<ownerRemaining()){ownerBefore=before;ownerDuration=duration;}
            ownerUntil=Math.min(ownerUntil,value.validUntilEpochMillis());requireCurrent();
            if(narrowed.tryEmitNext(Boolean.TRUE).isFailure())throw new IllegalStateException("Current response deadline unavailable");
        }
        private long ownerRemaining(){
            if(ownerDuration==Long.MAX_VALUE)return Long.MAX_VALUE;
            long elapsed=nanos.getAsLong()-ownerBefore;
            if(elapsed<0||elapsed>=ownerDuration)throw new IllegalStateException("Current owner observation expired");
            return ownerDuration-elapsed;
        }
        private synchronized void requireCurrent(){if(failureResponse)throw new IllegalStateException("CURRENT exchange already refused");budget.remaining();ownerRemaining();if(clock.millis()>=ownerUntil)throw new IllegalStateException("Current owner observation expired");}
        private synchronized Duration remaining(){
            requireCurrent();var remaining=budget.remaining();
            if(ownerUntil!=Long.MAX_VALUE){
                var wall=Duration.between(clock.instant(),Instant.ofEpochMilli(ownerUntil));var monotonic=Duration.ofNanos(ownerRemaining());
                if(wall.compareTo(remaining)<0)remaining=wall;if(monotonic.compareTo(remaining)<0)remaining=monotonic;
            }
            if(remaining.isZero()||remaining.isNegative())throw new IllegalStateException("Current response deadline exhausted");
            return remaining;
        }
        private Mono<Void> expiration(){
            return narrowed.asFlux().startWith(Boolean.TRUE).switchMap(ignored->Mono.defer(()->Mono.delay(remaining()).then(Mono.<Void>error(new IllegalStateException("Current response deadline exhausted"))))).next();
        }
        private synchronized void retainRefusal(int status){refusalStatus=status;}
        private boolean matchesRefusal(ServerHttpResponse response){return refusalStatus!=0&&response.getStatusCode()!=null&&response.getStatusCode().value()==refusalStatus;}
        private synchronized void requireCommit(ServerHttpResponse response){
            if(refusalStatus!=0){if(!matchesRefusal(response))throw new IllegalStateException("CURRENT refusal response status changed");}
            else if(!failureResponse)requireCurrent();
        }
        private synchronized void requireCompletion(ServerHttpResponse response){
            // Only the owner handler's completed code-only refusal has no remaining authority result.
            if(refusalStatus!=0){if(!response.isCommitted()||!matchesRefusal(response))throw new IllegalStateException("Exact completed CURRENT refusal required");}
            else requireCurrent();
        }
        private synchronized void failureResponse(){failureResponse=true;refusalStatus=503;}
    }
}
