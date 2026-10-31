package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native invariant qualification uses fictional server admission tuples; it is not JWT/OPA acceptance. */
class TenantEligibilityNativeIT {
    private static final String STAFF="00000000-0000-0000-0000-000000000101";
    private static final String ISSUER="https://native-qualification.example.test";
    private static String runtimePassword;
    private static String jdbcUrl;
    private static final JsonMapper JSON=new JsonMapper();

    @BeforeAll static void lease() throws Exception {
        InstitutionAuthorityNativeIT.requireExplicitOwnedNativeLease();
        runtimePassword=InstitutionAuthorityNativeIT.required("CFG_NATIVE_RUNTIME_PASSWORD");jdbcUrl=InstitutionAuthorityNativeIT.required("CFG_NATIVE_JDBC_URL");
    }
    static Connection owner() throws Exception{return InstitutionAuthorityNativeIT.owner();}
    static String tenant() throws Exception {
        var tenant=UUID.randomUUID().toString();
        try(var c=owner();var p=c.prepareStatement("insert into public.tenants(id,code,name,tenant_status_id,active) select ?::uuid,?, 'Fictional qualification tenant',id,true from public.tenant_statuses where code='ACTIVE'")) {p.setString(1,tenant);p.setString(2,"native-"+tenant);assertEquals(1,p.executeUpdate());}
        return tenant;
    }
    static Connection runtime() throws Exception {
        try(var c=owner();var p=c.prepareStatement("select exists(select 1 from pg_catalog.pg_roles where rolname='cfg_native_runtime')")) {
            var rows=p.executeQuery();rows.next();assertTrue(rows.getBoolean(1),"Native runtime role is missing");
            try(var quote=c.prepareStatement("select quote_literal(?)")){quote.setString(1,runtimePassword);var quoted=quote.executeQuery();quoted.next();try(var s=c.createStatement()){s.execute("alter role cfg_native_runtime password "+quoted.getString(1));}}
        }
        return DriverManager.getConnection(jdbcUrl,"cfg_native_runtime",runtimePassword);
    }
    static void context(Connection c,String tenant,String action,String resource,String resourceType,String operation,String digest,String kind,long until) throws Exception {
        var settings=new LinkedHashMap<String,String>();settings.put("xid",null);settings.put("tenant",tenant);settings.put("action",action);settings.put("resource",resource);settings.put("resource_type",resourceType);settings.put("operation",operation);settings.put("intent",digest);settings.put("actor_kind",kind);settings.put("actor_issuer",ISSUER);settings.put("actor_subject",kind.equals("MACHINE")?"qualified-emergency-machine":"qualified-staff");settings.put("actor_tenant",tenant);settings.put("employee",kind.equals("STAFF")?STAFF:"");settings.put("client",kind.equals("MACHINE")?"qualified-emergency-machine":"");settings.put("platform_recovery","false");settings.put("authority_until",Long.toString(until));settings.put("command_until",Long.toString(until));
        for(var item:settings.entrySet()) {
            try(var p=c.prepareStatement(item.getKey().equals("xid")?"select set_config('firefly.cfg.xid',pg_current_xact_id()::text,true)":"select set_config(?,?,true)")) {
                if(!item.getKey().equals("xid")){p.setString(1,"firefly.cfg."+item.getKey());p.setString(2,item.getValue());}p.executeQuery().close();
            }
        }
    }
    static String suspendJson(String action) {return "{\"actionId\":\""+action+"\",\"notAfterEpochMillis\":"+(System.currentTimeMillis()+120000)+",\"reason\":\"Fictional emergency disable\",\"evidenceReferences\":[\"incident:qualification-only\"]}";}
    static JsonNode suspend(String tenant,String raw) throws Exception {
        var intent=TenantEligibilityIntentCodec.decodeSuspend(tenant,raw.getBytes(StandardCharsets.UTF_8));
        try(var c=runtime()) {
            c.setAutoCommit(false);context(c,tenant,intent.request().actionId(),tenant,"TENANT_ELIGIBILITY","TENANT_SUSPEND",intent.intentDigest(),"MACHINE",System.currentTimeMillis()+30000);
            try(var p=c.prepareStatement("select public.cfg_native_suspend_tenant(?::uuid,?::bytea)")) {p.setString(1,tenant);p.setBytes(2,raw.getBytes(StandardCharsets.UTF_8));try(var rows=p.executeQuery()){assertTrue(rows.next());var result=JSON.readTree(rows.getString(1));c.commit();return result;}}
        }
    }
    static void requireProtocol(String name) throws Exception {
        try(var c=owner();var p=c.prepareStatement("select to_regprocedure(?)")){p.setString(1,name);var rows=p.executeQuery();rows.next();assertNotNull(rows.getString(1),"Actual native lifecycle function is missing");}
    }
    @Test void emergencyActuallyDisablesTenantAndReplayRetainsOneCompleteEvidenceSet() throws Exception {
        requireProtocol("public.cfg_native_suspend_tenant(uuid,bytea)");var tenant=tenant();var action=UUID.randomUUID().toString();var raw=suspendJson(action);
        var accepted=suspend(tenant,raw);assertFalse(accepted.get("replay").booleanValue());assertEquals(raw,new String(Base64.getDecoder().decode(accepted.get("receipt").get("originalBase64").textValue()),StandardCharsets.UTF_8));assertFalse(accepted.get("receipt").get("result").get("tenantActive").booleanValue());
        var replay=suspend(tenant,raw);assertTrue(replay.get("replay").booleanValue());assertEquals(accepted.get("receipt"),replay.get("receipt"));
        try(var c=owner();var p=c.prepareStatement("select t.active,e.generation,(select count(*) from public.cfg_native_action a where a.tenant_id=t.id),(select count(*) from public.cfg_native_receipt r where r.tenant_id=t.id),(select count(*) from public.cfg_native_audit a where a.tenant_id=t.id),(select count(*) from public.cfg_native_outbox o where o.tenant_id=t.id) from public.tenants t join public.cfg_native_tenant_eligibility e on e.tenant_id=t.id where t.id=?::uuid")) {
            p.setString(1,tenant);var rows=p.executeQuery();assertTrue(rows.next());assertFalse(rows.getBoolean(1));assertEquals(1,rows.getLong(2));for(int i=3;i<=6;i++)assertEquals(1,rows.getLong(i));
        }
        try(var c=owner();var p=c.prepareStatement("select actor_kind,actor_employee_id,actor_client_id from public.cfg_native_action where tenant_id=?::uuid and action_id=?::uuid")){p.setString(1,tenant);p.setString(2,action);var rows=p.executeQuery();assertTrue(rows.next());assertEquals("MACHINE",rows.getString(1));assertNull(rows.getObject(2));assertEquals("qualified-emergency-machine",rows.getString(3));}
    }
    @Test void missingOutboxInsertRollsBackSourceFlagGenerationAndReceipt() throws Exception {
        requireProtocol("public.cfg_native_suspend_tenant(uuid,bytea)");var tenant=tenant();var raw=suspendJson(UUID.randomUUID().toString());var constraint="cfg_test_outbox_"+UUID.randomUUID().toString().replace("-","");
        try(var c=owner();var s=c.createStatement()){s.execute("alter table public.cfg_native_outbox add constraint "+constraint+" check(tenant_id<>'"+tenant+"'::uuid)");}
        try {
            assertThrows(SQLException.class,()->suspend(tenant,raw));
            try(var c=owner();var p=c.prepareStatement("select active,(select count(*) from public.cfg_native_tenant_eligibility e where e.tenant_id=t.id),(select count(*) from public.cfg_native_action a where a.tenant_id=t.id),(select count(*) from public.cfg_native_audit a where a.tenant_id=t.id) from public.tenants t where t.id=?::uuid")){p.setString(1,tenant);var rows=p.executeQuery();assertTrue(rows.next());assertTrue(rows.getBoolean(1));assertEquals(0,rows.getLong(2));assertEquals(0,rows.getLong(3));assertEquals(0,rows.getLong(4));}
        } finally {try(var c=owner();var s=c.createStatement()){s.execute("alter table public.cfg_native_outbox drop constraint "+constraint);}}
    }
    @Test void forgedRuntimeSessionFlagsCannotBypassSourceOrNativeTableGuards() throws Exception {
        requireProtocol("public.cfg_native_suspend_tenant(uuid,bytea)");var tenant=tenant();suspend(tenant,suspendJson(UUID.randomUUID().toString()));
        try(var c=runtime()) {
            c.setAutoCommit(false);context(c,tenant,UUID.randomUUID().toString(),tenant,"TENANT_ELIGIBILITY","TENANT_TRANSITION","a".repeat(64),"STAFF",System.currentTimeMillis()+30000);
            try(var p=c.createStatement()){p.execute("select set_config('firefly.cfg.lifecycle_write','TENANT',true)");}
            try(var p=c.prepareStatement("update public.tenants set active=true where id=?::uuid")){p.setString(1,tenant);assertEquals("42501",assertThrows(SQLException.class,p::executeUpdate).getSQLState());}c.rollback();
        }
        try(var c=runtime();var p=c.prepareStatement("update public.cfg_native_tenant_eligibility set enabled=true where tenant_id=?::uuid")){p.setString(1,tenant);assertEquals("42501",assertThrows(SQLException.class,p::executeUpdate).getSQLState());}
        try(var c=runtime();var p=c.prepareStatement("update public.tenants set name='Fictional metadata update' where id=?::uuid")){p.setString(1,tenant);assertEquals(1,p.executeUpdate());}
        try(var c=owner();var p=c.prepareStatement("select active from public.tenants where id=?::uuid")){p.setString(1,tenant);var rows=p.executeQuery();assertTrue(rows.next());assertFalse(rows.getBoolean(1));}
    }
    @Test void missingCurrentDeadlineNeverAdmitsAnEffect() throws Exception {
        requireProtocol("public.cfg_native_suspend_tenant(uuid,bytea)");var tenant=tenant();var action=UUID.randomUUID().toString();var raw=suspendJson(action);var decoded=TenantEligibilityIntentCodec.decodeSuspend(tenant,raw.getBytes(StandardCharsets.UTF_8));
        try(var c=runtime()) {
            c.setAutoCommit(false);context(c,tenant,action,tenant,"TENANT_ELIGIBILITY","TENANT_SUSPEND",decoded.intentDigest(),"MACHINE",System.currentTimeMillis()+30000);
            try(var p=c.createStatement()){p.execute("select set_config('firefly.cfg.authority_until','',true)");}
            try(var p=c.prepareStatement("select public.cfg_native_suspend_tenant(?::uuid,?::bytea)")){p.setString(1,tenant);p.setBytes(2,raw.getBytes(StandardCharsets.UTF_8));assertEquals("42501",assertThrows(SQLException.class,p::executeQuery).getSQLState());}c.rollback();
        }
        try(var c=owner();var p=c.prepareStatement("select active,(select count(*) from public.cfg_native_action where tenant_id=t.id) from public.tenants t where id=?::uuid")){p.setString(1,tenant);var rows=p.executeQuery();assertTrue(rows.next());assertTrue(rows.getBoolean(1));assertEquals(0,rows.getLong(2));}
    }
    @Test void deferredCompletenessRejectsSilentlySuppressedOutboxRow() throws Exception {
        requireProtocol("public.cfg_native_suspend_tenant(uuid,bytea)");var tenant=tenant();var suffix=UUID.randomUUID().toString().replace("-","");var function="cfg_test_skip_"+suffix;var trigger="cfg_test_skip_"+suffix;
        try(var c=owner();var statement=c.createStatement()) {
            statement.execute("create function public."+function+"() returns trigger language plpgsql as $$ begin if NEW.tenant_id='"+tenant+"'::uuid then return null; end if; return NEW; end $$");
            statement.execute("create trigger "+trigger+" before insert on public.cfg_native_outbox for each row execute function public."+function+"()");
        }
        try {
            assertEquals("23514",assertThrows(SQLException.class,()->suspend(tenant,suspendJson(UUID.randomUUID().toString()))).getSQLState());
            try(var c=owner();var p=c.prepareStatement("select active,(select count(*) from public.cfg_native_action where tenant_id=t.id),(select count(*) from public.cfg_native_tenant_eligibility where tenant_id=t.id) from public.tenants t where id=?::uuid")){p.setString(1,tenant);var rows=p.executeQuery();assertTrue(rows.next());assertTrue(rows.getBoolean(1));assertEquals(0,rows.getLong(2));assertEquals(0,rows.getLong(3));}
        } finally {
            try(var c=owner();var statement=c.createStatement()){statement.execute("drop trigger "+trigger+" on public.cfg_native_outbox");statement.execute("drop function public."+function+"()");}
        }
    }

