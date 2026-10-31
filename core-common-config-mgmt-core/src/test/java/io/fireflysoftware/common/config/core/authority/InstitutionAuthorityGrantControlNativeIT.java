package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InstitutionAuthorityGrantControlNativeIT {
    @BeforeAll static void lease() throws Exception {TenantEligibilityNativeIT.lease();}
    static JsonNode prepare(String platform,String tenant,JsonNode grantTarget,long controlVersion,String before,String after) throws Exception {
        return prepare(platform,tenant,grantTarget,controlVersion,before,after,120000);
    }
    static JsonNode prepare(String platform,String tenant,JsonNode grantTarget,long controlVersion,String before,String after,long validityMillis) throws Exception {
        var grant=grantTarget.get("grant");var institution=grant.get("institutionId").textValue();var raw="{\"actionId\":\""+UUID.randomUUID()+"\",\"targetActionId\":\""+UUID.randomUUID()+"\",\"notAfterEpochMillis\":"+(System.currentTimeMillis()+validityMillis)+",\"draft\":{\"grantId\":\""+grant.get("grantId").textValue()+"\",\"version\":"+grant.get("version").longValue()+",\"issuerAuthorityId\":\""+grant.get("issuerAuthorityId").textValue()+"\",\"expectedControlVersion\":"+controlVersion+",\"expectedState\":\""+before+"\",\"targetState\":\""+after+"\",\"reason\":\"Reviewed fictional grant control\",\"evidenceReferences\":[]}}";
        var intent=InstitutionAuthorityGrantControlCodec.decodePrepare(tenant,institution,raw.getBytes(StandardCharsets.UTF_8));var result=InstitutionAuthorityGrantNativeIT.invoke(platform,tenant,institution,intent.request().actionId(),grant.get("grantId").textValue(),"GRANT","PREPARE",intent.canonicalIntent(),InstitutionAuthorityGrantNativeIT.MAKER,"select public.cfg_native_prepare_grant_control(?::uuid,?::uuid,?::bytea)",tenant,institution,raw.getBytes(StandardCharsets.UTF_8)).get("receipt").get("result");
        assertEquals(result.get("targetCanonical").textValue(),InstitutionAuthorityGrantControlCodec.decodeTarget(tenant,result.get("target").toString().getBytes(StandardCharsets.UTF_8)).canonicalIntent());return result;
    }
    static void approve(String platform,String tenant,JsonNode prepared) throws Exception {
        var target=prepared.get("target");var proposal=prepared.get("proposalId").textValue();var institution=target.get("institutionId").textValue();
        for(var operation:List.of("submit","approve")) {
            var raw="{\"actionId\":\""+UUID.randomUUID()+"\",\"expectedProposalControlVersion\":"+(operation.equals("submit")?0:1)+",\"proposalRevision\":1,\"targetIntentDigest\":\""+prepared.get("targetDigest").textValue()+"\",\"reason\":\"Independent fictional control review\"}";var intent=NativeAuthorityDecisionCodec.decode(tenant,proposal,operation,raw.getBytes(StandardCharsets.UTF_8),prepared.get("targetCanonical").textValue());
            InstitutionAuthorityGrantNativeIT.invoke(platform,tenant,institution,intent.request().actionId(),proposal,"PROPOSAL",operation.toUpperCase(Locale.ROOT),intent.canonicalIntent(),operation.equals("submit")?InstitutionAuthorityGrantNativeIT.MAKER:InstitutionAuthorityGrantNativeIT.CHECKER,"select public.cfg_native_decide(?::uuid,?::uuid,?,?::bytea)",tenant,proposal,operation,raw.getBytes(StandardCharsets.UTF_8));
        }
    }
    static JsonNode execute(String platform,String tenant,JsonNode prepared) throws Exception {
        var target=prepared.get("target");return InstitutionAuthorityGrantNativeIT.invoke(platform,tenant,target.get("institutionId").textValue(),target.get("actionId").textValue(),target.get("grantId").textValue(),"GRANT","GRANT_CONTROL",prepared.get("targetCanonical").textValue(),null,"select public.cfg_native_control_grant_v2(?::uuid,?::bytea)",tenant,target.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Test void governedSuspendResumeAndTerminalRevokeHaveDurableExactReceipts() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_control_grant_v2(uuid,bytea)");var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var grant=InstitutionAuthorityGrantNativeIT.prepare(platform,tenant,null,0);InstitutionAuthorityGrantNativeIT.approve(platform,tenant,grant);InstitutionAuthorityGrantNativeIT.issue(platform,tenant,grant);
        var suspended=prepare(platform,tenant,grant.get("target"),0,"ACTIVE","SUSPENDED");approve(platform,tenant,suspended);var receipt=execute(platform,tenant,suspended);assertEquals("SUSPENDED",receipt.get("receipt").get("result").get("state").textValue());assertEquals(receipt.get("receipt"),execute(platform,tenant,suspended).get("receipt"));assertEquals("GRANT_SUSPENDED",InstitutionAuthorityGrantNativeIT.observe(platform,tenant,grant.get("target")).get("denialReason").textValue());
        var resumed=prepare(platform,tenant,grant.get("target"),1,"SUSPENDED","ACTIVE");approve(platform,tenant,resumed);execute(platform,tenant,resumed);assertEquals("CURRENT_AUTHORIZED",InstitutionAuthorityGrantNativeIT.observe(platform,tenant,grant.get("target")).get("state").textValue());
        var revoked=prepare(platform,tenant,grant.get("target"),2,"ACTIVE","REVOKED");approve(platform,tenant,revoked);execute(platform,tenant,revoked);assertEquals("GRANT_REVOKED",InstitutionAuthorityGrantNativeIT.observe(platform,tenant,grant.get("target")).get("denialReason").textValue());assertEquals("55000",assertThrows(SQLException.class,()->prepare(platform,tenant,grant.get("target"),3,"SUSPENDED","ACTIVE")).getSQLState());
    }
    @Test void approvedResumeAndFreshResumeCannotReviveOldGrantAfterTenantRecovery() throws Exception {
        TenantEligibilityNativeIT.requireProtocol("public.cfg_native_control_grant_v2(uuid,bytea)");var platform=TenantEligibilityNativeIT.tenant();var tenant=TenantEligibilityNativeIT.tenant();var grant=InstitutionAuthorityGrantNativeIT.prepare(platform,tenant,null,0);InstitutionAuthorityGrantNativeIT.approve(platform,tenant,grant);InstitutionAuthorityGrantNativeIT.issue(platform,tenant,grant);
        var suspended=prepare(platform,tenant,grant.get("target"),0,"ACTIVE","SUSPENDED");approve(platform,tenant,suspended);execute(platform,tenant,suspended);var stale=prepare(platform,tenant,grant.get("target"),1,"SUSPENDED","ACTIVE");approve(platform,tenant,stale);
        TenantEligibilityNativeIT.suspend(tenant,TenantEligibilityNativeIT.suspendJson(UUID.randomUUID().toString()));var recovery=TenantEligibilityNativeIT.recovery(tenant);TenantEligibilityNativeIT.decide(tenant,recovery,"submit",0,InstitutionAuthorityGrantNativeIT.MAKER);TenantEligibilityNativeIT.decide(tenant,recovery,"approve",1,InstitutionAuthorityGrantNativeIT.CHECKER);TenantEligibilityNativeIT.executeRecovery(tenant,recovery);
        assertEquals("55000",assertThrows(SQLException.class,()->execute(platform,tenant,stale)).getSQLState());assertEquals("55000",assertThrows(SQLException.class,()->prepare(platform,tenant,grant.get("target"),1,"SUSPENDED","ACTIVE")).getSQLState());
    }
}
