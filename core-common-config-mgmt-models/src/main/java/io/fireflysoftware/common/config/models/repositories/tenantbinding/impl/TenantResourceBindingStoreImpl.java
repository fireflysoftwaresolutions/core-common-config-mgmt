package io.fireflysoftware.common.config.models.repositories.tenantbinding.impl;
import io.fireflysoftware.common.config.models.entities.tenantbinding.TenantResourceBindingRows.*;
import io.fireflysoftware.common.config.models.repositories.tenantbinding.TenantResourceBindingStore;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.core.StatementMapper;
import org.springframework.data.relational.core.query.*;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.*;
import reactor.core.publisher.Mono;
public final class TenantResourceBindingStoreImpl implements TenantResourceBindingStore {
 private final R2dbcEntityTemplate template;
 public TenantResourceBindingStoreImpl(R2dbcEntityTemplate template){this.template=Objects.requireNonNull(template);}
 @Override public ConnectionFactory connectionFactory(){return template.getDatabaseClient().getConnectionFactory();}
 @Override public Mono<Snapshot> current(String namespace){return Mono.defer(()->{var scope=namespace(namespace);return template.select(Snapshot.class).matching(Query.query(scope)).one();});}
 @Override public Mono<Head> head(String namespace){return Mono.defer(()->{var scope=namespace(namespace);return template.select(Head.class).matching(Query.query(scope)).one();});}
 @Override public Mono<Definition> definition(UUID binding,long version){return Mono.defer(()->{
  if(binding==null||binding.equals(new UUID(0,0))||version<1)throw new IllegalArgumentException("Exact binding version required");
  return template.select(Definition.class).matching(Query.query(Criteria.where("bindingId").is(binding).and("version").is(version))).one();
 });}
 @Override public Mono<Void> insertDefinition(Definition row){return insert(row);}
 @Override public Mono<Void> insertHead(Head row){return insert(row);}
 // Spring Data's mapped assigned insert still exposes the deprecated R2DBC Parameter type.
 @SuppressWarnings("deprecation")
 private Mono<Void> insert(Object row){return Mono.defer(()->{
  if(row==null||!Set.of(Definition.class,Head.class).contains(row.getClass()))throw new IllegalArgumentException("Closed assigned binding row required");
  var entity=template.getConverter().getMappingContext().getRequiredPersistentEntity(row.getClass());
  if(entity.hasVersionProperty()||entity.getPropertyAccessor(row).getProperty(entity.getRequiredIdProperty())==null)throw new IllegalArgumentException("Assigned binding identity required");
  var strategy=template.getDataAccessStrategy();var insert=StatementMapper.InsertSpec.create(entity.getQualifiedTableName());
  for(var column:strategy.getOutboundRow(row).entrySet())insert=insert.withColumn(column.getKey(),column.getValue());
  return exact(template.getDatabaseClient().sql(strategy.getStatementMapper().getMappedObject(insert)).fetch().rowsUpdated());
 });}
 @Override public Mono<Void> advance(Head expected,Head next){return Mono.defer(()->{
  if(expected==null||next==null||!Objects.equals(expected.namespaceDigest(),next.namespaceDigest())||!Objects.equals(expected.bindingId(),next.bindingId())
    ||expected.controlVersion()<1||expected.controlVersion()>=Long.MAX_VALUE-1||next.controlVersion()!=expected.controlVersion()+1
    ||!(next.state().equals("REVOKED")&&expected.state().equals("ACTIVE")&&next.definitionVersion()==expected.definitionVersion()&&Objects.equals(next.definitionDigest(),expected.definitionDigest())
      ||next.state().equals("ACTIVE")&&Set.of("ACTIVE","REVOKED").contains(expected.state())&&expected.definitionVersion()<Long.MAX_VALUE-1&&next.definitionVersion()==expected.definitionVersion()+1))throw new IllegalArgumentException("Exact binding transition required");
  var where=namespace(expected.namespaceDigest()).and("bindingId").is(expected.bindingId()).and("definitionVersion").is(expected.definitionVersion())
   .and("definitionDigest").is(expected.definitionDigest()).and("controlVersion").is(expected.controlVersion()).and("state").is(expected.state());
  return exact(template.update(Head.class).matching(Query.query(where)).apply(Update.update("definitionVersion",next.definitionVersion()).set("definitionDigest",next.definitionDigest()).set("controlVersion",next.controlVersion()).set("state",next.state())));
 });}
 private static Mono<Void> exact(Mono<Long> count){return count.switchIfEmpty(Mono.error(new DataIntegrityViolationException("Binding row count required"))).flatMap(n->n==1?Mono.<Void>empty():Mono.error(new DataIntegrityViolationException("Exact binding row count required")));}
 private static Criteria namespace(String digest){if(digest==null||!digest.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Exact namespace digest required");return Criteria.where("namespaceDigest").is(digest);}
}
