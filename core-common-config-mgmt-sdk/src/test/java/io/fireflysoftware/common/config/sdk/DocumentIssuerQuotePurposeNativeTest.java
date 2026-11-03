/* Copyright 2026 Firefly Software Foundation. Licensed under the Apache License, Version 2.0. */
package io.fireflysoftware.common.config.sdk;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Actual optional installation and pure native validation; no Main, caller, publication or issuance acceptance. */
class DocumentIssuerQuotePurposeNativeTest {
    private static PostgreSQLContainer postgres;
    private static String originalOid,originalAcl;
    private static final String INSTALLER="document_quote_installer";
    private static final String FIXTURE_PASSWORD="synthetic-test-only";
    @BeforeAll static void install()throws Exception{
        postgres=new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("quote_issuer_native").withUsername("fixture_admin").withPassword(FIXTURE_PASSWORD)
            .withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIp("127.0.0.1"),new com.github.dockerjava.api.model.ExposedPort(5432))));
        try{
            postgres.start();Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").load().migrate();
            try(var c=admin();var s=c.createStatement()){
                s.execute("REVOKE CREATE,TEMPORARY ON DATABASE quote_issuer_native FROM PUBLIC;REVOKE CREATE ON SCHEMA public FROM PUBLIC;CREATE ROLE "+INSTALLER+" LOGIN NOINHERIT NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '"+FIXTURE_PASSWORD+"';SET firefly.cfg_doc.installer='"+INSTALLER+"'");
                s.execute(Files.readString(Path.of("../ops/document-issuer/bootstrap.sql")));s.execute(Files.readString(Path.of("../ops/document-issuer/deployment-history.sql")));
            }
            migration("6").migrate();originalOid=scalar("SELECT 'public.cfg_document_issuer_use(json)'::regprocedure::oid::text");originalAcl=scalar("SELECT proacl::text FROM pg_proc WHERE oid='public.cfg_document_issuer_use(json)'::regprocedure");migration(null).migrate();
        }catch(Exception|Error error){if(postgres!=null)postgres.stop();throw error;}
    }
    @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
    private static Connection admin()throws Exception{return DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());}
    private static Flyway migration(String target){var c=Flyway.configure().dataSource(postgres.getJdbcUrl(),INSTALLER,FIXTURE_PASSWORD).locations("classpath:db/document-issuer-migration").defaultSchema("cfg_document_issuer_migrations").schemas("cfg_document_issuer_migrations").createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).validateOnMigrate(true);if(target!=null)c.target(target);return c.load();}
    private static String scalar(String sql)throws Exception{try(var c=admin();var s=c.createStatement();var rows=s.executeQuery(sql)){assertTrue(rows.next());return rows.getString(1);}}
    private static String use(String purpose)throws Exception{try(var c=admin();var s=c.createStatement()){s.execute("SET ROLE cfg_document_issuer_guard");try(var q=c.prepareStatement("SELECT public.cfg_document_issuer_use(?::json)")){q.setString(1,"{\"purpose\":\""+purpose+"\",\"documentType\":\"CONTRACT\",\"locale\":\"en-US\"}");try(var rows=q.executeQuery()){assertTrue(rows.next());return rows.getString(1);}}}}
    @Test void bothExactNativePurposesKeepDistinctCanonicalFrames()throws Exception{assertEquals("R3:S26:DEPOSIT_ACCOUNT_ENROLLMENTS8:CONTRACTS5:en-US",use("DEPOSIT_ACCOUNT_ENROLLMENT"));assertEquals("R3:S23:INSURANCE_TERM_PC_QUOTES8:CONTRACTS5:en-US",use("INSURANCE_TERM_PC_QUOTE"));}
    @Test void cardEnrollmentKeepsItsDistinctNativeCanonicalUse()throws Exception{
        assertEquals("R3:S15:CARD_ENROLLMENTS8:CONTRACTS5:en-US",use("CARD_ENROLLMENT"));
        assertNotEquals(use("DEPOSIT_ACCOUNT_ENROLLMENT"),use("CARD_ENROLLMENT"));
        assertNotEquals(use("INSURANCE_TERM_PC_QUOTE"),use("CARD_ENROLLMENT"));
    }
    @Test void cardEnrollmentDoesNotAuthorizeActivationShippingOrSignature()throws Exception{
        for(var purpose:List.of("CARD_ACTIVATION","CARD_SHIPPING","CARD_CONTRACT_SIGNATURE")){
            var error=assertThrows(SQLException.class,()->use(purpose));assertEquals("22023",error.getSQLState());
        }
    }
    @Test void unknownPurposesCannotAuthorizePolicyIssuanceOrSignature()throws Exception{for(var purpose:List.of("INSURANCE_POLICY_ISSUANCE","INSURANCE_CONTRACT_SIGNATURE","TERM_PC_INSURANCE_QUOTE","")){var error=assertThrows(SQLException.class,()->use(purpose));assertEquals("22023",error.getSQLState());}}
    @Test void forwardMigrationPreservesExistingFunctionOwnerOidAndAcl()throws Exception{assertEquals(originalOid,scalar("SELECT 'public.cfg_document_issuer_use(json)'::regprocedure::oid::text"));assertEquals(originalAcl,scalar("SELECT proacl::text FROM pg_proc WHERE oid='public.cfg_document_issuer_use(json)'::regprocedure"));assertEquals("cfg_document_issuer_guard",scalar("SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid='public.cfg_document_issuer_use(json)'::regprocedure"));assertEquals("false",scalar("SELECT has_schema_privilege('cfg_document_issuer_guard','public','CREATE')::text"));}
    @Test void runtimeGroupsDoNotAcquirePureCodecOrInstallerAuthority()throws Exception{for(var role:List.of("cfg_document_issuer_commands","cfg_document_issuer_read")){assertEquals("false",scalar("SELECT has_function_privilege('"+role+"','public.cfg_document_issuer_use(json)','EXECUTE')::text"));assertEquals("false",scalar("SELECT pg_has_role('"+role+"','cfg_document_issuer_guard','MEMBER')::text"));assertEquals("false",scalar("SELECT has_schema_privilege('"+role+"','cfg_document_issuer_migrations','USAGE,CREATE')::text"));}}
    @Test void dedicatedHistoryValidatesFromFreshInstallerWithoutReplayingMigrations()throws Exception{migration(null).validate();assertEquals(0,migration(null).migrate().migrationsExecuted);assertEquals("2",scalar("SELECT count(*)::text FROM cfg_document_issuer_migrations.flyway_schema_history WHERE success AND version IN ('6','7')"));assertEquals("5",scalar("SELECT count(*)::text FROM public.flyway_schema_history WHERE success AND version IS NOT NULL"));}
}
