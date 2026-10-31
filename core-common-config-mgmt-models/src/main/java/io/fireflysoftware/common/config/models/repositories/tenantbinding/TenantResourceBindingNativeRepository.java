package io.fireflysoftware.common.config.models.repositories.tenantbinding;
import io.fireflysoftware.common.config.models.entities.tenantbinding.TenantResourceBindingRows.Head;
import org.springframework.data.repository.Repository;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;
import java.util.UUID;
public interface TenantResourceBindingNativeRepository extends Repository<Head,String> {
 @Query("SELECT public.cfg_trb_open_lookup(:issuer,:subject,:platformTenant,:until)::text AS result")
 Mono<String> openLookup(@Param("issuer")String issuer,@Param("subject")String subject,@Param("platformTenant")String platformTenant,@Param("until")Long until);
 @Query("SELECT public.cfg_trb_open_control(:original,:kind,:issuer,:subject,:actorTenant,:employee,:client,:until)::text AS result")
 Mono<String> openControl(@Param("original")byte[] original,@Param("kind")String kind,@Param("issuer")String issuer,@Param("subject")String subject,@Param("actorTenant")UUID actorTenant,@Param("employee")UUID employee,@Param("client")String client,@Param("until")Long until);
 @Query("SELECT public.cfg_trb_finish_control(:original)::text AS result")
 Mono<String> finishControl(@Param("original")byte[] original);
 @Query("SELECT public.cfg_trb_prepare(:original,:kind,:issuer,:subject,:actorTenant,:employee,:client,:until)::text AS result")
 Mono<String> prepare(@Param("original")byte[] original,@Param("kind")String kind,@Param("issuer")String issuer,@Param("subject")String subject,@Param("actorTenant")UUID actorTenant,@Param("employee")UUID employee,@Param("client")String client,@Param("until")Long until);
 @Query("SELECT public.cfg_trb_decide(:original,:kind,:issuer,:subject,:actorTenant,:employee,:client,:until)::text AS result")
 Mono<String> decide(@Param("original")byte[] original,@Param("kind")String kind,@Param("issuer")String issuer,@Param("subject")String subject,@Param("actorTenant")UUID actorTenant,@Param("employee")UUID employee,@Param("client")String client,@Param("until")Long until);

}
