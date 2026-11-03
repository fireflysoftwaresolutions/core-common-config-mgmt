/*
 * Copyright 2025 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package io.fireflysoftware.common.config.core.repositories.documentissuer;

import java.lang.reflect.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.*;
import io.r2dbc.spi.*;
import org.reactivestreams.Publisher;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentIssuerWritesTest {
    static UUID id(long n){return new UUID(0,n);}
    static Object effect() {
        try {return Class.forName("io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows$Audit")
                .getDeclaredConstructors()[0].newInstance(id(1),id(2),id(3),"PREPARE","a".repeat(64),"https://identity.example","maker",OffsetDateTime.parse("2026-10-27T16:00:00Z"));}
        catch(ReflectiveOperationException failure){throw new AssertionError("Mapped document issuer audit row missing",failure);}
    }
    static Object writes(Driver driver){
        try{return Class.forName("io.fireflysoftware.common.config.models.repositories.documentissuer.impl.DocumentIssuerWritesImpl")
                .getConstructor(R2dbcEntityTemplate.class).newInstance(driver.template());}
        catch(ReflectiveOperationException failure){throw new AssertionError("Actual mapped count writer missing",failure);}
    }
    static Mono<?> invoke(Object writer,String name,Object arg){
        try{var method=Arrays.stream(writer.getClass().getMethods()).filter(m->m.getName().equals(name)&&m.getParameterCount()==1).findFirst().orElseThrow();return (Mono<?>)method.invoke(writer,arg);}
        catch(InvocationTargetException failure){return Mono.error(failure.getCause());}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    @Test void oneActualMappedInsertKeepsScopeIdentityAndNoBusinessSqlTemplate(){
        var d=new Driver();invoke(writes(d),"audit",effect()).block();assertEquals(1,d.sql.size());
        assertTrue(d.sql.getFirst().startsWith("INSERT INTO \"public\".\"cfg_document_issuer_audit\""));
        for(UUID value:List.of(id(1),id(2),id(3)))assertTrue(d.bound.containsValue(value));
    }
    @Test void zeroActualInsertRollsBackWithoutReportingSuccess(){assertRefusedCount(0);}
    @Test void multipleActualInsertRollsBackWithoutReportingSuccess(){assertRefusedCount(2);}
    void assertRefusedCount(long rows){var d=new Driver();d.rows=rows;var writer=writes(d);var row=effect();var tx=TransactionalOperator.create(new R2dbcTransactionManager(d));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx.transactional(Mono.defer(()->invoke(writer,"audit",row))).block());
        assertEquals(0,d.commits);assertEquals(1,d.rollbacks);assertEquals(1,d.sql.size());
    }
    @Test void missingCountIsNotInventedFromReturnedEntity(){var d=new Driver();d.noCount=true;assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->invoke(writes(d),"audit",effect()).block());}
    @Test void duplicateDoesNotQueryAbortedTransaction(){var d=new Driver();d.duplicate=true;var writer=writes(d);var row=effect();var tx=TransactionalOperator.create(new R2dbcTransactionManager(d));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx.transactional(Mono.defer(()->invoke(writer,"audit",row))).block());
        assertEquals(0,d.commits);assertEquals(1,d.rollbacks);assertEquals(1,d.sql.size());assertTrue(d.sql.stream().noneMatch(s->s.startsWith("SELECT")));
    }
    static Object control(long generation,String state,UUID publication) {
        try {return Class.forName("io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows$Control")
                .getDeclaredConstructors()[0].newInstance(id(2),id(1),publication,1L,generation,state,9L);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    static Mono<?> advance(Object writer,Object expected,Object next) {
        try{return (Mono<?>)writer.getClass().getMethod("advance",expected.getClass(),next.getClass()).invoke(writer,expected,next);}
        catch(InvocationTargetException failure){return Mono.error(failure.getCause());}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    @Test void zeroAndMultipleCasValuesCannotReportACompletedWrite() {for(long count:new long[]{0,2}){var d=new Driver();d.rows=count;var writer=writes(d);var prior=control(1,"PREPARED",id(8));var next=control(2,"PUBLISHED",id(8));var tx=TransactionalOperator.create(new R2dbcTransactionManager(d));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->tx.transactional(Mono.defer(()->advance(writer,prior,next))).block());assertEquals(0,d.commits);assertEquals(1,d.rollbacks);
    }}
    @Test void missingCasCountCannotBeTreatedAsACommitWitness() {var d=new Driver();d.noCount=true;assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->advance(writes(d),control(1,"PREPARED",id(8)),control(2,"PUBLISHED",id(8))).block());}
    @Test void controlCasIsFullyScopedAndChangesOnlyFiveColumns() {var d=new Driver();advance(writes(d),control(1,"PREPARED",id(8)),control(2,"PUBLISHED",id(8))).block();var sql=d.sql.getFirst();
        var parts=sql.split(" WHERE ",2);assertEquals(2,parts.length);for(String field:List.of("tenant_id","issuer_id","publication_id","revision","generation","state","source_generation"))assertTrue(parts[1].contains(field));
        assertFalse(parts[0].contains("tenant_id"));assertFalse(parts[0].contains("issuer_id"));assertFalse(parts[0].contains("expires"));assertEquals(5,parts[0].split(" SET ",2)[1].split(",").length);
    }
    @Test void genuineTemplateCharacterizationMissingUpdateCountIsZero() throws Exception {
        var d=new Driver();d.noCount=true;
        var entity=Class.forName("io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows$Control");
        var count=d.template().update(entity).matching(org.springframework.data.relational.core.query.Query.empty())
            .apply(org.springframework.data.relational.core.query.Update.update("generation",2L)).block();
        assertEquals(0L,count);
    }
    @Test void allSixClosedRowsUseActualGeneratedInsertCounts() {
        var d=new Driver();var writer=new io.fireflysoftware.common.config.models.repositories.documentissuer.impl.DocumentIssuerWritesImpl(d.template());
        var stamp=OffsetDateTime.parse("2026-10-27T16:00:00Z");
        writer.revision(new io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows.Revision(id(1),id(2),id(3),1,"{}","a".repeat(64),9,"https://identity.example","maker",stamp)).block();
        writer.control(new io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows.Control(id(3),id(2),id(1),1,1,"PREPARED",9)).block();
        writer.decision(new io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows.Decision(id(4),id(2),id(3),id(1),1,"PUBLISH","a".repeat(64),"https://identity.example","checker",stamp)).block();
        writer.action(new io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows.Action(id(4),id(2),id(3),"PUBLISH","a".repeat(64),"{}","b".repeat(64),stamp)).block();
        writer.audit(new io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows.Audit(id(4),id(2),id(3),"PUBLISH","a".repeat(64),"https://identity.example","checker",stamp)).block();
        writer.outbox(new io.fireflysoftware.common.config.models.entities.documentissuer.DocumentIssuerRows.Outbox(id(4),id(2),id(3),"PUBLISHED","b".repeat(64),stamp)).block();
        assertEquals(6,d.sql.size());assertTrue(d.sql.stream().allMatch(sql->sql.startsWith("INSERT INTO \"public\".\"cfg_document_issuer_")));
    }
    @Test void controlIdentityAndRevisionCannotBeSilentlyReplacedByPublish() {
        var d=new Driver();var writer=writes(d);
        assertThrows(IllegalArgumentException.class,()->advance(writer,control(1,"PREPARED",id(8)),control(2,"PUBLISHED",id(9))).block());
        assertThrows(IllegalArgumentException.class,()->advance(writer,control(1,"PREPARED",id(8)),control(3,"PUBLISHED",id(8))).block());
        assertTrue(d.sql.isEmpty());
    }
    @Test void mappedMetadataReadsAlwaysRetainExactTenantAndResourcePredicates() {
        var d=new Driver();var reads=new io.fireflysoftware.common.config.models.repositories.documentissuer.impl.DocumentIssuerReadsImpl(d.template());
        reads.revision(id(1),id(2)).block();reads.control(id(1),id(2)).block();reads.publishedDecision(id(1),id(2)).block();
        reads.action(id(1),id(2)).block();reads.audit(id(1),id(2)).block();reads.source(id(1),id(2)).block();
        assertEquals(6,d.sql.size());for(var sql:d.sql){assertTrue(sql.startsWith("SELECT "));assertTrue(sql.contains(" WHERE "));assertTrue(sql.contains("tenant_id"));}
        assertTrue(d.sql.get(0).contains("publication_id"));assertTrue(d.sql.get(1).contains("issuer_id"));
        assertTrue(d.sql.get(2).contains("operation"));assertTrue(d.sql.get(3).contains("action_id"));assertTrue(d.sql.get(4).contains("action_id"));assertTrue(d.sql.get(5).contains("branding_id"));
    }
    static final class Driver implements ConnectionFactory {
        final List<String> sql=new ArrayList<>();final Map<Object,Object> bound=new LinkedHashMap<>();long rows=1;boolean noCount,duplicate;int commits,rollbacks;
        final Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "createStatement"->statement((String)a[0]);case "getMetadata"->new ConnectionMetadata(){public String getDatabaseProductName(){return "PostgreSQL";}public String getDatabaseVersion(){return "17";}};
            case "commitTransaction"->{commits++;yield Mono.empty();}case "rollbackTransaction"->{rollbacks++;yield Mono.empty();}
            case "isAutoCommit"->true;case "getTransactionIsolationLevel"->IsolationLevel.READ_COMMITTED;case "validate"->Mono.just(true);
            case "close","beginTransaction","setAutoCommit","setTransactionIsolationLevel"->Mono.empty();case "toString"->"NetworklessDocumentIssuerDriver";
            default->throw new UnsupportedOperationException(m.getName());});
        Statement statement(String sqlText){sql.add(sqlText);return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(p,m,a)->{
            if(m.getName().equals("bind")){bound.put(a[0],a[1]);return p;}if(m.getName().equals("execute")){
                if(duplicate)return Mono.error(new R2dbcDataIntegrityViolationException("Synthetic unique conflict","23505",0));
                return Mono.just(new Result(){public Publisher<Long> getRowsUpdated(){return noCount?Mono.empty():Mono.just(rows);}
                    public <T>Publisher<T> map(BiFunction<Row,RowMetadata,? extends T> mapper){return Flux.empty();}
                    public <T>Publisher<T> map(Function<? super io.r2dbc.spi.Readable,? extends T> mapper){return Flux.empty();}
                    public Result filter(Predicate<Segment> predicate){return this;}
                    public <T>Publisher<T> flatMap(Function<Segment,? extends Publisher<? extends T>> mapper){return Flux.empty();}});
            }if(m.getReturnType()==Statement.class)return p;throw new UnsupportedOperationException(m.getName());});}
        public Publisher<? extends Connection> create(){return Mono.just(connection);}public ConnectionFactoryMetadata getMetadata(){return ()->"PostgreSQL";}
        R2dbcEntityTemplate template(){return new R2dbcEntityTemplate(DatabaseClient.create(this),PostgresDialect.INSTANCE);}
    }
}
