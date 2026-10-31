package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatusEligibilityNativeIT {
    private static final JsonMapper JSON=new JsonMapper();
    private static final String MAKER="00000000-0000-0000-0000-000000000101",CHECKER="00000000-0000-0000-0000-000000000102";
    @BeforeAll static void lease() throws Exception {TenantEligibilityNativeIT.lease();}
    static String status() throws Exception {
        var status=UUID.randomUUID().toString();
        try(var c=TenantEligibilityNativeIT.owner();var p=c.prepareStatement("insert into public.tenant_statuses(id,code,name,active) values(?::uuid,?,'Fictional shared status',true)")){p.setString(1,status);p.setString(2,"NATIVE_"+status.replace("-",""));assertEquals(1,p.executeUpdate());}return status;
    }
    static String member(String status) throws Exception {
        var tenant=UUID.randomUUID().toString();try(var c=TenantEligibilityNativeIT.owner();var p=c.prepareStatement("insert into public.tenants(id,code,name,tenant_status_id,active) values(?::uuid,?,'Fictional status member',?::uuid,true)")){p.setString(1,tenant);p.setString(2,"native-"+tenant);p.setString(3,status);assertEquals(1,p.executeUpdate());}return tenant;
    }
    static JsonNode invoke(String tenant,String action,String resource,String type,String operation,String canonical,String employee,String sql,List<Object> arguments) throws Exception {
        try(var c=TenantEligibilityNativeIT.runtime()) {
            c.setAutoCommit(false);TenantEligibilityNativeIT.context(c,tenant,action,resource,type,operation,AuthorityWire.sha(canonical),employee==null?"MACHINE":"STAFF",System.currentTimeMillis()+30000);
            try(var p=c.createStatement()){p.execute("select set_config('firefly.cfg.platform_recovery','true',true)");}
            if(employee!=null)try(var p=c.prepareStatement("select set_config('firefly.cfg.employee',?,true),set_config('firefly.cfg.actor_subject',?,true)")){p.setString(1,employee);p.setString(2,"qualified-staff/"+employee);p.executeQuery().close();}
            try(var p=c.prepareStatement(sql)){for(int i=0;i<arguments.size();i++){var value=arguments.get(i);if(value instanceof byte[] bytes)p.setBytes(i+1,bytes);else p.setString(i+1,value.toString());}try(var rows=p.executeQuery()){assertTrue(rows.next());var result=JSON.readTree(rows.getString(1));c.commit();return result;}}
        }
    }
    static JsonNode suspend(String tenant,String status,String raw) throws Exception {
        var intent=TenantEligibilityIntentCodec.decodeStatusSuspend(status,raw.getBytes(StandardCharsets.UTF_8));
        return invoke(tenant,intent.request().actionId(),status,"STATUS_ELIGIBILITY","STATUS_SUSPEND",intent.canonicalIntent(),null,"select public.cfg_native_suspend_status(?::uuid,?::uuid,?::bytea)",List.of(tenant,status,raw.getBytes(StandardCharsets.UTF_8)));
    }
    static JsonNode recovery(String tenant,String status) throws Exception {
        var raw="{\"actionId\":\""+UUID.randomUUID()+"\",\"targetActionId\":\""+UUID.randomUUID()+"\",\"notAfterEpochMillis\":"+(System.currentTimeMillis()+120000)+",\"draft\":{\"targetActive\":true,\"reason\":\"Reviewed fictional shared recovery\",\"evidenceReferences\":[]}}";
        var intent=TenantEligibilityIntentCodec.decodeStatusPrepare(status,raw.getBytes(StandardCharsets.UTF_8));
        var result=invoke(tenant,intent.request().actionId(),status,"STATUS_ELIGIBILITY","PREPARE",intent.canonicalIntent(),MAKER,"select public.cfg_native_prepare_transition(?::uuid,?::uuid,true,?::bytea)",List.of(tenant,status,raw.getBytes(StandardCharsets.UTF_8))).get("receipt").get("result");
        var target=TenantEligibilityIntentCodec.decodeStatusTransition(status,result.get("target").toString().getBytes(StandardCharsets.UTF_8));assertEquals(target.canonicalIntent(),result.get("targetCanonical").textValue());return result;
    }
    static void decide(String tenant,JsonNode prepared,String operation,long version,String employee) throws Exception {
        var proposal=prepared.get("proposalId").textValue();var raw="{\"actionId\":\""+UUID.randomUUID()+"\",\"expectedProposalControlVersion\":"+version+",\"proposalRevision\":1,\"targetIntentDigest\":\""+prepared.get("targetDigest").textValue()+"\",\"reason\":\"Reviewed fictional status decision\"}";
        var intent=NativeAuthorityDecisionCodec.decode(tenant,proposal,operation,raw.getBytes(StandardCharsets.UTF_8),prepared.get("targetCanonical").textValue());
        invoke(tenant,intent.request().actionId(),proposal,"PROPOSAL",operation.toUpperCase(Locale.ROOT),intent.canonicalIntent(),employee,"select public.cfg_native_decide(?::uuid,?::uuid,?,?::bytea)",List.of(tenant,proposal,operation,raw.getBytes(StandardCharsets.UTF_8)));
    }
    static void approve(String tenant,JsonNode prepared) throws Exception {decide(tenant,prepared,"submit",0,MAKER);decide(tenant,prepared,"approve",1,CHECKER);}
    static JsonNode execute(String tenant,String status,JsonNode prepared) throws Exception {
        var target=prepared.get("target");return invoke(tenant,target.get("actionId").textValue(),status,"STATUS_ELIGIBILITY","STATUS_TRANSITION",prepared.get("targetCanonical").textValue(),null,"select public.cfg_native_transition_status(?::uuid,?::uuid,?::bytea)",List.of(tenant,status,target.toString().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void sharedStatusDisableAndGovernedRecoveryInvalidateMemberApprovalsWithoutFanout() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_suspend_status(uuid,uuid,bytea)");TenantEligibilityNativeIT.requireProtocol("public.cfg_native_transition_status(uuid,uuid,bytea)");
        var auditTenant=TenantEligibilityNativeIT.tenant();var status=status();var first=member(status);var second=member(status);
        var firstTarget=TenantEligibilityNativeIT.recovery(first);TenantEligibilityNativeIT.decide(first,firstTarget,"submit",0,MAKER);TenantEligibilityNativeIT.decide(first,firstTarget,"approve",1,CHECKER);
        var secondTarget=TenantEligibilityNativeIT.recovery(second);TenantEligibilityNativeIT.decide(second,secondTarget,"submit",0,MAKER);TenantEligibilityNativeIT.decide(second,secondTarget,"approve",1,CHECKER);
        var raw=TenantEligibilityNativeIT.suspendJson(UUID.randomUUID().toString());var result=suspend(auditTenant,status,raw);assertFalse(result.get("receipt").get("result").get("enabled").booleanValue());assertEquals(1,result.get("receipt").get("result").get("generation").longValue());assertEquals(result.get("receipt"),suspend(auditTenant,status,raw).get("receipt"));
        try(var c=TenantEligibilityNativeIT.owner();var p=c.prepareStatement("select t.active,e.generation from public.tenants t join public.cfg_native_tenant_eligibility e on e.tenant_id=t.id where t.id in (?::uuid,?::uuid)")){p.setString(1,first);p.setString(2,second);var rows=p.executeQuery();int count=0;while(rows.next()){count++;assertTrue(rows.getBoolean(1));assertEquals(0,rows.getLong(2));}assertEquals(2,count);}
        assertEquals("55000",assertThrows(SQLException.class,()->TenantEligibilityNativeIT.executeRecovery(first,firstTarget)).getSQLState());
        var current=recovery(auditTenant,status);approve(auditTenant,current);var recovered=execute(auditTenant,status,current);assertTrue(recovered.get("receipt").get("result").get("enabled").booleanValue());assertEquals(2,recovered.get("receipt").get("result").get("generation").longValue());
        assertEquals("55000",assertThrows(SQLException.class,()->TenantEligibilityNativeIT.executeRecovery(first,firstTarget)).getSQLState());assertEquals("55000",assertThrows(SQLException.class,()->TenantEligibilityNativeIT.executeRecovery(second,secondTarget)).getSQLState());
    }
    @Test void laterEmergencyInvalidatesAlreadyApprovedStatusRecoveryPermanently() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_suspend_status(uuid,uuid,bytea)");TenantEligibilityNativeIT.requireProtocol("public.cfg_native_transition_status(uuid,uuid,bytea)");
        var tenant=TenantEligibilityNativeIT.tenant();var status=status();suspend(tenant,status,TenantEligibilityNativeIT.suspendJson(UUID.randomUUID().toString()));var stale=recovery(tenant,status);approve(tenant,stale);suspend(tenant,status,TenantEligibilityNativeIT.suspendJson(UUID.randomUUID().toString()));assertEquals("55000",assertThrows(SQLException.class,()->execute(tenant,status,stale)).getSQLState());
        var current=recovery(tenant,status);approve(tenant,current);assertEquals(3,execute(tenant,status,current).get("receipt").get("result").get("generation").longValue());assertEquals("55000",assertThrows(SQLException.class,()->execute(tenant,status,stale)).getSQLState());
    }
}
