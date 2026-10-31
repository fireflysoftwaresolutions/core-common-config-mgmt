package io.fireflysoftware.common.config.web.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import io.fireflysoftware.common.config.core.services.authority.impl.*;
import io.fireflysoftware.common.config.models.config.NativeAuthorityRepositoryConfiguration;
import io.fireflysoftware.common.config.web.config.NativeAuthoritySecurityConfiguration;
import io.fireflysoftware.common.config.web.controllers.authority.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Separate explicit runtime gate; no mocked principal or policy port enters the production services. */
class NativeAuthorityVerifiedHttpIT {
    static final JsonMapper JSON=new JsonMapper();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String MAKER="00000000-0000-0000-0000-000000000101",CHECKER="00000000-0000-0000-0000-000000000102";
    static HttpServer jwks;static ConfigurableApplicationContext app;static KeyPair key;static Process packaged;
    static String issuer,origin,opa,jdbc,platform,tenant,statusId;
    static boolean metadataRead=true,metadataWrite=true;
    static final Map<String,Map<String,Boolean>> permissions=new HashMap<>();
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({NativeAuthoritySecurityConfiguration.class,NativeAuthorityRepositoryConfiguration.class,TenantEligibilityServiceImpl.class,InstitutionAuthorityServiceImpl.class,NativeAuthorityActionServiceImpl.class,PlatformTenantEligibilityController.class,InstitutionAuthorityController.class,PlatformInstitutionAuthorityController.class,NativeAuthorityActionController.class,PlatformNativeAuthorityActionController.class})
    static class Application {}
    static String required(String name){var value=System.getenv(name);if(value==null || value.isBlank())throw new IllegalStateException("Owned authority HTTP environment missing: "+name);return value;}
    static Connection owner() throws Exception{return DriverManager.getConnection(jdbc,required("CFG_NATIVE_MIGRATION_USER"),required("CFG_NATIVE_MIGRATION_PASSWORD"));}
    static String tenant() throws Exception {
        var id=UUID.randomUUID().toString();try(var c=owner();var query=c.prepareStatement("insert into public.tenants(id,code,name,tenant_status_id,active) select ?::uuid,?,'Fictional signed HTTP qualification',id,true from public.tenant_statuses where code='ACTIVE'")){query.setString(1,id);query.setString(2,"http-"+id);assertEquals(1,query.executeUpdate());}return id;
    }
    @BeforeAll static void start() throws Exception {
        if(!Boolean.getBoolean("cfg.authority.http.lease"))throw new IllegalStateException("Explicit owned authority HTTP/native lease required");
        var frameworkSource=org.fireflyframework.security.rs.ResourceServerProperties.class.getProtectionDomain().getCodeSource().getLocation().toURI();var expectedSource=System.getProperty("cfg.authority.framework.expected-source");
        if(expectedSource!=null)assertEquals(java.nio.file.Path.of(expectedSource).toRealPath(),java.nio.file.Path.of(frameworkSource).toRealPath(),"Actual candidate dependency origin must match the reviewed reactor source");
        System.out.println("Verified HTTP fixture framework dependency origin: "+frameworkSource);
        jdbc=required("CFG_NATIVE_JDBC_URL");var database=URI.create(jdbc.substring("jdbc:".length()));if(!database.getHost().equals("127.0.0.1") || database.getPort()<1 || !database.getPath().startsWith("/cfg_authority_qualification_"))throw new IllegalStateException("Owned loopback native database required");
        opa=required("CFG_AUTHORITY_OPA_URL");var policy=URI.create(opa);if(!policy.getScheme().equals("http") || !policy.getHost().equals("127.0.0.1") || policy.getPort()<1 || !required("CFG_AUTHORITY_OPA_OWNED").equals("organization-authority-next"))throw new IllegalStateException("Owned loopback OPA required");
        var packagedJar=System.getProperty("cfg.authority.packaged.jar");
        if(packagedJar!=null)org.flywaydb.core.Flyway.configure().dataSource(jdbc,required("CFG_NATIVE_MIGRATION_USER"),required("CFG_NATIVE_MIGRATION_PASSWORD")).locations("classpath:db/migration").load().migrate();
        platform=tenant();tenant=tenant();try(var c=owner();var quote=c.prepareStatement("select quote_literal(?)")){quote.setString(1,required("CFG_NATIVE_RUNTIME_PASSWORD"));var rows=quote.executeQuery();assertTrue(rows.next());try(var statement=c.createStatement()){statement.execute("alter role cfg_native_runtime password "+rows.getString(1));}}
        try(var c=owner();var query=c.prepareStatement("select id from public.tenant_statuses where code='ACTIVE'")){var rows=query.executeQuery();assertTrue(rows.next());statusId=rows.getString(1);}
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=generator.generateKeyPair();var publicKey=new RSAKey.Builder((RSAPublicKey)key.getPublic()).keyID("owned-http-key").build();
        jwks=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);jwks.createContext("/jwks",exchange->{var bytes=new JWKSet(publicKey).toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}});jwks.start();var keyOrigin="http://127.0.0.1:"+jwks.getAddress().getPort();issuer=keyOrigin+"/qualification-issuer";
        try(var resource=NativeAuthorityVerifiedHttpIT.class.getResourceAsStream("/authority/native-authority-qualification.rego")){assertNotNull(resource);var response=HTTP.send(HttpRequest.newBuilder(URI.create(opa+"/v1/policies/firefly-authority-qualification")).PUT(HttpRequest.BodyPublishers.ofByteArray(resource.readAllBytes())).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());}
        for(var subject:List.of("maker","maker-alias","checker","machine")){var actions=new HashMap<String,Boolean>();for(var action:List.of("proposal.prepare","proposal.submit","proposal.approve","history","grant.issue","grant.observe","grant.control","action.read","action.reconcile","tenant.suspend"))actions.put("configuration.institution.authority."+action,true);permissions.put(subject,actions);}for(var subject:List.of("metadata-reader","metadata-writer","metadata-admin"))permissions.put(subject,new HashMap<>());policyData();
        var props=new HashMap<String,Object>();props.put("spring.config.name","native-authority-qualification");props.put("server.address","127.0.0.1");props.put("server.port",0);props.put("spring.application.name","firefly-authority-http-qualification");props.put("firefly.web.idempotency.namespace","firefly-authority-http-qualification");props.put("spring.flyway.enabled",false);props.put("spring.r2dbc.url",jdbc.replace("jdbc:postgresql:","r2dbc:postgresql:"));props.put("spring.r2dbc.username","cfg_native_runtime");props.put("spring.r2dbc.password",required("CFG_NATIVE_RUNTIME_PASSWORD"));props.put("firefly.security.resource-server.enabled",true);props.put("firefly.security.resource-server.strict-policy",true);props.put("firefly.security.resource-server.issuer",issuer);props.put("firefly.security.resource-server.jwk-set-uri",keyOrigin+"/jwks");props.put("firefly.security.resource-server.audiences","authority-qualification");props.put("firefly.security.resource-server.role-claim-paths[0]","roles");props.put("firefly.security.resource-server.tenant-claim-paths[0]","tenant_id");props.put("firefly.security.resource-server.tenant-required",true);props.put("firefly.configuration.authority.platform-tenant-id",platform);props.put("firefly.configuration.authority.policy-url",opa);props.put("firefly.configuration.authority.policy-path","firefly/configuration/allow");props.put("spring.main.banner-mode","off");
        if(packagedJar!=null){startPackaged(packagedJar);return;}
        var application=new SpringApplication(Application.class);application.setWebApplicationType(WebApplicationType.REACTIVE);application.setDefaultProperties(props);app=application.run("--server.address=127.0.0.1","--server.port=0","--spring.config.name=native-authority-qualification");origin="http://127.0.0.1:"+app.getEnvironment().getRequiredProperty("local.server.port");
    }
    @AfterAll static void stop() throws Exception {
        if(app!=null)app.close();if(packaged!=null){packaged.destroy();if(!packaged.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){packaged.destroyForcibly();assertTrue(packaged.waitFor(5,java.util.concurrent.TimeUnit.SECONDS));}}if(jwks!=null)jwks.stop(0);
    }
    static void startPackaged(String artifact) throws Exception {
        var jar=java.nio.file.Path.of(artifact).toRealPath();assertTrue(jar.getFileName().toString().endsWith(".jar"));var log=java.nio.file.Path.of(required("CFG_AUTHORITY_PACKAGED_LOG"));var database=URI.create(jdbc.substring("jdbc:".length()));
        var command=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString(),"--server.address=127.0.0.1","--server.port=0");var env=command.environment();env.put("DB_HOST",database.getHost());env.put("DB_PORT",Integer.toString(database.getPort()));env.put("DB_NAME",database.getPath().substring(1));env.put("DB_SSL_MODE","disable");env.put("DB_USERNAME","cfg_native_runtime");env.put("DB_PASSWORD",required("CFG_NATIVE_RUNTIME_PASSWORD"));env.put("DB_MIGRATION_USERNAME",required("CFG_NATIVE_MIGRATION_USER"));env.put("DB_MIGRATION_PASSWORD",required("CFG_NATIVE_MIGRATION_PASSWORD"));env.put("FIREFLY_AUTHORITY_ISSUER",issuer);env.put("FIREFLY_AUTHORITY_AUDIENCE","authority-qualification");env.put("FIREFLY_AUTHORITY_JWKS_URI",issuer.substring(0,issuer.indexOf("/qualification-issuer"))+"/jwks");env.put("FIREFLY_PLATFORM_TENANT_ID",platform);env.put("FIREFLY_CONFIGURATION_POLICY_URL",opa);
        packaged=command.redirectErrorStream(true).redirectOutput(log.toFile()).start();var pattern=java.util.regex.Pattern.compile("Netty started on port ([0-9]+)");long until=System.nanoTime()+Duration.ofSeconds(60).toNanos();
        while(System.nanoTime()<until){if(java.nio.file.Files.exists(log)){var matcher=pattern.matcher(java.nio.file.Files.readString(log));if(matcher.find()){int port=Integer.parseInt(matcher.group(1));assertNotEquals(18080,port);origin="http://127.0.0.1:"+port;return;}}if(!packaged.isAlive())throw new AssertionError("Original packaged application exited; inspect owned packaged log");Thread.sleep(100);}throw new AssertionError("Original packaged application did not become ready within60seconds");
    }
    static void policyData() throws Exception {
        var scopes=new HashMap<String,Object>();for(var subject:permissions.keySet())scopes.put(subject,Map.of(tenant,true));var principalTenants=new HashMap<String,Object>();for(var subject:permissions.keySet())principalTenants.put(subject,platform);var body=JSON.writeValueAsString(Map.of("enabled",true,"permissions",permissions,"scopes",scopes,"principal_tenants",principalTenants,"metadata_read",Map.of("metadata-reader",metadataRead,"metadata-admin",metadataRead),"metadata_write",Map.of("metadata-writer",metadataWrite,"metadata-admin",metadataWrite),"metadata_admin",Map.of("metadata-admin",true),"metadata_statuses",Map.of("metadata-reader",Map.of(statusId,true),"metadata-admin",Map.of(statusId,true))));var response=HTTP.send(HttpRequest.newBuilder(URI.create(opa+"/v1/data/qualification")).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(204,response.statusCode(),response.body());
    }
    static String token(String subject,String employee,String audience,long expiry,KeyPair signer,boolean fakeStaff) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience).issueTime(Date.from(Instant.now())).notBeforeTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(Instant.now().plusSeconds(expiry))).claim("tenant_id",platform).claim("principal_kind",employee==null?"MACHINE":"STAFF").claim("roles",List.of("AUTHORITY_OPERATOR"));if(employee!=null)claims.claim("staff_actor_id",employee);else claims.claim("client_id","signed-http-client");if(fakeStaff)claims.claim("staff_actor_id",MAKER);var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("owned-http-key").build(),claims.build());jwt.sign(new RSASSASigner((RSAPrivateKey)signer.getPrivate()));return jwt.serialize();
    }
    static String metadataToken(String subject,List<String> roles,String actorTenant) throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience("authority-qualification").expirationTime(Date.from(Instant.now().plusSeconds(120))).claim("tenant_id",actorTenant).claim("principal_kind","MACHINE").claim("client_id","metadata-client").claim("roles",roles).build();var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("owned-http-key").build(),claims);token.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));return token.serialize();
    }
    static String token(String subject,String employee) throws Exception{return token(subject,employee,"authority-qualification",300,key,false);}
    static HttpResponse<String> call(String method,String path,String token,String body) throws Exception {var request=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(15));if(token!=null)request.header("Authorization","Bearer "+token);if(body==null)request.method(method,HttpRequest.BodyPublishers.noBody());else request.header("Content-Type","application/json").header("X-Idempotency-Key","authority-http-"+UUID.nameUUIDFromBytes((path+"|"+body).getBytes(StandardCharsets.UTF_8))).method(method,HttpRequest.BodyPublishers.ofString(body));return HTTP.send(request.build(),HttpResponse.BodyHandlers.ofString());}
    static JsonNode accepted(HttpResponse<String> response,int status) throws Exception {assertEquals(status,response.statusCode(),response.body());assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));return JSON.readTree(response.body());}
    @Test void signedIndependentStaffAndActualMachineCompleteNativeGrantWithCurrentPolicyReplay() throws Exception {
        var base="/api/v1/platform/tenants/"+tenant+"/institution-authorities";long now=System.currentTimeMillis();var prepare=JSON.createObjectNode().put("actionId",UUID.randomUUID().toString()).put("targetActionId",UUID.randomUUID().toString()).put("notAfterEpochMillis",now+120000);var draft=prepare.putObject("draft");draft.putNull("existingInstitutionId").putNull("existingIssuerAuthorityId").put("expectedGrantHeadVersion",0).put("effectiveFromEpochMillis",now-1000).put("effectiveToEpochMillis",now+86400000).put("reason","Independent fictional signed HTTP admission");draft.putArray("uses").add("ACCOUNTING_BOOK");draft.putArray("evidenceReferences");
        var prepared=accepted(call("POST",base+"/grant-proposals",token("maker",MAKER),prepare.toString()),201).get("receipt").get("result");var target=prepared.get("target");var grant=target.get("grant");var institution=grant.get("institutionId").textValue();var proposal=prepared.get("proposalId").textValue();var decisionBase=base+"/"+institution+"/proposals/"+proposal;
        var decision=JSON.createObjectNode().put("actionId",UUID.randomUUID().toString()).put("expectedProposalControlVersion",0).put("proposalRevision",1).put("targetIntentDigest",prepared.get("targetDigest").textValue()).put("reason","Independent signed review");accepted(call("POST",decisionBase+"/submit",token("maker",MAKER),decision.toString()),201);decision.put("actionId",UUID.randomUUID().toString()).put("expectedProposalControlVersion",1);assertEquals(403,call("POST",decisionBase+"/approve",token("maker-alias",MAKER),decision.toString()).statusCode());decision.put("actionId",UUID.randomUUID().toString());accepted(call("POST",decisionBase+"/approve",token("checker",CHECKER),decision.toString()),201);
        var machine=token("machine",null);var issue=base+"/"+institution+"/grant-issues";var issued=accepted(call("POST",issue,machine,target.toString()),201);assertEquals("MACHINE",issued.get("receipt").get("actor").get("kind").textValue());assertTrue(issued.get("receipt").get("actor").get("employeeId").isNull());assertEquals(platform,issued.get("receipt").get("actor").get("tenantId").textValue());assertEquals(issued.get("receipt"),accepted(call("POST",issue,machine,target.toString()),200).get("receipt"));
        permissions.get("machine").put("configuration.institution.authority.grant.issue",false);policyData();assertEquals(403,call("POST",issue,machine,target.toString()).statusCode());permissions.get("machine").put("configuration.institution.authority.grant.issue",true);policyData();
        var current=base+"/"+institution+"/issuers/"+grant.get("issuerAuthorityId").textValue()+"/current-grant?use=ACCOUNTING_BOOK";assertEquals("CURRENT_AUTHORIZED",accepted(call("GET",current,machine,null),200).get("state").textValue());var action=base+"/actions/"+grant.get("actionId").textValue();assertEquals("RECEIPT_FOUND",accepted(call("GET",action,machine,null),200).get("state").textValue());
        var suspend=JSON.createObjectNode().put("actionId",UUID.randomUUID().toString()).put("notAfterEpochMillis",System.currentTimeMillis()+30000).put("reason","Current signed emergency suspension");suspend.putArray("evidenceReferences");var disabled=accepted(call("POST","/api/v1/platform/tenants/"+tenant+"/eligibility/suspensions",machine,suspend.toString()),201);assertFalse(disabled.get("receipt").get("result").get("tenantActive").booleanValue());
        var noCurrent=accepted(call("GET",current,machine,null),200);assertEquals("NO_CURRENT_AUTHORITY",noCurrent.get("state").textValue());assertEquals("TENANT_DISABLED",noCurrent.get("denialReason").textValue());var historical=accepted(call("GET",base+"/"+institution+"/issuers/"+grant.get("issuerAuthorityId").textValue()+"/grants/"+grant.get("grantId").textValue()+"/versions/1",machine,null),200);assertTrue(historical.get("historicalOnly").booleanValue());assertEquals(issued.get("receipt").get("result"),historical.get("definition"));assertEquals(issued.get("receipt"),accepted(call("POST",issue,machine,target.toString()),200).get("receipt"));

    }
    @Test void invalidSignatureAudienceExpiredTokenAndMachineStaffClaimFailClosed() throws Exception {
        var path="/api/v1/platform/tenants/"+tenant+"/institution-authorities/actions/"+UUID.randomUUID();assertEquals(401,call("GET",path,null,null).statusCode());var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);assertEquals(401,call("GET",path,token("machine",null,"authority-qualification",300,generator.generateKeyPair(),false),null).statusCode());assertEquals(401,call("GET",path,token("machine",null,"wrong-audience",300,key,false),null).statusCode());assertEquals(401,call("GET",path,token("machine",null,"authority-qualification",-120,key,false),null).statusCode());assertEquals(403,call("GET",path,token("machine",null,"authority-qualification",300,key,true),null).statusCode());
    }
}
