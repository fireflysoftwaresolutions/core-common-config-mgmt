package io.fireflysoftware.common.config.web.tenantbinding.impl;

import io.fireflysoftware.common.config.core.authority.TenantResourceBindingGovernedCodec;
import io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingGovernedContracts.*;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/** One exchange-owned deadline begins before authentication; expiration never proves a dispatched action absent. */
public final class TenantResourceBindingGovernanceBudgetFilterImpl implements WebFilter,Ordered {
    public static final String BASE="/api/v1/tenant-resource-bindings/governance";
    private static final Set<String> PATHS=Set.of(BASE+"/intents",BASE+"/actions",BASE+"/actions/observations");
    private static final String KEY=TenantResourceBindingGovernanceBudgetFilterImpl.class.getName()+".original";
    private static final Duration LIMIT=Duration.ofSeconds(5);
    private final Clock clock;
    private final LongSupplier nanos;
    public TenantResourceBindingGovernanceBudgetFilterImpl(Clock clock,LongSupplier nanos){this.clock=Objects.requireNonNull(clock);this.nanos=Objects.requireNonNull(nanos);}
    @Override public int getOrder(){return Ordered.HIGHEST_PRECEDENCE;}
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){
        if(exchange.getRequest().getMethod()!=HttpMethod.POST||!PATHS.contains(exchange.getRequest().getPath().pathWithinApplication().value()))return chain.filter(exchange);
        final Scope scope;
        try{scope=(Scope)exchange.getAttributes().computeIfAbsent(KEY,key->original());}catch(RuntimeException error){return unavailable(exchange,null);}
        exchange.getResponse().getHeaders().setCacheControl("private, no-store");exchange.getResponse().getHeaders().setVary(java.util.List.of(HttpHeaders.AUTHORIZATION));
        exchange.getResponse().beforeCommit(()->Mono.fromRunnable(()->scope.requireResponse(exchange)));
        return Mono.defer(()->{scope.budget.remaining();return Mono.firstWithSignal(Mono.defer(()->chain.filter(exchange)).doOnSuccess(ignored->scope.requireResponse(exchange)),Mono.defer(()->Mono.delay(scope.budget.remaining()).then(Mono.<Void>error(new IllegalStateException("Original binding HTTP deadline exhausted")))));}).onErrorResume(error->unavailable(exchange,scope));
    }
    public static R2dbcOperationBudget requireBudget(ServerWebExchange exchange){return scope(exchange).budget;}
    public static void retainAction(ServerWebExchange exchange,ActionRequest request){var scope=scope(exchange);scope.budget.remaining();TenantResourceBindingGovernedCodec.original(request);if(!exchange.getRequest().getPath().pathWithinApplication().value().equals(BASE+"/actions"))throw new IllegalStateException("Exact action route required");var prepared=new PreparedIntent(TenantResourceBindingGovernedCodec.action(request.intent()),request.originalIntentDigest());synchronized(scope){if(scope.action!=null&&!scope.action.equals(prepared))throw new IllegalStateException("Original action cannot be replaced");scope.action=prepared;}}
    public static void retainKnownCommit(ServerWebExchange exchange,ActionResult result){var scope=scope(exchange);synchronized(scope){if(scope.action==null||!scope.action.actionId().equals(result.actionId())||!scope.action.intentDigest().equals(result.intentDigest())||result.status()==ActionStatus.OUTCOME_UNKNOWN)throw new IllegalStateException("Exact observed owner commit required");scope.ownerCommitted=true;}}
    public static boolean observedCommit(ServerWebExchange exchange){return scope(exchange).ownerCommitted;}
    public static ActionResult unavailableAction(ServerWebExchange exchange){var scope=scope(exchange);var original=scope.action;if(original==null)throw new IllegalStateException("Original action required");return new ActionResult(scope.ownerCommitted?ActionStatus.COMMITTED_RESPONSE_UNAVAILABLE:ActionStatus.OUTCOME_UNKNOWN,original.actionId(),original.intentDigest(),null,null);}
    public static void retainUnavailable(ServerWebExchange exchange){var scope=scope(exchange);synchronized(scope){if(scope.action==null)throw new IllegalStateException("Original action required");scope.safeStatus=200;}}
    public static void retainRefusal(ServerWebExchange exchange,HttpStatusCode status){if(!Set.of(400,401,403,503).contains(status.value()))throw new IllegalArgumentException("Exact code-only refusal required");var value=exchange.getAttribute(KEY);if(value instanceof Scope scope)synchronized(scope){scope.safeStatus=status.value();}}
    private Scope original(){long before=nanos.getAsLong();var observed=clock.instant();return new Scope(R2dbcOperationBudget.observed(observed,observed.plus(LIMIT),before,LIMIT,clock,nanos));}
    private static Scope scope(ServerWebExchange exchange){var value=exchange.getAttribute(KEY);if(!(value instanceof Scope scope))throw new IllegalStateException("Original binding HTTP budget required");return scope;}
    private static Mono<Void> unavailable(ServerWebExchange exchange,Scope scope){
        var response=exchange.getResponse();if(response.isCommitted())return Mono.error(new IllegalStateException("Binding governance response unavailable"));
        final byte[] bytes;final HttpStatus status;
        if(scope!=null&&scope.action!=null){var action=scope.action;var outcome=scope.ownerCommitted?ActionStatus.COMMITTED_RESPONSE_UNAVAILABLE:ActionStatus.OUTCOME_UNKNOWN;bytes=("{\"status\":\""+outcome.name()+"\",\"actionId\":\""+action.actionId()+"\",\"intentDigest\":\""+action.intentDigest()+"\",\"governanceReceipt\":null,\"completion\":null}").getBytes(StandardCharsets.UTF_8);status=HttpStatus.OK;synchronized(scope){scope.safeStatus=200;}}
        else{bytes="{\"code\":\"TENANT_RESOURCE_BINDING_GOVERNANCE_UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8);status=HttpStatus.SERVICE_UNAVAILABLE;if(scope!=null)synchronized(scope){scope.safeStatus=503;}}
        response.setStatusCode(status);response.getHeaders().setCacheControl("private, no-store");response.getHeaders().setVary(java.util.List.of(HttpHeaders.AUTHORIZATION));response.getHeaders().setContentType(MediaType.APPLICATION_JSON);response.getHeaders().setContentLength(bytes.length);return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
    private static final class Scope {
        private final R2dbcOperationBudget budget;
        private volatile PreparedIntent action;
        private volatile boolean ownerCommitted;
        private int safeStatus;
        private Scope(R2dbcOperationBudget budget){this.budget=budget;}
        private synchronized void requireResponse(ServerWebExchange exchange){if(safeStatus!=0){if(exchange.getResponse().getStatusCode()==null||exchange.getResponse().getStatusCode().value()!=safeStatus)throw new IllegalStateException("Exact non-authority response required");}else budget.remaining();}
    }
}
