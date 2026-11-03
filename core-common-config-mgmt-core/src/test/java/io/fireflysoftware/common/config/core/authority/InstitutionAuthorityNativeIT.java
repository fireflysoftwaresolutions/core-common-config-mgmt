package io.fireflysoftware.common.config.core.authority;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit native lease only; ordinary core unit execution does not select *IT. */
class InstitutionAuthorityNativeIT {
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll static void requireExplicitOwnedNativeLease() throws Exception {
        if(!Boolean.getBoolean("cfg.native.lease")) throw new IllegalStateException("Explicit configuration native lease is required");
        jdbcUrl=required("CFG_NATIVE_JDBC_URL");username=required("CFG_NATIVE_MIGRATION_USER");password=required("CFG_NATIVE_MIGRATION_PASSWORD");
        if(!jdbcUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/cfg_authority_qualification_[a-z0-9_]+(?:\\?.*)?")) throw new IllegalStateException("Owned loopback qualification database is required");
        try(var connection=owner();var statement=connection.createStatement()) {
            var result=statement.executeQuery("select current_database()");result.next();
            if(!result.getString(1).startsWith("cfg_authority_qualification_")) throw new IllegalStateException("Qualification database mismatch");
        }
    }
    static Connection owner() throws SQLException {return DriverManager.getConnection(jdbcUrl,username,password);}
    static String required(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" is required");return value;}

    @Test void nativeGrantV2CanonicalMatchesIndependentLiteralVector() throws Exception {
        com.fasterxml.jackson.databind.JsonNode vector;
        try(var in=getClass().getResourceAsStream("/authority/institution-eligibility-v2-golden.json")){vector=new JsonMapper().readTree(in);}
        try(var c=owner();var q=c.prepareStatement("select public.cfg_native_grant_v2_intent(?::uuid, convert_from(?,'UTF8')::json)")) {
            q.setString(1,vector.get("tenantId").textValue());q.setBytes(2,vector.get("grantV2Original").textValue().getBytes(StandardCharsets.UTF_8));
            try(var rows=q.executeQuery()){assertTrue(rows.next());assertEquals(vector.get("grantV2Canonical").textValue(),rows.getString(1));assertFalse(rows.next());}
        }
    }
    @Test void nativeGrantV2RejectsUnboundV1DuplicateAndNoncanonicalEpoch() throws Exception {
        com.fasterxml.jackson.databind.JsonNode vector;
        try(var in=getClass().getResourceAsStream("/authority/institution-eligibility-v2-golden.json")){vector=new JsonMapper().readTree(in);}
        var json=vector.get("grantV2Original").textValue();var tree=new JsonMapper().readTree(json);
        for(var body:List.of(tree.get("grant").toString(),json.replace("\"tenantGeneration\":2","\"tenantGeneration\":1e0"),json.replace("\"tenantGeneration\":2","\"tenantGeneration\":2,\"tenantGeneration\":2"),json.replace("\"statusGeneration\":3","\"statusGeneration\":-1"))) {
            try(var c=owner();var q=c.prepareStatement("select public.cfg_native_grant_v2_intent(?::uuid, convert_from(?,'UTF8')::json)")) {
                q.setString(1,vector.get("tenantId").textValue());q.setBytes(2,body.getBytes(StandardCharsets.UTF_8));assertEquals("22023",assertThrows(SQLException.class,q::executeQuery).getSQLState());
            }
        }
    }
    @Test void nativeLifecycleIntentHasIndependentTenantAndStatusDomains() throws Exception {
        com.fasterxml.jackson.databind.JsonNode vector;
        try(var in=getClass().getResourceAsStream("/authority/institution-eligibility-v2-golden.json")){vector=new JsonMapper().readTree(in);}
        try(var c=owner();var q=c.prepareStatement("select public.cfg_native_suspend_intent(?::uuid, convert_from(?,'UTF8')::json, false), public.cfg_native_suspend_intent(?::uuid, convert_from(?,'UTF8')::json, true)")) {
            var raw=vector.get("tenantSuspendOriginal").textValue().getBytes(StandardCharsets.UTF_8);
            q.setString(1,vector.get("tenantId").textValue());q.setBytes(2,raw);q.setString(3,vector.get("statusId").textValue());q.setBytes(4,raw);
            try(var rows=q.executeQuery()){assertTrue(rows.next());assertEquals(vector.get("tenantSuspendCanonical").textValue(),rows.getString(1));assertNotEquals(rows.getString(1),rows.getString(2));}
        }
    }
    @Test void sqlUuidOrderMatchesJavaCanonicalOrderAcrossSignedBoundary() throws Exception {
        var ids=List.of("ffffffff-ffff-ffff-ffff-ffffffffffff","80000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000001","7fffffff-ffff-ffff-ffff-ffffffffffff");
        try(var c=owner();var q=c.prepareStatement("select value::text from unnest(?::uuid[]) value order by value")) {
            q.setArray(1,c.createArrayOf("uuid",ids.toArray()));var actual=new ArrayList<String>();try(var rows=q.executeQuery()){while(rows.next())actual.add(rows.getString(1));}
            assertEquals(InstitutionAuthorityLockOrder.statusIds(ids),actual);
        }
    }
}
