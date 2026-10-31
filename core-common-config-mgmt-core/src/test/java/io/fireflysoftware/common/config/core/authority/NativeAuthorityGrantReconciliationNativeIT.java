package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeAuthorityGrantReconciliationNativeIT {
    @BeforeAll static void lease() throws Exception {TenantEligibilityNativeIT.lease();}
    static JsonNode reconcile(String platform,String tenant,JsonNode prepared) throws Exception {
        return reconcile(platform,tenant,prepared,"GRANT_ISSUE");
    }
    static JsonNode reconcile(String platform,String tenant,JsonNode prepared,String operation) throws Exception {
        var target=prepared.get("target");var grant=operation.equals("GRANT_ISSUE")?target.get("grant"):target;var action=grant.get("actionId").textValue();var body="{\"operation\":\""+operation+"\",\"originalBase64\":\""+Base64.getEncoder().encodeToString(target.toString().getBytes(StandardCharsets.UTF_8))+"\"}";
        var intent=NativeAuthorityGrantReconciliationCodec.decode(tenant,action,body.getBytes(StandardCharsets.UTF_8));return InstitutionAuthorityGrantNativeIT.invoke(platform,tenant,grant.get("institutionId").textValue(),action,action,"ACTION","RECONCILE",intent.canonicalIntent(),null,"select public.cfg_native_reconcile_grant(?::uuid,?::uuid,?::bytea)",tenant,action,body.getBytes(StandardCharsets.UTF_8));
    }
    @Test void elapsedOriginalDeadlineHasDurableNegativeFenceAndNoGrantEffect() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_reconcile_grant(uuid,uuid,bytea)");var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var prepared=InstitutionAuthorityGrantNativeIT.prepare(platform,tenant,null,0,2000);InstitutionAuthorityGrantNativeIT.approve(platform,tenant,prepared);
        assertEquals("55000",assertThrows(SQLException.class,()->reconcile(platform,tenant,prepared)).getSQLState());
        Thread.sleep(2100);TenantEligibilityNativeIT.suspend(tenant,TenantEligibilityNativeIT.suspendJson(UUID.randomUUID().toString()));
        var negative=reconcile(platform,tenant,prepared);assertEquals("NO_EFFECT_FINAL",negative.get("receipt").get("resultKind").textValue());assertEquals(negative.get("receipt"),reconcile(platform,tenant,prepared).get("receipt"));assertEquals(negative.get("receipt"),InstitutionAuthorityGrantNativeIT.issue(platform,tenant,prepared).get("receipt"));
        try(var c=TenantEligibilityNativeIT.owner();var query=c.prepareStatement("select (select count(*) from public.cfg_native_grant_version where tenant_id=?::uuid),(select state from public.cfg_native_proposal_control where tenant_id=?::uuid and proposal_id=?::uuid)")){query.setString(1,tenant);query.setString(2,tenant);query.setString(3,prepared.get("proposalId").textValue());var rows=query.executeQuery();assertTrue(rows.next());assertEquals(0,rows.getInt(1));assertEquals("NO_EFFECT_FINAL",rows.getString(2));}
    }
    @Test void committedActionReconcilesToItsOriginalPositiveReceipt() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_reconcile_grant(uuid,uuid,bytea)");var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var prepared=InstitutionAuthorityGrantNativeIT.prepare(platform,tenant,null,0);InstitutionAuthorityGrantNativeIT.approve(platform,tenant,prepared);var positive=InstitutionAuthorityGrantNativeIT.issue(platform,tenant,prepared);
        assertEquals(positive.get("receipt"),reconcile(platform,tenant,prepared).get("receipt"));
    }
    @Test void negativeControlFenceRetainsActiveIssuedVersionWithoutControlEffect() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_reconcile_grant(uuid,uuid,bytea)");var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var grant=InstitutionAuthorityGrantNativeIT.prepare(platform,tenant,null,0);InstitutionAuthorityGrantNativeIT.approve(platform,tenant,grant);InstitutionAuthorityGrantNativeIT.issue(platform,tenant,grant);
        var control=InstitutionAuthorityGrantControlNativeIT.prepare(platform,tenant,grant.get("target"),0,"ACTIVE","SUSPENDED",2000);InstitutionAuthorityGrantControlNativeIT.approve(platform,tenant,control);Thread.sleep(2100);
        var negative=reconcile(platform,tenant,control,"GRANT_CONTROL");assertEquals("NO_EFFECT_FINAL",negative.get("receipt").get("resultKind").textValue());assertEquals(negative.get("receipt"),InstitutionAuthorityGrantControlNativeIT.execute(platform,tenant,control).get("receipt"));assertEquals("CURRENT_AUTHORIZED",InstitutionAuthorityGrantNativeIT.observe(platform,tenant,grant.get("target")).get("state").textValue());
        try(var c=TenantEligibilityNativeIT.owner();var query=c.prepareStatement("select count(*) from public.cfg_native_grant_control_event where tenant_id=?::uuid and action_id=?::uuid")){query.setString(1,tenant);query.setString(2,control.get("target").get("actionId").textValue());var rows=query.executeQuery();assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
    }

    @Test void concurrentAdmissionWaitsPastDeadlineThenReconciliationCertifiesOnlyNoEffect() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_reconcile_grant(uuid,uuid,bytea)");var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();long start=System.currentTimeMillis();var prepared=InstitutionAuthorityGrantNativeIT.prepare(platform,tenant,null,0,5000);InstitutionAuthorityGrantNativeIT.approve(platform,tenant,prepared);var action=prepared.get("target").get("grant").get("actionId").textValue();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var blocker=TenantEligibilityNativeIT.owner()) {
            blocker.setAutoCommit(false);try(var gate=blocker.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")){gate.setString(1,"cfg-native-action/"+tenant+"/"+action);gate.executeQuery().close();}
            var issue=pool.submit(()->InstitutionAuthorityGrantNativeIT.issue(platform,tenant,prepared));boolean waiting=false;
            try(var probe=TenantEligibilityNativeIT.owner();var query=probe.prepareStatement("select exists(select 1 from pg_locks l join pg_stat_activity a on a.pid=l.pid where l.locktype='advisory' and not l.granted and a.datname=current_database())")) {
                for(int attempt=0;attempt<100;attempt++){try(var rows=query.executeQuery()){assertTrue(rows.next());waiting=rows.getBoolean(1);}if(waiting)break;Thread.sleep(20);}
            }
            assertTrue(waiting,"Original native admission must actually reach the final action wait");var reconciliation=pool.submit(()->reconcile(platform,tenant,prepared));Thread.sleep(Math.max(1,start+5200-System.currentTimeMillis()));blocker.commit();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->issue.get(15,java.util.concurrent.TimeUnit.SECONDS));assertInstanceOf(SQLException.class,failure.getCause());assertEquals("55000",((SQLException)failure.getCause()).getSQLState());assertEquals("NO_EFFECT_FINAL",reconciliation.get(15,java.util.concurrent.TimeUnit.SECONDS).get("receipt").get("resultKind").textValue());
        }
    }

}
