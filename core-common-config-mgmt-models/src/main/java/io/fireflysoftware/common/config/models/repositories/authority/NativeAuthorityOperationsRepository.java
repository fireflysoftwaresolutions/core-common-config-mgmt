/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.models.repositories.authority;

import io.fireflysoftware.common.config.models.entities.authority.ActionKey;
import io.fireflysoftware.common.config.models.entities.authority.ActionRow;
import org.springframework.data.repository.Repository;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface NativeAuthorityOperationsRepository extends Repository<ActionRow,ActionKey> {
    @Query("SELECT public.cfg_native_environment(:operation) AS permitted")
    Mono<Boolean> environment(@Param("operation") String operation);

    @Query("SELECT public.cfg_native_establish_context(:tenant,:institution,:operation,:resourceType,:resource,:action,:intent,:kind,:issuer,:subject,:actorTenant,:employee,:client,:recovery,:until,:deadline) AS established")
    Mono<Boolean> establishContext(@Param("tenant") UUID tenant,@Param("institution") UUID institution,@Param("operation") String operation,@Param("resourceType") String resourceType,@Param("resource") UUID resource,@Param("action") UUID action,@Param("intent") String intent,@Param("kind") String kind,@Param("issuer") String issuer,@Param("subject") String subject,@Param("actorTenant") UUID actorTenant,@Param("employee") UUID employee,@Param("client") String client,@Param("recovery") Boolean recovery,@Param("until") Long until,@Param("deadline") Long deadline);

    @Query("SELECT public.cfg_native_suspend_tenant(:tenant,:original)::text AS result")
    Mono<String> suspendTenant(@Param("tenant") UUID tenant,@Param("original") byte[] original);

    @Query("SELECT public.cfg_native_prepare_transition(:tenant,:resource,:status,:original)::text AS result")
    Mono<String> prepareTransition(@Param("tenant") UUID tenant,@Param("resource") UUID resource,@Param("status") Boolean status,@Param("original") byte[] original);

    @Query("SELECT public.cfg_native_decide(:tenant,:proposal,:operation,:original)::text AS result")
    Mono<String> decide(@Param("tenant") UUID tenant,@Param("proposal") UUID proposal,@Param("operation") String operation,@Param("original") byte[] original);

    @Query("SELECT public.cfg_native_transition_tenant(:tenant,:original)::text AS result")
    Mono<String> transitionTenant(@Param("tenant") UUID tenant,@Param("original") byte[] original);
    @Query("SELECT public.cfg_native_suspend_status(:tenant,:status,:original)::text AS result")
    Mono<String> suspendStatus(@Param("tenant") UUID tenant,@Param("status") UUID status,@Param("original") byte[] original);

    @Query("SELECT public.cfg_native_transition_status(:tenant,:status,:original)::text AS result")
    Mono<String> transitionStatus(@Param("tenant") UUID tenant,@Param("status") UUID status,@Param("original") byte[] original);

    @Query("SELECT public.cfg_native_prepare_grant(:tenant,:original)::text AS result")
    Mono<String> prepareGrant(@Param("tenant") UUID tenant,@Param("original") byte[] original);
    @Query("SELECT public.cfg_native_issue_grant_v2(:tenant,:original)::text AS result")
    Mono<String> issueGrant(@Param("tenant") UUID tenant,@Param("original") byte[] original);
    @Query("SELECT public.cfg_native_observe_grant(:tenant,:institution,:issuer,:use)::text AS result")
    Mono<String> observeGrant(@Param("tenant") UUID tenant,@Param("institution") UUID institution,@Param("issuer") UUID issuer,@Param("use") String use);
    @Query("SELECT public.cfg_native_read_grant_history(:tenant,:institution,:issuer,:grant,:version)::text AS result")
    Mono<String> readGrantHistory(@Param("tenant") UUID tenant,@Param("institution") UUID institution,@Param("issuer") UUID issuer,@Param("grant") UUID grant,@Param("version") Long version);

    @Query("SELECT public.cfg_native_prepare_grant_control(:tenant,:institution,:original)::text AS result")
    Mono<String> prepareGrantControl(@Param("tenant") UUID tenant,@Param("institution") UUID institution,@Param("original") byte[] original);
    @Query("SELECT public.cfg_native_control_grant_v2(:tenant,:original)::text AS result")
    Mono<String> controlGrant(@Param("tenant") UUID tenant,@Param("original") byte[] original);

    @Query("SELECT public.cfg_native_reconcile_grant(:tenant,:action,:original)::text AS result")
    Mono<String> reconcileGrant(@Param("tenant") UUID tenant,@Param("action") UUID action,@Param("original") byte[] original);

}
