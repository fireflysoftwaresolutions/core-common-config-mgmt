package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.fireflysoftware.common.config.interfaces.authority.InstitutionAuthorityUse;
import org.junit.jupiter.api.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL invariants with synthetic server admissions; JWT/OPA transport is a separate gate. */
class InstitutionAuthorityGrantNativeIT {
    static final JsonMapper JSON=new JsonMapper();
    static final String MAKER="00000000-0000-0000-0000-000000000101",CHECKER="00000000-0000-0000-0000-000000000102";
    @BeforeAll static void lease() throws Exception {TenantEligibilityNativeIT.lease();}
    static JsonNode invoke(String platform,String tenant,String institution,String action,String resource,String type,String operation,String canonical,String employee,String sql,Object... arguments) throws Exception {
        try(var c=TenantEligibilityNativeIT.runtime()) {
            c.setAutoCommit(false);TenantEligibilityNativeIT.context(c,tenant,action,resource,type,operation,AuthorityWire.sha(canonical),employee==null?"MACHINE":"STAFF",System.currentTimeMillis()+30000);
            try(var p=c.prepareStatement("select set_config('firefly.cfg.platform_recovery','true',true),set_config('firefly.cfg.actor_tenant',?,true),set_config('firefly.cfg.institution',?,true),set_config('firefly.cfg.actor_subject',?,true),set_config('firefly.cfg.employee',?,true)")){p.setString(1,platform);p.setString(2,institution==null?"":institution);p.setString(3,employee==null?"platform-machine":"platform-staff/"+employee);p.setString(4,employee==null?"":employee);p.executeQuery().close();}
            try(var p=c.prepareStatement(sql)){for(int i=0;i<arguments.length;i++){if(arguments[i] instanceof byte[] bytes)p.setBytes(i+1,bytes);else p.setString(i+1,arguments[i].toString());}try(var rows=p.executeQuery()){assertTrue(rows.next());var result=JSON.readTree(rows.getString(1));c.commit();return result;}}
        }
    }
    static JsonNode prepare(String platform,String tenant,JsonNode previous,long expected) throws Exception {
        return prepare(platform,tenant,previous,expected,120000);
    }
    static JsonNode prepare(String platform,String tenant,JsonNode previous,long expected,long validityMillis) throws Exception {
        String institution=previous==null?null:previous.get("grant").get("institutionId").textValue(),issuer=previous==null?null:previous.get("grant").get("issuerAuthorityId").textValue();long now=System.currentTimeMillis();
        var raw="{\"actionId\":\""+UUID.randomUUID()+"\",\"targetActionId\":\""+UUID.randomUUID()+"\",\"notAfterEpochMillis\":"+(now+validityMillis)+",\"draft\":{\"existingInstitutionId\":"+(institution==null?"null":"\""+institution+"\"")+",\"existingIssuerAuthorityId\":"+(issuer==null?"null":"\""+issuer+"\"")+",\"expectedGrantHeadVersion\":"+expected+",\"uses\":[\"ACCOUNTING_BOOK\"],\"effectiveFromEpochMillis\":"+(now-1000)+",\"effectiveToEpochMillis\":"+(now+86400000)+",\"reason\":\"Fictional native institution admission\",\"evidenceReferences\":[]}}";
        var intent=InstitutionAuthorityGrantPrepareCodec.decode(tenant,raw.getBytes(StandardCharsets.UTF_8));var result=invoke(platform,tenant,institution,intent.request().actionId(),tenant,"INSTITUTION_AUTHORITY","PREPARE",intent.canonicalIntent(),MAKER,"select public.cfg_native_prepare_grant(?::uuid,?::bytea)",tenant,raw.getBytes(StandardCharsets.UTF_8)).get("receipt").get("result");
        var target=InstitutionAuthorityGrantV2Codec.decodeIssue(tenant,result.get("target").toString().getBytes(StandardCharsets.UTF_8));assertEquals(target.canonicalIntent(),result.get("targetCanonical").textValue());return result;
    }
    static void approve(String platform,String tenant,JsonNode prepared) throws Exception {
        var institution=prepared.get("target").get("grant").get("institutionId").textValue();var proposal=prepared.get("proposalId").textValue();
        for(var operation:List.of("submit","approve")) {
            var raw="{\"actionId\":\""+UUID.randomUUID()+"\",\"expectedProposalControlVersion\":"+(operation.equals("submit")?0:1)+",\"proposalRevision\":1,\"targetIntentDigest\":\""+prepared.get("targetDigest").textValue()+"\",\"reason\":\"Independent fictional institutional review\"}";
            var intent=NativeAuthorityDecisionCodec.decode(tenant,proposal,operation,raw.getBytes(StandardCharsets.UTF_8),prepared.get("targetCanonical").textValue());invoke(platform,tenant,institution,intent.request().actionId(),proposal,"PROPOSAL",operation.toUpperCase(Locale.ROOT),intent.canonicalIntent(),operation.equals("submit")?MAKER:CHECKER,"select public.cfg_native_decide(?::uuid,?::uuid,?,?::bytea)",tenant,proposal,operation,raw.getBytes(StandardCharsets.UTF_8));
        }
    }
    static JsonNode issue(String platform,String tenant,JsonNode prepared) throws Exception {
        var target=prepared.get("target");var grant=target.get("grant");return invoke(platform,tenant,grant.get("institutionId").textValue(),grant.get("actionId").textValue(),grant.get("proposalId").textValue(),"PROPOSAL","GRANT_ISSUE",prepared.get("targetCanonical").textValue(),null,"select public.cfg_native_issue_grant_v2(?::uuid,?::bytea)",tenant,target.toString().getBytes(StandardCharsets.UTF_8));
    }
    static JsonNode observe(String platform,String tenant,JsonNode target) throws Exception {
        var grant=target.get("grant");var institution=grant.get("institutionId").textValue();var issuer=grant.get("issuerAuthorityId").textValue();var intent=InstitutionAuthorityGrantReadCodec.observe(tenant,institution,issuer,InstitutionAuthorityUse.ACCOUNTING_BOOK);
        return invoke(platform,tenant,institution,UUID.randomUUID().toString(),issuer,"GRANT","OBSERVE",intent.canonicalIntent(),null,"select public.cfg_native_observe_grant(?::uuid,?::uuid,?::uuid,?)",tenant,institution,issuer,"ACCOUNTING_BOOK");
    }
    @Test void actualNonBankBootstrapIssuesFullImmutableGrantAndCurrentObservation() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_issue_grant_v2(uuid,bytea)");TenantEligibilityNativeIT.requireProtocol("public.cfg_native_observe_grant(uuid,uuid,uuid,text)");
        var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var prepared=prepare(platform,tenant,null,0);approve(platform,tenant,prepared);var result=issue(platform,tenant,prepared);var definition=result.get("receipt").get("result");
        assertEquals(definition.get("definitionDigest").textValue(),InstitutionAuthorityGrantDefinitionCodec.decodeDefinition(tenant,definition.toString().getBytes(StandardCharsets.UTF_8)).definitionDigest());assertEquals(platform,result.get("receipt").get("actor").get("tenantId").textValue());assertEquals("MACHINE",result.get("receipt").get("actor").get("kind").textValue());assertTrue(result.get("receipt").get("actor").get("employeeId").isNull());assertEquals(result.get("receipt"),issue(platform,tenant,prepared).get("receipt"));
        var observed=observe(platform,tenant,prepared.get("target"));assertEquals("CURRENT_AUTHORIZED",observed.get("state").textValue());assertTrue(observed.get("validUntilEpochMillis").longValue()>observed.get("observedAtEpochMillis").longValue());assertTrue(observed.get("validUntilEpochMillis").longValue()<=observed.get("observedAtEpochMillis").longValue()+30000);
        try(var c=TenantEligibilityNativeIT.owner();var p=c.prepareStatement("select to_regclass('public.banks'),to_regclass('public.legal_entities')")){var rows=p.executeQuery();assertTrue(rows.next());assertNull(rows.getString(1));assertNull(rows.getString(2));}
    }
    @Test void priorGrantAndApprovedProposalStayInvalidAfterTenantRecoveryUntilNewAdmission() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_issue_grant_v2(uuid,bytea)");TenantEligibilityNativeIT.requireProtocol("public.cfg_native_observe_grant(uuid,uuid,uuid,text)");
        var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var first=prepare(platform,tenant,null,0);approve(platform,tenant,first);issue(platform,tenant,first);var stale=prepare(platform,tenant,first.get("target"),1);approve(platform,tenant,stale);
        TenantEligibilityNativeIT.suspend(tenant,TenantEligibilityNativeIT.suspendJson(UUID.randomUUID().toString()));var recovery=TenantEligibilityNativeIT.recovery(tenant);TenantEligibilityNativeIT.decide(tenant,recovery,"submit",0,MAKER);TenantEligibilityNativeIT.decide(tenant,recovery,"approve",1,CHECKER);TenantEligibilityNativeIT.executeRecovery(tenant,recovery);
        assertEquals("ELIGIBILITY_CHANGED",observe(platform,tenant,first.get("target")).get("denialReason").textValue());assertEquals("55000",assertThrows(SQLException.class,()->issue(platform,tenant,stale)).getSQLState());
        var admitted=prepare(platform,tenant,first.get("target"),1);approve(platform,tenant,admitted);issue(platform,tenant,admitted);var observed=observe(platform,tenant,admitted.get("target"));assertEquals("CURRENT_AUTHORIZED",observed.get("state").textValue());assertEquals(2,observed.get("headVersion").longValue());assertEquals("55000",assertThrows(SQLException.class,()->issue(platform,tenant,stale)).getSQLState());
    }
}
