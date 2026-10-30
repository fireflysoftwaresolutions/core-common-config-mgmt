package io.fireflysoftware.common.config.models.repositories.tenantbinding;
import io.fireflysoftware.common.config.models.entities.tenantbinding.TenantResourceBindingRows.*;
import io.r2dbc.spi.ConnectionFactory;
import java.util.UUID;
import reactor.core.publisher.Mono;
public interface TenantResourceBindingStore {
 ConnectionFactory connectionFactory();
 Mono<Snapshot> current(String namespaceDigest);
 Mono<Head> head(String namespaceDigest);
 Mono<Definition> definition(UUID bindingId,long version);
 Mono<Void> insertDefinition(Definition definition);
 Mono<Void> insertHead(Head head);
 Mono<Void> advance(Head expected,Head next);
}
