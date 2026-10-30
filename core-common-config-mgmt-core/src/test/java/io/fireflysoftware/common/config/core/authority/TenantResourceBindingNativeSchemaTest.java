package io.fireflysoftware.common.config.core.authority;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class TenantResourceBindingNativeSchemaTest {
 private static final String OPTIONAL_V7="core-common-config-mgmt-models/src/main/resources/db/tenant-resource-binding-prerequisite/V7__Tenant_resource_binding_persistence_prerequisite.sql";
 private static boolean owningRoot(Path root){return root!=null&&Files.isRegularFile(root.resolve("pom.xml"))&&Files.isRegularFile(root.resolve("core-common-config-mgmt-core/pom.xml"))&&Files.isRegularFile(root.resolve(OPTIONAL_V7));}
 private static Path root(String configured,Path workingDirectory){
  if(configured!=null){if(configured.isBlank())throw new IllegalStateException("Exact configured binding source root required");var root=Path.of(configured).toAbsolutePath().normalize();if(owningRoot(root))return root;throw new IllegalStateException("Exact configured binding source root required");}
  var root=workingDirectory.toAbsolutePath().normalize();
  if(owningRoot(root))return root;
  if(root.getFileName()!=null&&root.getFileName().toString().equals("core-common-config-mgmt-core")&&Files.isRegularFile(root.resolve("pom.xml"))&&owningRoot(root.getParent()))return root.getParent();
  throw new IllegalStateException("Exact repository or owning Core module source root required");
 }
 private static Path root(){return root(System.getProperty("configuration.binding.source"),Path.of(System.getProperty("user.dir")));}
 private String sql()throws Exception{return Files.readString(root().resolve(OPTIONAL_V7));}
 @Test void optionalInstallerHasARealAcyclicAtomicOrder()throws Exception{var sql=sql();assertTrue(sql.contains("BEGIN;"));assertTrue(sql.lastIndexOf("COMMIT;")>sql.indexOf("$attest$"));assertTrue(sql.indexOf("CREATE TABLE public.cfg_trb_scope")<sql.indexOf("CREATE FUNCTION public.cfg_trb_current"));assertTrue(sql.indexOf("CREATE FUNCTION public.cfg_trb_lookup_current")<sql.indexOf("CREATE VIEW public.cfg_tenant_resource_binding_current"));assertFalse(sql.contains("Separately qualified binding native issuer and protected same-session scope required"));}
 @Test void nativeAuthorityBindsPhysicalSessionAndDefersFullCompletion()throws Exception{var sql=sql();for(var contract:new String[]{"pg_backend_pid()","pg_current_xact_id()","session_principal IS DISTINCT FROM session_user","transaction_isolation","read committed","statement_timeout","lock_timeout","DEFERRABLE INITIALLY DEFERRED","cfg_trb_check_effect(scope,true)","cfg_trb_capture_anchor(scope,action)","original_target","cfg_native_finish_action","cfg_trb_anchor_immutable"})assertTrue(sql.contains(contract),contract);assertFalse(sql.contains("CREATE ROLE"));assertFalse(sql.contains("GRANT cfg_native_guard"));assertFalse(sql.contains("CREATE OR REPLACE FUNCTION public.cfg_native_"));}
 @Test void originalMakerAndCurrentRevocationActorRemainDistinct()throws Exception{var sql=sql();assertTrue(sql.contains("p_scope.operation='TENANT_RESOURCE_BINDING_ESTABLISH' AND(actual.maker_issuer"));assertTrue(sql.contains("Original native binding creation anchor required; no origin is synthesized"));assertTrue(sql.contains("Original native receipt window expired; no replacement is issued"));assertTrue(sql.contains("Historical head recovery is not a current duplicate admission"));assertTrue(sql.contains("status.code='ACTIVE'"));assertFalse(sql.contains("INSERT INTO public.cfg_native_tenant_eligibility"));assertFalse(sql.contains("INSERT INTO public.cfg_native_status_eligibility"));}
 @Test void mutationReceiptsKeepRealProposalLinksAndPrivateAnchorPrivileges()throws Exception{var sql=sql();assertTrue(sql.contains("scope.proposal_id,1,"));assertTrue(sql.contains("'TENANT_RESOURCE_BINDING_PREPARE',proposal,proposal,1,"));assertFalse(sql.contains("'APPLIED',NULL"));assertTrue(sql.contains("REVOKE ALL ON public.cfg_trb_scope,public.cfg_trb_definition_anchor FROM PUBLIC,cfg_native_runtime"));assertTrue(sql.contains("FOREIGN KEY(resource_tenant_id,creation_action_id) REFERENCES public.cfg_native_action"));assertTrue(sql.contains("FOREIGN KEY(resource_tenant_id,creation_proposal_id) REFERENCES public.cfg_native_proposal"));assertFalse(sql.contains("GRANT EXECUTE ON FUNCTION public.cfg_native_s"));}

 @Test void completedScopeRejectsReentryWithoutRollingBackItsProtectedWitness()throws Exception{var sql=sql();assertTrue(sql.contains("PRIMARY KEY(backend_pid,xid)"));assertTrue(sql.contains("completed BOOLEAN NOT NULL DEFAULT false"));assertTrue(sql.contains("OR scope.completed"));var complete=sql.substring(sql.indexOf("CREATE FUNCTION public.cfg_trb_complete_scope"),sql.indexOf("CREATE CONSTRAINT TRIGGER cfg_trb_scope_complete"));assertTrue(complete.contains("SET completed=true"));assertFalse(complete.contains("DELETE FROM public.cfg_trb_scope"));}
 @Test void pooledReuseOnlyRemovesEarlierCompletedFullXidWitnesses()throws Exception{var sql=sql();var enter=sql.substring(sql.indexOf("CREATE FUNCTION public.cfg_trb_enter"),sql.indexOf("CREATE FUNCTION public.cfg_trb_actor"));assertTrue(enter.contains("s.xid<>pg_current_xact_id() AND s.completed"));assertTrue(enter.contains("s.backend_pid=pg_backend_pid() AND s.xid=pg_current_xact_id()"));assertTrue(enter.contains("NOT s.completed"));assertFalse(enter.contains("set_config('firefly.cfg_trb.completed"));}

 @Test void guardOwnershipHandoffsBracketOnlyTemporarySchemaCreate()throws Exception{var sql=sql();var grant="GRANT CREATE ON SCHEMA public TO cfg_native_guard;";var revoke="REVOKE CREATE ON SCHEMA public FROM cfg_native_guard;";var view=sql.indexOf("ALTER VIEW public.cfg_tenant_resource_binding_current OWNER TO cfg_native_guard");var functions=sql.indexOf("DO $acl$");assertTrue(sql.lastIndexOf(grant,view)>=0);assertTrue(sql.indexOf(revoke,view)>view);assertTrue(sql.lastIndexOf(grant,functions)>view);assertTrue(sql.indexOf(revoke,functions)>functions);assertEquals(2,sql.split(java.util.regex.Pattern.quote(grant),-1).length-1);assertEquals(2,sql.split(java.util.regex.Pattern.quote(revoke),-1).length-1);}
 @Test void installerRequiresGrantAuthorityAndAttestsNoGuardRuntimeCreate()throws Exception{var sql=sql();assertTrue(sql.contains("has_schema_privilege(current_user,'public','CREATE WITH GRANT OPTION')"));assertTrue(sql.contains("pg_has_role(current_user,'cfg_native_guard','SET')"));var attest=sql.substring(sql.indexOf("DO $attest$"));assertTrue(attest.contains("has_schema_privilege('cfg_native_guard','public','CREATE')"));assertTrue(attest.contains("has_schema_privilege('cfg_native_runtime','public','CREATE')"));assertFalse(sql.contains("GRANT CREATE ON SCHEMA public TO cfg_native_runtime"));}

 private String function(String name)throws Exception{var source=sql();var start=source.indexOf("CREATE FUNCTION public."+name+"(");assertTrue(start>=0);var end=source.indexOf("$fn$;",start);assertTrue(end>start);return source.substring(start,end);}
 private static String field(String value){return "S"+value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+":"+value;}
 @Test void canonicalUuidTokensAreNeverEncodedAsStringsAgain()throws Exception{assertFalse(java.util.regex.Pattern.compile("cfg_native_s\\s*\\(\\s*public\\.cfg_native_uuid\\s*\\(").matcher(sql()).find());}
 @Test void numericFieldsAreNotStringsOfLegacyIntegerTokens()throws Exception{assertFalse(java.util.regex.Pattern.compile("cfg_native_s\\s*\\(\\s*public\\.cfg_native_long\\s*\\(").matcher(sql()).find());}
 @Test void commandAndDecisionRawEnumsNeverUseCanonicalTokenReturnValues()throws Exception{for(var name:new String[]{"cfg_trb_command","cfg_trb_decision"}){var body=function(name);assertFalse(body.contains("operation:=public.cfg_native_string"));assertTrue(body.contains("operation:=p_value->>'operation'"));assertTrue(body.contains("PERFORM public.cfg_native_string"));}assertFalse(function("cfg_trb_decision").contains("digest:=public.cfg_native_string"));}
 @Test void preparationUsesValidatedRawIdentityAndDeadlineForOperationalValues()throws Exception{var body=function("cfg_trb_preparation");assertFalse(body.contains("action:=public.cfg_native_uuid"));assertFalse(body.contains("until_millis:=public.cfg_native_long"));assertTrue(body.contains("action:=p_value->>'actionId'"));assertTrue(body.contains("until_millis:=p_value->>'notAfterEpochMillis'"));assertTrue(body.contains("PERFORM public.cfg_native_uuid"));assertTrue(body.contains("action=target->'command'->>'actionId'"));}
 @Test void definitionPassesValidatedRawNamespaceToExistingDigestFunction()throws Exception{var body=function("cfg_trb_definition");assertFalse(body.contains("issuer:=public.cfg_native_string"));assertFalse(body.contains("platform:=public.cfg_native_string"));assertTrue(body.contains("issuer:=ns->>'issuer'"));assertTrue(body.contains("platform:=ns->>'platformTenant'"));assertTrue(body.contains("PERFORM public.cfg_native_string"));}
 private static io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Definition vector(){return new io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Definition(java.util.UUID.fromString("10000000-0000-0000-0000-000000000001"),new io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Namespace("https://issuer.example.test","opaque/α"),new io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.ResourceTenant(java.util.UUID.fromString("20000000-0000-0000-0000-000000000002")),1,new io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Eligibility(2,java.util.UUID.fromString("30000000-0000-0000-0000-000000000003"),4),1000);}
 private static String expectedDefinition(){return "FF_TENANT_RESOURCE_BINDING_DEFINITION_V1S36:10000000-0000-0000-0000-000000000001S27:https://issuer.example.testS9:opaque/αS36:20000000-0000-0000-0000-000000000002S1:1S1:2S36:30000000-0000-0000-0000-000000000003S1:4S4:1000";}
 @Test void javaDefinitionProtocolKeepsUtf8FieldsAndDecimalValuesExactlyOnce(){assertEquals(expectedDefinition(),TenantResourceBindingCodec.definitionCanonical(vector()));}
 @Test void javaCommandProtocolUsesRawEnumAndControlValues(){var target=new io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Command(java.util.UUID.fromString("40000000-0000-0000-0000-000000000004"),java.util.UUID.fromString("50000000-0000-0000-0000-000000000005"),io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Operation.TENANT_RESOURCE_BINDING_ESTABLISH,vector(),vector().eligibility(),0);var expected="FF_TENANT_RESOURCE_BINDING_COMMAND_V1S36:40000000-0000-0000-0000-000000000004S36:50000000-0000-0000-0000-000000000005S33:TENANT_RESOURCE_BINDING_ESTABLISHS1:0S1:2S36:30000000-0000-0000-0000-000000000003S1:4"+field(expectedDefinition());assertEquals(expected,TenantResourceBindingCodec.commandCanonical(target));}
 @Test void javaPreparationRejectsSameActionWithoutInventingAnotherIdentity(){var action=java.util.UUID.fromString("40000000-0000-0000-0000-000000000004");var target=new io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Command(action,java.util.UUID.fromString("50000000-0000-0000-0000-000000000005"),io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Operation.TENANT_RESOURCE_BINDING_ESTABLISH,vector(),vector().eligibility(),0);assertThrows(IllegalArgumentException.class,()->new io.fireflysoftware.common.config.interfaces.authority.TenantResourceBindingContracts.Preparation(action,target,1000));}

 private String originalV5()throws Exception{var directory=root().resolve("core-common-config-mgmt-models/src/main/resources/db/migration");try(var paths=Files.list(directory)){return Files.readString(paths.filter(path->path.getFileName().toString().startsWith("V5__")).findFirst().orElseThrow());}}
 private java.util.List<String> proposalColumns()throws Exception{var matcher=java.util.regex.Pattern.compile("INSERT INTO public\\.cfg_native_proposal\\(([^)]+)\\)\\s*VALUES\\((.*?)\\);",java.util.regex.Pattern.DOTALL).matcher(function("cfg_trb_prepare"));assertTrue(matcher.find());return java.util.Arrays.stream(matcher.group(1).split(",")).map(String::trim).toList();}
 private java.util.List<String> proposalValues()throws Exception{var matcher=java.util.regex.Pattern.compile("INSERT INTO public\\.cfg_native_proposal\\(([^)]+)\\)\\s*VALUES\\((.*?)\\);",java.util.regex.Pattern.DOTALL).matcher(function("cfg_trb_prepare"));assertTrue(matcher.find());return java.util.Arrays.stream(matcher.group(2).split(",")).map(String::trim).toList();}
 @Test void proposalInsertSuppliesEveryOriginalRequiredColumnWithoutDefault()throws Exception{var original=originalV5();var start=original.indexOf("CREATE TABLE public.cfg_native_proposal (");var end=original.indexOf("\n);",start);var required=new java.util.HashSet<String>();for(var line:original.substring(start,end).split("\n")){var field=java.util.regex.Pattern.compile("^\\s*([a-z_]+)\\s+(?:UUID|TEXT|TIMESTAMPTZ)\\b.*").matcher(line);if(field.matches()&&line.contains("NOT NULL")&&!line.contains("DEFAULT"))required.add(field.group(1));}var added=java.util.regex.Pattern.compile("ALTER TABLE public\\.cfg_native_proposal ADD COLUMN ([a-z_]+) ([^;]+);").matcher(original);while(added.find())if(added.group(2).contains("NOT NULL")&&!added.group(2).contains("DEFAULT"))required.add(added.group(1));assertEquals(java.util.Set.of("tenant_id","proposal_id","resource_id","target_operation","target_action_id","maker_issuer","maker_subject","maker_employee_id","prepare_action_id"),required);assertEquals(proposalColumns().size(),proposalValues().size());assertTrue(proposalColumns().containsAll(required),"Every required originalV5 proposal column must be supplied");}
 @Test void proposalPreparationReferenceUsesActualPrepareActionAndDeferredFinishLink()throws Exception{var columns=proposalColumns();var values=proposalValues();var prepared=columns.indexOf("prepare_action_id");assertTrue(prepared>=0,"Original preparation action reference required");assertEquals("binding_prepare.action_id",values.get(prepared));assertEquals("(command->>'actionId')::uuid",values.get(columns.indexOf("target_action_id")));var body=function("cfg_trb_prepare");assertTrue(body.contains("action_id:=(target->>'prepareActionId')::uuid"));assertTrue(body.indexOf("cfg_native_finish_action(tenant,action_id,")>body.indexOf("INSERT INTO public.cfg_native_proposal("));assertTrue(originalV5().contains("cfg_proposal_prepare_action FOREIGN KEY(tenant_id,prepare_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED"));}


 // Source contracts against unchanged V5 declarations; actual PostgreSQL behavior is separately gated.
 private java.util.Map<String,String> declaredLegacyPhases()throws Exception{
  var result=new java.util.HashMap<String,String>();
  var matcher=java.util.regex.Pattern.compile("(?:IF|ELSIF)\\s+p_scope\\.mode='([^']+)'\\s+AND\\s+p_scope\\.operation='([^']+)'\\s+THEN\\s+legacy_phase:='([^']+)';").matcher(function("cfg_trb_legacy"));
  while(matcher.find())assertNull(result.put(matcher.group(1)+"/"+matcher.group(2),matcher.group(3)),"Duplicate phase branch");
  return result;
 }
 @Test void preparationBridgeUsesOriginalDraftPhaseWithoutChangingBindingOperation()throws Exception{
  assertTrue(originalV5().contains("NEW.control_version<>0 OR NEW.state<>'DRAFT' OR operation<>'PREPARE'"));
  assertEquals("PREPARE",declaredLegacyPhases().get("PREPARE/TENANT_RESOURCE_BINDING_PREPARE"));
  assertTrue(function("cfg_trb_legacy").contains("set_config('firefly.cfg.operation',legacy_phase,true)"));
 }
 @Test void decisionsBridgeOriginalUppercasePhasesForAllFourOperations()throws Exception{
  assertTrue(originalV5().contains("cfg_native_require_context(p_tenant,action,upper(p_operation),p_proposal,'PROPOSAL',canonical,true)"));
  var phases=declaredLegacyPhases();
  for(var phase:java.util.List.of("SUBMIT","APPROVE","REJECT","WITHDRAW"))assertEquals(phase,phases.get("DECIDE/TENANT_RESOURCE_BINDING_"+phase));
 }
 @Test void bridgeRefusesEveryUndeclaredModeAndOperationPair()throws Exception{
  var phases=declaredLegacyPhases();
  assertEquals(java.util.Map.of("PREPARE/TENANT_RESOURCE_BINDING_PREPARE","PREPARE",
   "DECIDE/TENANT_RESOURCE_BINDING_SUBMIT","SUBMIT","DECIDE/TENANT_RESOURCE_BINDING_APPROVE","APPROVE",
   "DECIDE/TENANT_RESOURCE_BINDING_REJECT","REJECT","DECIDE/TENANT_RESOURCE_BINDING_WITHDRAW","WITHDRAW",
   "CONTROL/TENANT_RESOURCE_BINDING_ESTABLISH","TENANT_RESOURCE_BINDING_ESTABLISH",
   "CONTROL/TENANT_RESOURCE_BINDING_REVOKE","TENANT_RESOURCE_BINDING_REVOKE"),phases);
  for(var unsupported:java.util.List.of("LOOKUP/TENANT_RESOURCE_BINDING_ESTABLISH","PREPARE/TENANT_RESOURCE_BINDING_APPROVE",
    "DECIDE/TENANT_RESOURCE_BINDING_ESTABLISH","CONTROL/TENANT_RESOURCE_BINDING_PREPARE","CONTROL/GRANT_ISSUE"))assertFalse(phases.containsKey(unsupported));
  assertTrue(function("cfg_trb_legacy").contains("ELSE RAISE EXCEPTION 'Exact binding legacy phase required' USING ERRCODE='42501';"));
 }
 private static java.util.List<String> sqlList(String text){
  var result=new java.util.ArrayList<String>();int depth=0,start=0;boolean quoted=false;
  for(int i=0;i<text.length();i++){char c=text.charAt(i);
   if(c=='\''){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='\''){i++;continue;}quoted=!quoted;}
   else if(!quoted){if(c=='(')depth++;else if(c==')')depth--;else if(c==','&&depth==0){result.add(text.substring(start,i).trim());start=i+1;}}
  }
  assertEquals(0,depth);assertFalse(quoted);result.add(text.substring(start).trim());return result;
 }
 private static int closing(String text,int opening){
  int depth=0;boolean quoted=false;
  for(int i=opening;i<text.length();i++){char c=text.charAt(i);
   if(c=='\''){if(quoted&&i+1<text.length()&&text.charAt(i+1)=='\''){i++;continue;}quoted=!quoted;}
   else if(!quoted){if(c=='(')depth++;else if(c==')'&&--depth==0)return i;}
  }
  throw new AssertionError("Closed original declaration required");
 }
 private java.util.Set<String> requiredOriginalColumns(String table)throws Exception{
  var original=originalV5();var start=original.indexOf("CREATE TABLE public."+table+" (");assertTrue(start>=0,table);
  int open=original.indexOf('(',start);var required=new java.util.HashSet<String>();
  for(var part:sqlList(original.substring(open+1,closing(original,open)))){
   var field=java.util.regex.Pattern.compile("^([a-z_]+)\\s+.+",java.util.regex.Pattern.DOTALL).matcher(part);
   if(field.matches()&&part.contains("NOT NULL")&&!part.contains("DEFAULT"))required.add(field.group(1));
  }
  var added=java.util.regex.Pattern.compile("ALTER TABLE public\\."+table+" ADD COLUMN ([a-z_]+) ([^;]+);").matcher(original);
  while(added.find())if(added.group(2).contains("NOT NULL")&&!added.group(2).contains("DEFAULT"))required.add(added.group(1));
  assertFalse(required.isEmpty(),table);return required;
 }
 private static java.util.Map<String,String> insertAssignments(String body,String table){
  var matcher=java.util.regex.Pattern.compile("INSERT INTO public\\."+table+"\\s*\\(").matcher(body);assertTrue(matcher.find(),table);
  int open=matcher.end()-1,end=closing(body,open);var columns=sqlList(body.substring(open+1,end));
  int values=body.indexOf("VALUES",end);assertTrue(values>end,table);int valueOpen=body.indexOf('(',values);var supplied=sqlList(body.substring(valueOpen+1,closing(body,valueOpen)));
  assertEquals(columns.size(),supplied.size(),table+" column/value arity");var result=new java.util.HashMap<String,String>();
  for(int i=0;i<columns.size();i++)assertNull(result.put(columns.get(i),supplied.get(i)),"Duplicate inserted field");return result;
 }
 private String originalFunction(String name)throws Exception{
  var text=originalV5();int start=text.indexOf("CREATE FUNCTION public."+name+"(");assertTrue(start>=0,name);int begin=text.indexOf("AS $$",start),end=text.indexOf("$$;",begin+5);assertTrue(end>begin,name);return text.substring(start,end);
 }
 @Test void allEightSharedInsertsSupplyOriginalRequiredFields()throws Exception{
  var origins=java.util.Map.of("cfg_native_proposal",function("cfg_trb_prepare"),"cfg_native_proposal_revision",function("cfg_trb_prepare"),
   "cfg_native_proposal_control",function("cfg_trb_prepare"),"cfg_native_decision",function("cfg_trb_decide"),
   "cfg_native_action",originalFunction("cfg_native_finish_action"),"cfg_native_receipt",originalFunction("cfg_native_finish_action"),
   "cfg_native_audit",originalFunction("cfg_native_finish_action"),"cfg_native_outbox",originalFunction("cfg_native_finish_action"));
  for(var row:origins.entrySet())assertTrue(insertAssignments(row.getValue(),row.getKey()).keySet().containsAll(requiredOriginalColumns(row.getKey())),row.getKey()+" required CREATE and ALTER fields");
 }
 @Test void executionAdmissionAdvancesOriginalProposalControlCounter()throws Exception{
  assertTrue(originalV5().contains("NEW.control_version<>OLD.control_version+1"));
  var finish=function("cfg_trb_finish_control");var update=java.util.regex.Pattern.compile("UPDATE public\\.cfg_native_proposal_control SET (.*?) WHERE",java.util.regex.Pattern.DOTALL).matcher(finish);assertTrue(update.find());
  assertTrue(update.group(1).contains("control_version=control.control_version+1"),"Execution admission must advance the locked proposal-control counter");
  assertTrue(finish.contains("FOR UPDATE"));assertTrue(finish.contains("control.state<>'APPROVED' OR control.execution_action_id IS NOT NULL"));
 }
 @Test void finishAndReplayKeepProtectedBindingOperationRatherThanLegacyPhase()throws Exception{
  assertTrue(function("cfg_trb_prepare").contains("cfg_native_finish_action(tenant,action_id,'TENANT_RESOURCE_BINDING_PREPARE'"));
  assertTrue(function("cfg_trb_decide").contains("cfg_native_finish_action(tenant,action_id,scope.operation"));
  assertTrue(function("cfg_trb_finish_control").contains("cfg_native_finish_action(scope.tenant_id,scope.action_id,scope.operation"));
  assertTrue(function("cfg_trb_existing").contains("action.operation IS DISTINCT FROM p_scope.operation"));
  var bridge=function("cfg_trb_legacy");assertFalse(bridge.contains("UPDATE public.cfg_trb_scope"));assertFalse(bridge.contains("p_scope.operation:="));
  var finish=originalFunction("cfg_native_finish_action");assertTrue(finish.contains("'operation',p_operation"));assertTrue(finish.contains("'configuration.institution.'||lower(p_operation)"));
 }
 @Test void originalSharedFinishStillBindsFourRowsActorsDigestsAndDeferredLinks()throws Exception{
  var finish=originalFunction("cfg_native_finish_action");
  for(var table:java.util.List.of("cfg_native_action","cfg_native_receipt","cfg_native_audit","cfg_native_outbox"))assertEquals("p_action",insertAssignments(finish,table).get("action_id"));
  var action=insertAssignments(finish,"cfg_native_action");assertEquals("p_operation",action.get("operation"));assertEquals("p_original",action.get("original_request"));assertEquals("p_canonical",action.get("canonical_intent"));assertEquals("p_deadline",action.get("deadline_epoch_millis"));assertEquals("current_setting('firefly.cfg.actor_issuer')",action.get("actor_issuer"));
  var original=originalV5();assertTrue(original.contains("cfg_proposal_prepare_action FOREIGN KEY(tenant_id,prepare_action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED"));
  assertTrue(original.contains("cfg_decision_action FOREIGN KEY(tenant_id,action_id) REFERENCES public.cfg_native_action(tenant_id,action_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED"));
  var complete=originalFunction("cfg_native_complete_action");
  for(var binding:java.util.List.of("receipt.receipt_id IS DISTINCT FROM action.receipt_id","audit.audit_id IS DISTINCT FROM action.audit_id","event.event_id IS DISTINCT FROM action.event_id","receipt.payload->>'operation' IS DISTINCT FROM action.operation","decode(receipt.payload->>'originalBase64','base64') IS DISTINCT FROM action.original_request","receipt.payload->'result' IS DISTINCT FROM action.result"))assertTrue(complete.contains(binding),binding);
 }

 @Test void lookupRowLockPredicateUsesOnlyCurrentPrivateScopeAndExactSourceAssociation()throws Exception{
  var body=function("cfg_trb_lookup_lock_current");
  assertTrue(body.contains("SECURITY DEFINER"));assertTrue(body.contains("search_path=pg_catalog,public,pg_temp"));
  assertTrue(body.contains("scope:=public.cfg_trb_current()"));
  assertTrue(body.contains("scope.mode<>'LOOKUP'"));assertTrue(body.contains("scope.operation<>'TENANT_RESOURCE_BINDING_LOOKUP'"));
  assertTrue(body.contains("(p_tenant IS NULL)=(p_status IS NULL)"));
  assertTrue(body.contains("RETURN p_tenant=scope.tenant_id"));
  assertTrue(body.contains("t.id=scope.tenant_id AND t.tenant_status_id=p_status"));
  for(var unsupported:java.util.List.of("cfg_native_tenant_eligibility","cfg_native_status_eligibility","registered_tenant_id","set_config(","cfg_trb_legacy","INSERT INTO","UPDATE public.","LOCK TABLE"))assertFalse(body.contains(unsupported),unsupported);
 }
 @Test void lookupEligibilityPoliciesPermitOnlyRowLockSelectionAndNeverNewRowChecks()throws Exception{
  var sql=sql();
  assertTrue(sql.contains("CREATE POLICY cfg_trb_tenant_eligibility_lock ON public.cfg_native_tenant_eligibility FOR UPDATE TO cfg_native_guard"));
  assertTrue(sql.contains("USING(public.cfg_trb_lookup_lock_current(tenant_id,NULL::uuid)) WITH CHECK(false)"));
  assertTrue(sql.contains("CREATE POLICY cfg_trb_status_eligibility_lock ON public.cfg_native_status_eligibility FOR UPDATE TO cfg_native_guard"));
  assertTrue(sql.contains("USING(public.cfg_trb_lookup_lock_current(NULL::uuid,status_id)) WITH CHECK(false)"));
  var original=originalV5();assertTrue(original.contains("FOR ALL TO cfg_native_guard USING(public.cfg_native_context_current("));
  assertTrue(original.contains("FOR SELECT TO cfg_native_guard USING(true)"));
  assertFalse(sql.contains("GRANT UPDATE ON public.cfg_native_tenant_eligibility"));
  assertFalse(sql.contains("GRANT UPDATE ON public.cfg_native_status_eligibility"));
 }
 @Test void unmatchedLookupPolicyReturnsFalseWithoutBreakingLegacyPermissiveEvaluation()throws Exception{
  var body=function("cfg_trb_lookup_lock_current");
  assertTrue(body.contains("THEN RETURN false;"));
  assertTrue(body.contains("EXCEPTION WHEN insufficient_privilege THEN RETURN false;"));
  assertFalse(body.contains("WHEN OTHERS"));assertFalse(body.contains("RAISE EXCEPTION"));
 }
 @Test void installerAttestsTheTwoPrivateLookupLockPoliciesAndHelperAcl()throws Exception{
  var attest=sql().substring(sql().indexOf("DO $attest$"));
  for(var required:java.util.List.of("cfg_trb_tenant_eligibility_lock","cfg_trb_status_eligibility_lock","polcmd='w'","polroles=ARRAY[guard]","polpermissive","pg_get_expr","polwithcheck","cfg_trb_lookup_lock_current(uuid,uuid)","p.prosecdef"))assertTrue(attest.contains(required),required);
  var grants=sql().substring(sql().indexOf("GRANT EXECUTE ON FUNCTION public.cfg_trb_open_lookup"),sql().indexOf("DO $attest$"));
  assertFalse(grants.contains("cfg_trb_lookup_lock_current"));
 }
 @Test void lookupScopeRemainsActiveUntilBothEligibilityLockPhasesHaveFinished()throws Exception{
  var open=function("cfg_trb_open_lookup");assertTrue(open.indexOf("cfg_trb_enter(")<open.indexOf("cfg_trb_lock_eligibility("));assertFalse(open.contains("cfg_trb_legacy("));
  var lookup=function("cfg_trb_lookup_current");assertFalse(lookup.contains("UPDATE public.cfg_trb_scope"));assertFalse(lookup.contains("completed=true"));
  var complete=function("cfg_trb_complete_scope");assertTrue(complete.indexOf("cfg_trb_lock_eligibility(")<complete.indexOf("SET completed=true"));
  assertFalse(sql().contains("consumed"));
 }

 @Test void approvedPreHeadCompetingFirstEstablishmentRefusesCasBeforeIdentity()throws Exception{
  var body=function("cfg_trb_bind_current");
  assertTrue(body.indexOf("FOR UPDATE")<body.indexOf("Exact first binding establishment required"));
  assertTrue(body.indexOf("Exact first binding establishment required")<body.indexOf("Exact retained binding namespace identity required"),"A genuinely approved expected0 competitor must encounter the locked-head CAS refusal first");
 }
 @Test void everyAdmissibleReplayAndControlPathStillRequiresCommonRetainedIdentityBeforeScopeUpdate()throws Exception{
  var body=function("cfg_trb_bind_current");int identity=body.indexOf("IF head IS NOT NULL AND(head->>'issuer'");
  assertTrue(identity>body.indexOf("Exact binding successor definition required"),"The same identity guard must remain after the complete replay/CAS/state/version block");
  assertTrue(identity<body.indexOf("until_millis:=p_scope.until_epoch_millis"));
  assertTrue(identity<body.indexOf("UPDATE public.cfg_trb_scope SET before_head=head"));
  assertTrue(body.contains("head->>'issuer' IS DISTINCT FROM definition->'namespace'->>'issuer' OR head->>'platform_tenant' IS DISTINCT FROM definition->'namespace'->>'platformTenant'"));
  assertTrue(body.contains("(head->>'binding_id')::uuid IS DISTINCT FROM(definition->>'bindingId')::uuid"));
  assertTrue(body.contains("THEN RAISE EXCEPTION 'Exact retained binding namespace identity required' USING ERRCODE='23514';END IF;"));
 }
 @Test void nativeAdmissionCaseMatrixRetainsReplayInitialRevocationAndSuccessorFences()throws Exception{
  var body=function("cfg_trb_bind_current");
  var cases=java.util.Map.of(
   "current replay",java.util.List.of("IF p_replay THEN","(head->>'control_version')::bigint IS DISTINCT FROM expected+1","head->>'definition_digest' IS DISTINCT FROM p_target->>'definitionDigest'","Historical head recovery is not a current duplicate admission"),
   "first establishment",java.util.List.of("ELSIF expected=0 THEN","IF head IS NOT NULL OR (definition->>'version')::bigint<>1 OR command->>'operation'<>'TENANT_RESOURCE_BINDING_ESTABLISH'","Exact first binding establishment required"),
   "current head CAS",java.util.List.of("IF head IS NULL OR (head->>'control_version')::bigint IS DISTINCT FROM expected","Exact expected binding head required"),
   "revocation",java.util.List.of("IF command->>'operation'='TENANT_RESOURCE_BINDING_REVOKE' THEN","IF head->>'state'<>'ACTIVE'","Exact active immutable binding required for revocation"),
   "successor",java.util.List.of("ELSIF (definition->>'version')::bigint IS DISTINCT FROM(head->>'definition_version')::bigint+1","Exact binding successor definition required"));
  for(var row:cases.entrySet())for(var required:row.getValue())assertTrue(body.contains(required),row.getKey()+": "+required);
  assertTrue(body.contains("IF head IS DISTINCT FROM candidate THEN RAISE EXCEPTION 'Binding namespace changed while acquiring its exact plan' USING ERRCODE='40001'"));
  assertTrue(body.contains("epoch IS DISTINCT FROM command->'expectedCurrentEligibility'"));
  assertTrue(body.contains("PERFORM public.cfg_trb_current(),public.cfg_trb_timeouts(until_millis)"));
 }

 private static Path fixtureRoot(Path directory)throws Exception{Files.createDirectories(directory.resolve("core-common-config-mgmt-core"));Files.writeString(directory.resolve("pom.xml"),"root");Files.writeString(directory.resolve("core-common-config-mgmt-core/pom.xml"),"core");var sql=directory.resolve(OPTIONAL_V7);Files.createDirectories(sql.getParent());Files.writeString(sql,"source marker");return directory.toAbsolutePath().normalize();}
 @Test void sourceLocatorAcceptsExactRepositoryWorkingDirectory(@org.junit.jupiter.api.io.TempDir Path directory)throws Exception{var source=fixtureRoot(directory);assertEquals(source,root(null,source));}
 @Test void sourceLocatorAcceptsOnlyTheImmediateOwningCoreParent(@org.junit.jupiter.api.io.TempDir Path directory)throws Exception{var source=fixtureRoot(directory);assertEquals(source,root(null,source.resolve("core-common-config-mgmt-core")));}
 @Test void sourceLocatorPreservesValidExplicitProperty(@org.junit.jupiter.api.io.TempDir Path directory)throws Exception{var source=fixtureRoot(directory.resolve("source"));assertEquals(source,root(source.toString(),directory.resolve("unrelated")));}
 @Test void sourceLocatorRequiresBothPomsAndTheOptionalV7Marker(@org.junit.jupiter.api.io.TempDir Path directory)throws Exception{for(var marker:java.util.List.of("pom.xml","core-common-config-mgmt-core/pom.xml",OPTIONAL_V7)){var source=fixtureRoot(directory.resolve(Integer.toString(marker.length())));Files.delete(source.resolve(marker));assertThrows(IllegalStateException.class,()->root(null,source.resolve("core-common-config-mgmt-core")),marker);}}
 @Test void sourceLocatorNeverWalksArbitraryAncestors(@org.junit.jupiter.api.io.TempDir Path directory)throws Exception{var source=fixtureRoot(directory);assertThrows(IllegalStateException.class,()->root(null,source.resolve("nested")));assertThrows(IllegalStateException.class,()->root(null,source.resolve("nested/core-common-config-mgmt-core")));}
 @Test void invalidExplicitSourceCannotFallBackToValidWorkingDirectory(@org.junit.jupiter.api.io.TempDir Path directory)throws Exception{var source=fixtureRoot(directory.resolve("source"));assertThrows(IllegalStateException.class,()->root(directory.resolve("wrong").toString(),source));assertThrows(IllegalStateException.class,()->root("",source));}
}