    static JsonNode invoke(String tenant,String action,String resource,String resourceType,String operation,String canonical,String employee,String sql,List<Object> arguments) throws Exception {
        try(var c=runtime()) {
            c.setAutoCommit(false);context(c,tenant,action,resource,resourceType,operation,AuthorityWire.sha(canonical),employee==null?"MACHINE":"STAFF",System.currentTimeMillis()+30000);
            if(employee!=null)try(var p=c.prepareStatement("select set_config('firefly.cfg.employee',?,true),set_config('firefly.cfg.actor_subject',?,true)")){p.setString(1,employee);p.setString(2,"qualified-staff/"+employee);p.executeQuery().close();}
            try(var p=c.prepareStatement(sql)){for(int i=0;i<arguments.size();i++){var value=arguments.get(i);if(value instanceof byte[] bytes)p.setBytes(i+1,bytes);else p.setString(i+1,value.toString());}try(var rows=p.executeQuery()){assertTrue(rows.next());var result=JSON.readTree(rows.getString(1));c.commit();return result;}}
        }
    }
    static JsonNode recovery(String tenant) throws Exception {
        var prepareAction=UUID.randomUUID().toString();var targetAction=UUID.randomUUID().toString();String status;
        try(var c=owner();var p=c.prepareStatement("select id from public.tenant_statuses where code='ACTIVE'")){var rows=p.executeQuery();assertTrue(rows.next());status=rows.getString(1);}
        var raw="{\"actionId\":\""+prepareAction+"\",\"targetActionId\":\""+targetAction+"\",\"notAfterEpochMillis\":"+(System.currentTimeMillis()+120000)+",\"draft\":{\"targetStatusId\":\""+status+"\",\"targetActive\":true,\"reason\":\"Reviewed fictional recovery\",\"evidenceReferences\":[]}}";
        String canonical;try(var c=owner();var p=c.prepareStatement("select public.cfg_native_prepare_transition_intent(?::uuid,?::json,false)")){p.setString(1,tenant);p.setString(2,raw);var rows=p.executeQuery();assertTrue(rows.next());canonical=rows.getString(1);}
        var result=invoke(tenant,prepareAction,tenant,"TENANT_ELIGIBILITY","PREPARE",canonical,STAFF,"select public.cfg_native_prepare_transition(?::uuid,?::uuid,false,?::bytea)",List.of(tenant,tenant,raw.getBytes(StandardCharsets.UTF_8))).get("receipt").get("result");
        var decoded=TenantEligibilityIntentCodec.decodeTransition(tenant,result.get("target").toString().getBytes(StandardCharsets.UTF_8));assertEquals(decoded.canonicalIntent(),result.get("targetCanonical").textValue());assertEquals(decoded.intentDigest(),result.get("targetDigest").textValue());
        return result;
    }
    static void decide(String tenant,JsonNode prepared,String operation,long version,String employee) throws Exception {
        var action=UUID.randomUUID().toString();var proposal=prepared.get("proposalId").textValue();
        var raw="{\"actionId\":\""+action+"\",\"expectedProposalControlVersion\":"+version+",\"proposalRevision\":1,\"targetIntentDigest\":\""+prepared.get("targetDigest").textValue()+"\",\"reason\":\"Fictional independent review\"}";
        invoke(tenant,action,proposal,"PROPOSAL",operation.toUpperCase(Locale.ROOT),NativeAuthorityDecisionCodec.decode(tenant,proposal,operation,raw.getBytes(StandardCharsets.UTF_8),prepared.get("targetCanonical").textValue()).canonicalIntent(),employee,"select public.cfg_native_decide(?::uuid,?::uuid,?,?::bytea)",List.of(tenant,proposal,operation,raw.getBytes(StandardCharsets.UTF_8)));
    }
    static JsonNode executeRecovery(String tenant,JsonNode prepared) throws Exception {
        var target=prepared.get("target");return invoke(tenant,target.get("actionId").textValue(),tenant,"TENANT_ELIGIBILITY","TENANT_TRANSITION",prepared.get("targetCanonical").textValue(),null,"select public.cfg_native_transition_tenant(?::uuid,?::bytea)",List.of(tenant,target.toString().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void governedRecoveryActuallyReactivatesTenantWithNewGeneration() throws Exception {
        requireProtocol("public.cfg_native_prepare_transition(uuid,uuid,boolean,bytea)");requireProtocol("public.cfg_native_decide(uuid,uuid,text,bytea)");requireProtocol("public.cfg_native_transition_tenant(uuid,bytea)");
        var tenant=tenant();suspend(tenant,suspendJson(UUID.randomUUID().toString()));var prepared=recovery(tenant);
        assertEquals(1,prepared.get("target").get("expectedEpoch").get("tenantGeneration").longValue());
        decide(tenant,prepared,"submit",0,STAFF);decide(tenant,prepared,"approve",1,"00000000-0000-0000-0000-000000000102");
        try(var c=owner();var p=c.prepareStatement("select active from public.tenants where id=?::uuid")){p.setString(1,tenant);var rows=p.executeQuery();assertTrue(rows.next());assertFalse(rows.getBoolean(1));}
        var result=executeRecovery(tenant,prepared);assertTrue(result.get("receipt").get("result").get("tenantActive").booleanValue());assertEquals(2,result.get("receipt").get("result").get("tenantGeneration").longValue());
    }
    @Test void secondEmergencyInvalidatesAlreadyApprovedRecoveryAcrossLaterReactivation() throws Exception {
        requireProtocol("public.cfg_native_prepare_transition(uuid,uuid,boolean,bytea)");requireProtocol("public.cfg_native_decide(uuid,uuid,text,bytea)");requireProtocol("public.cfg_native_transition_tenant(uuid,bytea)");
        var tenant=tenant();suspend(tenant,suspendJson(UUID.randomUUID().toString()));var stale=recovery(tenant);decide(tenant,stale,"submit",0,STAFF);decide(tenant,stale,"approve",1,"00000000-0000-0000-0000-000000000102");
        suspend(tenant,suspendJson(UUID.randomUUID().toString()));assertEquals("55000",assertThrows(SQLException.class,()->executeRecovery(tenant,stale)).getSQLState());
        var current=recovery(tenant);decide(tenant,current,"submit",0,STAFF);decide(tenant,current,"approve",1,"00000000-0000-0000-0000-000000000102");var result=executeRecovery(tenant,current);assertEquals(3,result.get("receipt").get("result").get("tenantGeneration").longValue());
        assertEquals("55000",assertThrows(SQLException.class,()->executeRecovery(tenant,stale)).getSQLState());
    }

    @Test void nativeApprovalRequiresDistinctStaffAndExactDecisionIntent() throws Exception {
        var tenant=tenant();suspend(tenant,suspendJson(UUID.randomUUID().toString()));var prepared=recovery(tenant);decide(tenant,prepared,"submit",0,STAFF);
        assertEquals("42501",assertThrows(SQLException.class,()->decide(tenant,prepared,"approve",1,STAFF)).getSQLState());
        assertEquals("42501",assertThrows(SQLException.class,()->decide(tenant,prepared,"approve",1,null)).getSQLState());
        var action=UUID.randomUUID().toString();var proposal=prepared.get("proposalId").textValue();
        var raw="{\"actionId\":\""+action+"\",\"expectedProposalControlVersion\":1,\"proposalRevision\":1,\"targetIntentDigest\":\""+prepared.get("targetDigest").textValue()+"\",\"reason\":\"Exact reviewed reason\"}";
        var canonical=NativeAuthorityDecisionCodec.decode(tenant,proposal,"approve",raw.getBytes(StandardCharsets.UTF_8),prepared.get("targetCanonical").textValue()).canonicalIntent();
        assertEquals("42501",assertThrows(SQLException.class,()->invoke(tenant,action,proposal,"PROPOSAL","APPROVE",canonical,"00000000-0000-0000-0000-000000000102","select public.cfg_native_decide(?::uuid,?::uuid,?,?::bytea)",List.of(tenant,proposal,"approve",raw.replace("Exact reviewed reason","Changed reason").getBytes(StandardCharsets.UTF_8)))).getSQLState());
        decide(tenant,prepared,"approve",1,"00000000-0000-0000-0000-000000000102");assertTrue(executeRecovery(tenant,prepared).get("receipt").get("result").get("tenantActive").booleanValue());
    }
    @Test void recoveryOutboxFailureRollsBackSourceGenerationAndApprovalConsumption() throws Exception {
        var tenant=tenant();suspend(tenant,suspendJson(UUID.randomUUID().toString()));var prepared=recovery(tenant);decide(tenant,prepared,"submit",0,STAFF);decide(tenant,prepared,"approve",1,"00000000-0000-0000-0000-000000000102");
        var constraint="cfg_test_recovery_"+UUID.randomUUID().toString().replace("-","");
        try(var c=owner();var statement=c.createStatement()){statement.execute("alter table public.cfg_native_outbox add constraint "+constraint+" check (tenant_id<>'"+tenant+"'::uuid OR event_kind<>'configuration.institution.tenant_transition') not valid");}
        try {
            assertEquals("23514",assertThrows(SQLException.class,()->executeRecovery(tenant,prepared)).getSQLState());
            try(var c=owner();var p=c.prepareStatement("select t.active,e.generation,c.state,c.control_version,c.execution_action_id from public.tenants t join public.cfg_native_tenant_eligibility e on e.tenant_id=t.id join public.cfg_native_proposal_control c on c.tenant_id=t.id where t.id=?::uuid and c.proposal_id=?::uuid")) {
                p.setString(1,tenant);p.setString(2,prepared.get("proposalId").textValue());var rows=p.executeQuery();assertTrue(rows.next());assertFalse(rows.getBoolean(1));assertEquals(1,rows.getLong(2));assertEquals("APPROVED",rows.getString(3));assertEquals(2,rows.getLong(4));assertNull(rows.getString(5));
            }
        } finally {try(var c=owner();var statement=c.createStatement()){statement.execute("alter table public.cfg_native_outbox drop constraint "+constraint);}}
        assertTrue(executeRecovery(tenant,prepared).get("receipt").get("result").get("tenantActive").booleanValue());
    }

}
