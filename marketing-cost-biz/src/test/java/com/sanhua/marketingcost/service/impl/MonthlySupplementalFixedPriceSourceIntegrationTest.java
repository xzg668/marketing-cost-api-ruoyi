package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.technicaldata.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.*;

/** Disposable actual-schema/SQL approval and adoption, not external OA-provider acceptance. */
@Tag("integration")
class MonthlySupplementalFixedPriceSourceIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql")).withDatabaseName("mpp_technical_source").withUsername("root").withPassword("mpp-technical-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static final String MONTH="2026-10";static final long CURRENT_FORM=910000,CURRENT_ITEM=910001;
  static final QuoteTechnicalDataRepository repository=mock(QuoteTechnicalDataRepository.class);
  static final AtomicInteger statements=new AtomicInteger(),sourceCalls=new AtomicInteger();
  static final AtomicLong sequence=new AtomicLong(1000000);
  static final AtomicReference<Runnable> afterVersionRead=new AtomicReference<>();
  static final AtomicLong versionConnection=new AtomicLong();
  static JdbcTemplate concurrent;
  static JdbcTemplate jdbc;static MonthlySupplementalFixedPriceSource source;static MonthlyMaterialPriceService module;
  static QuoteTechDataVersionMapper versions;static QuoteTechModuleMapper modules;
  static TechnicalDataVersionContentCodec content;static MonthlyPriceDocumentCodec documents;static OaMessageCodec json;
  record Fixture(long task,long product,long priceModule,long personal,long effective,long flow,List<String> materials) {}

  @BeforeAll static void database() throws Exception {
    MYSQL.start();var original=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    concurrent=new JdbcTemplate(original);
    var ds=new DelegatingDataSource(original){@Override public java.sql.Connection getConnection() throws java.sql.SQLException{var connection=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
      if(method.getName().equals("prepareStatement")){statements.incrementAndGet();if(afterVersionRead.get()!=null && args[0].toString().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT).startsWith("SELECT * FROM LP_QUOTE_TECH_DATA_VERSION WHERE ID IN"))try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT CONNECTION_ID()")){result.next();versionConnection.set(result.getLong(1));}}
      try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}});}};
    jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/legacy-range-source-schema.sql"),new ClassPathResource("monthly-pricing/legacy-linked-extra-schema.sql"),new ClassPathResource("monthly-pricing/technical-source-schema.sql"),new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql")){MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/technical-price.sql");var result=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_technical_source < /tmp/technical-price.sql");assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();}
    var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
    for(var mapper:List.of(MonthlySupplementalFixedPriceSourceMapper.class,MonthlyPriceStorageMapper.class,MonthlyPriceLinkMapper.class,QuoteTechDataVersionMapper.class,QuoteTechModuleMapper.class,MaterialMasterRawMapper.class))configuration.addMapper(mapper);
    var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);var sql=new SqlSessionTemplate(factory.getObject());
    var mapper=new ObjectMapper().findAndRegisterModules();content=new TechnicalDataVersionContentCodec(mapper);json=new OaMessageCodec(mapper);documents=new MonthlyPriceDocumentCodec(mapper);versions=sql.getMapper(QuoteTechDataVersionMapper.class);modules=sql.getMapper(QuoteTechModuleMapper.class);
    var approvals=new TechnicalDataCostingSources(null,null,null,null,null,repository,content,new TechnicalDataDependencies(repository,content,json),null,json,null);
    var actualRows=sql.getMapper(MonthlySupplementalFixedPriceSourceMapper.class);
    var hookedRows=mock(MonthlySupplementalFixedPriceSourceMapper.class,org.mockito.AdditionalAnswers.delegatesTo(actualRows));
    doAnswer(call->{var result=actualRows.versions(call.getArgument(0));var hook=afterVersionRead.getAndSet(null);if(hook!=null)hook.run();return result;}).when(hookedRows).versions(anyCollection());
    source=new MonthlySupplementalFixedPriceSource(hookedRows,approvals,content,json,documents);
    module=new MonthlyMaterialPriceService(new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),documents,sql.getMapper(MonthlyPriceLinkMapper.class)),new DataSourceTransactionManager(ds),documents,(context,requests,dependencies)->{sourceCalls.incrementAndGet();return source.read(context,requests);});
    jdbc.update("INSERT INTO oa_form(id,oa_no,business_unit_type) VALUES(?,'MPP05-TECH-CURRENT','COMMERCIAL')",CURRENT_FORM);jdbc.update("INSERT INTO oa_form_item(id,oa_form_id,material_no,business_unit_type) VALUES(?,?,'TOP','COMMERCIAL')",CURRENT_ITEM,CURRENT_FORM);
  }
  @AfterAll static void stop(){MYSQL.stop();}
  @AfterEach void noHiddenApprovalIo(){verifyNoInteractions(repository);}
  static Context context(){return new Context("COMMERCIAL","MPP05-TECH-CURRENT",CURRENT_ITEM,"TOP",YearMonth.parse(MONTH),LocalDateTime.of(2026,10,23,9,0),"fixture","210");}
  static MonthlyPriceRequest request(String code){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(MONTH),"COMMERCIAL",code,"SPEC","件","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);}
  static PriceItem item(String code,String amount){return new PriceItem("ITEM:"+code,code,"210","件","CNY","FIXED",new BigDecimal(amount),null,null,"actual isolated fixture",null,null,null);}

  static Fixture fixed(int count,boolean ownQuote){
    long task=sequence.incrementAndGet(),product=sequence.incrementAndGet(),originalItem=sequence.incrementAndGet(),flow=sequence.incrementAndGet();long originalForm=ownQuote?CURRENT_FORM:sequence.incrementAndGet();
    String originalOa=ownQuote?"MPP05-TECH-CURRENT":"ORIGINAL-"+originalForm;
    if(!ownQuote)jdbc.update("INSERT INTO oa_form(id,oa_no,business_unit_type) VALUES(?,?,'COMMERCIAL')",originalForm,originalOa);
    jdbc.update("INSERT INTO oa_form_item(id,oa_form_id,material_no,business_unit_type) VALUES(?, ?,?,'COMMERCIAL')",originalItem,originalForm,"TOP-"+product);
    jdbc.update("INSERT INTO lp_oa_technical_flow(id,source_system,environment,oa_form_id,accounting_month,external_document_id,finance_ready,finance_confirmed_fingerprint) VALUES(?,'MOCK','ISOLATED',?,'2026-09',?,1,?)",flow,originalForm,"DOC-"+flow,json.canonicalHash("[]"));
    jdbc.update("INSERT INTO lp_quote_tech_task(id,task_no,oa_form_id,oa_form_item_id,oa_no,accounting_month,business_unit_type,applicable_org_code,task_status,assignee_user_id,assignee_name,active_flag,active_lock_key,oa_flow_id) VALUES(?,?,?,?,?,'2026-09','COMMERCIAL','210','SUBMITTED',1,'fixture',1,?,?)",task,"TASK-"+task,originalForm,originalItem,originalOa,"ITEM:"+originalItem+":MONTH:2026-09",flow);
    jdbc.update("INSERT INTO lp_quote_tech_product(id,task_id,oa_form_item_id,quote_no,accounting_month,source_snapshot_json,source_fingerprint,product_status,active_flag,active_lock_key,content_schema_version,material_no) VALUES(?,?,?,?,'2026-09','{}',?,'SUBMITTED',1,?,2,?)",product,task,originalItem,originalOa,"a".repeat(64),"ITEM:"+originalItem+":MONTH:2026-09","TOP-"+product);
    var codes=new ArrayList<String>();var prices=new ArrayList<PriceItem>();
    for(int i=0;i<count;i++){String code="TECH-"+product+"-"+i;codes.add(code);prices.add(item(code,"7"));}
    long personal=sequence.incrementAndGet(),effective=sequence.incrementAndGet(),priceModule=0;
    for(String type:TechnicalDataModuleType.orderedCodes()) {
      long id=sequence.incrementAndGet();boolean price=type.equals("PRICE");if(price)priceModule=id;
      jdbc.update("INSERT INTO lp_quote_tech_module(id,product_id,module_type,required_flag,requirement_reason_code,requirement_reason,source_availability,source_checked_at,module_status,current_version_id,assignee_user_id,assignee_name) VALUES(?,?,?,?,?,?,?,NOW(),?,?,?,?)",id,product,type,price?1:0,"ISOLATED","actual source fixture",price?"MISSING":"AVAILABLE",price?"SUBMITTED":"NOT_REQUIRED",price?personal:null,price?1L:null,price?"fixture":null);
    }
    for(int versionNo=1;versionNo<=2;versionNo++) {
      long id=versionNo==1?personal:effective;
      jdbc.update("INSERT INTO lp_quote_tech_data_version(id,product_id,version_no,version_status,content_schema_version,price_items_json) VALUES(?,?,?,'DRAFT',2,?)",id,product,versionNo,content.pricesJson(new Prices(prices)));
      var version=versions.selectById(id);var roster=modules.selectByProductId(product);version.setReferenceSnapshotJson(content.referenceSnapshotJson(content.moduleSnapshots(roster)));version.setSourceFactsJson(content.sourceFactsJson("{}",roster));
      String fingerprint=content.fingerprint(version,content.readReferenceSnapshot(version.getReferenceSnapshotJson()),List.of(),List.of(),List.of());
      jdbc.update("UPDATE lp_quote_tech_data_version SET reference_snapshot_json=?,source_facts_json=?,content_fingerprint=?,version_status='SUBMITTED' WHERE id=?",version.getReferenceSnapshotJson(),version.getSourceFactsJson(),fingerprint,id);
    }
    jdbc.update("UPDATE lp_quote_tech_product SET effective_version_id=? WHERE id=?",effective,product);
    for(String code:codes) {
      jdbc.update("INSERT INTO lp_quote_tech_price_claim(material_code,owner_module_id,organization_code,business_unit_type,price_unit,currency) VALUES(?,?,'210','COMMERCIAL','件','CNY')",code,priceModule);
      // Official UI fixed publication has no specification restriction; its claim defines scope.
      jdbc.update("INSERT INTO lp_price_fixed_item(material_code,org_code,business_unit_type,spec_model,unit,fixed_price,tax_included,source_kind,technical_version_id,technical_item_key,technical_publication_status,source_type,created_at,updated_at,pricing_month) VALUES(?,'210','COMMERCIAL',NULL,'件',7,0,'TECH_SUPPLEMENTAL',?,?,'AVAILABLE','PURCHASE_FIXED',NOW(),NOW(),'2026-09')",code,personal,"ITEM:"+code);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,organization_code,unit,import_batch_id) VALUES(?,'COMMERCIAL','件','TECH-ISOLATED')",code);
    }
    return new Fixture(task,product,priceModule,personal,effective,flow,List.copyOf(codes));
  }
  @Test void anUntaxedDeclarationDoesNotMakeTheIncludedTaxProbeAnInvalidSource() {
    var fixture=fixed(1,false);var excluded=request(fixture.materials().getFirst());var family=excluded.family();
    var included=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(family.month(),family.businessUnitType(),
        family.materialCode(),family.specification(),family.unit(),family.currency(),TaxBasis.INCLUDED,null),
        excluded.priceOrgCode(),excluded.conditions(),excluded.priceBasisQuantity());
    var result=source.read(context(),Set.of(included,excluded));
    assertThat(result.problems().get(included).reason()).isEqualTo(Failure.MISSING);
    assertThat(result.values().get(excluded).selection().fact().price()).isEqualByComparingTo("7");
    assertThat(module.lookup(Set.of(included,excluded)).missing()).containsExactlyInAnyOrder(included,excluded);
  }

  static AdoptionResult adopt(Candidates candidates,Set<MonthlyPriceRequest> roots){var selected=new LinkedHashMap<MonthlyPriceRequest,Selection>();candidates.values().forEach((request,value)->selected.put(request,value.selection()));return module.adopt(context(),new MonthlyAdoptionPlan(selected,roots.stream().map(root->new Use("root/"+root.family().materialCode(),root,BigDecimal.ONE)).toList(),Set.of()));}
  static void reseal(long id,String priceJson){jdbc.update("UPDATE lp_quote_tech_data_version SET price_items_json=? WHERE id=?",priceJson,id);var version=versions.selectById(id);String hash=content.fingerprint(version,content.readReferenceSnapshot(version.getReferenceSnapshotJson()),List.of(),List.of(),List.of());jdbc.update("UPDATE lp_quote_tech_data_version SET content_fingerprint=? WHERE id=?",hash,id);}

  @Test void finalAggregateAndPersonalPriceSourceAreBothPreservedWithRealSavedId(){var fixture=fixed(1,false);var request=request(fixture.materials().getFirst());long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class);var result=module.resolveCandidates(context(),Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().price()).isEqualByComparingTo("7");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class)).isEqualTo(before);var saved=adopt(result,Set.of(request)).plan().selections().get(request);assertThat(saved.monthlyPriceId()).isPositive();assertThat(saved.fact().evidence().json()).contains("TECH_FIXED",Long.toString(fixture.personal()),Long.toString(fixture.effective()),"contentFingerprint");}
  @Test void aSubmittedAvailablePersonalPriceWithoutFinalEffectiveVersionWaitsAndIsNotAdopted(){var fixture=fixed(1,false);jdbc.update("UPDATE lp_quote_tech_product SET effective_version_id=NULL WHERE id=?",fixture.product());var request=request(fixture.materials().getFirst());var result=module.resolveCandidates(context(),Set.of(request));assertThat(result.values()).isEmpty();assertThat(result.problems().get(request).reason()).isEqualTo(Failure.WAITING);assertThat(module.lookup(Set.of(request)).missing()).contains(request);}
  @Test void approvalFromAnotherQuoteRequiresTheCurrentOriginalFinanceConfirmation(){var fixture=fixed(1,false);jdbc.update("UPDATE lp_oa_technical_flow SET finance_confirmed_fingerprint=NULL WHERE id=?",fixture.flow());var request=request(fixture.materials().getFirst());var result=module.resolveCandidates(context(),Set.of(request));assertThat(result.values()).isEmpty();assertThat(result.problems().get(request).reason()).isEqualTo(Failure.WAITING);}
  @Test void ownQuoteKeepsTheExistingFinalSubmittedReadBeforeFinanceConfirmation(){var fixture=fixed(1,true);jdbc.update("UPDATE lp_oa_technical_flow SET finance_ready=0,finance_confirmed_fingerprint=NULL WHERE id=?",fixture.flow());var request=request(fixture.materials().getFirst());var result=module.resolveCandidates(context(),Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().price()).isEqualByComparingTo("7");}
  @Test void finalPriceContentMustActuallyContainTheSelectedPersonalItem(){var fixture=fixed(1,false);reseal(fixture.effective(),content.pricesJson(new Prices(List.of())));var request=request(fixture.materials().getFirst());var result=module.resolveCandidates(context(),Set.of(request));assertThat(result.values()).isEmpty();assertThat(result.problems().get(request).reason()).isEqualTo(Failure.INVALID_INPUT);}
  @Test void changedMaterialUnitOrRawFixedAmountCannotBypassFinalDeclaration(){var fixture=fixed(2,false);String first=fixture.materials().get(0),second=fixture.materials().get(1);jdbc.update("UPDATE lp_material_master_raw SET unit='KG' WHERE material_code=?",first);jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=8 WHERE material_code=?",second);var a=request(first);var b=request(second);var result=module.resolveCandidates(context(),Set.of(a,b));assertThat(result.values()).isEmpty();assertThat(result.problems().keySet()).containsExactlyInAnyOrder(a,b);assertThat(result.problems().values()).allMatch(value->value.reason()==Failure.INVALID_INPUT);}
  @Test void sourceDeletionAndLaterReturnDoNotChangeTheAdoptedMonthlyFact(){var fixture=fixed(1,false);var request=request(fixture.materials().getFirst());var saved=adopt(module.resolveCandidates(context(),Set.of(request)),Set.of(request)).plan().selections().get(request);jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?",request.family().materialCode());jdbc.update("UPDATE lp_quote_tech_product SET effective_version_id=NULL,product_status='RETURNED' WHERE id=?",fixture.product());jdbc.update("DELETE FROM lp_quote_tech_data_version WHERE id IN (?,?)",fixture.personal(),fixture.effective());int before=sourceCalls.get();var reused=module.resolveCandidates(context(),Set.of(request));assertThat(reused.values().get(request).selection().monthlyPriceId()).isEqualTo(saved.monthlyPriceId());assertThat(sourceCalls.get()).isEqualTo(before);assertThat(module.readByIds("COMMERCIAL",Set.of(saved.monthlyPriceId())).get(saved.monthlyPriceId()).fact().evidence()).isEqualTo(saved.fact().evidence());}
  @Test void fiveHundredActualItemsShareCapturedApprovalAndStayWithinTheTechnicalSqlBudget(){var fixture=fixed(500,false);var requests=new LinkedHashSet<MonthlyPriceRequest>();fixture.materials().forEach(code->requests.add(request(code)));statements.set(0);var result=source.read(context(),requests);int count=statements.get();System.out.println("MPP05_TECH_FIXED_500_SQL="+count);assertThat(result.problems()).isEmpty();assertThat(result.values()).hasSize(500);assertThat(count).isLessThanOrEqualTo(8+16*2);}
  @Test void materialUnitValidationPreservesTheDatabaseMatchedRawCode(){var fixture=fixed(1,false);String code=fixture.materials().getFirst();jdbc.update("UPDATE lp_material_master_raw SET material_code=? WHERE material_code=?",code.toLowerCase(java.util.Locale.ROOT),code);var request=request(code);var result=module.resolveCandidates(context(),Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().evidence().json()).contains(code.toLowerCase(java.util.Locale.ROOT));}
  @Test void equalNumericJsonRepresentationsDoNotMakeTheFinalPriceItemDifferent(){var fixture=fixed(1,false);String code=fixture.materials().getFirst();reseal(fixture.effective(),content.pricesJson(new Prices(List.of(item(code,"7.000000")))));var request=request(code);var result=module.resolveCandidates(context(),Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().price()).isEqualByComparingTo("7");}

  static void submit(Fixture fixture){
    long recipient=sequence.incrementAndGet(),submission=sequence.incrementAndGet();
    jdbc.update("INSERT INTO lp_quote_tech_oa_recipient(id,task_id,assignment_version,assignee_user_id,assignee_name,external_user_id,module_types_json,dispatch_status,todo_status,active_flag) VALUES(?,?,1,1,'fixture',?,'[\"PRICE\"]','CONFIRMED','SUBMITTED',1)",recipient,fixture.task(),"ISOLATED-"+recipient);
    jdbc.update("INSERT INTO lp_quote_tech_submission(id,task_id,product_id,technical_version_id,submission_round,request_id,expected_task_version,expected_product_version,content_schema_version,content_fingerprint,content_snapshot_json,summary_json,assignee_user_id,submitted_by,prepared_at,submission_status,recipient_id) VALUES(?,?,?,?,1,?,0,0,2,?,'{}','{}',1,1,NOW(6),'SENT',?)",submission,fixture.task(),fixture.product(),fixture.personal(),"ISOLATED-"+submission,versions.selectById(fixture.personal()).getContentFingerprint(),recipient);
    jdbc.update("UPDATE lp_quote_tech_oa_recipient SET latest_submission_id=? WHERE id=?",submission,recipient);
  }
  @Test void changedActualSubmissionBasisIncludingAnotherBusinessUnitRequiresFreshConfirmation(){
    var first=fixed(1,false);submit(first);var workflow=new com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository(jdbc);
    jdbc.update("UPDATE lp_oa_technical_flow SET finance_confirmed_fingerprint=? WHERE id=?",json.canonicalHash(workflow.submissionBasis(first.flow())),first.flow());var request=request(first.materials().getFirst());assertThat(module.resolveCandidates(context(),Set.of(request)).problems()).isEmpty();
    var peer=fixed(1,false);Long originalForm=jdbc.queryForObject("SELECT oa_form_id FROM lp_quote_tech_task WHERE id=?",Long.class,first.task());
    jdbc.update("UPDATE lp_quote_tech_task SET oa_form_id=?,oa_flow_id=?,business_unit_type='HOUSEHOLD' WHERE id=?",originalForm,first.flow(),peer.task());jdbc.update("UPDATE lp_quote_tech_price_claim SET business_unit_type='HOUSEHOLD' WHERE owner_module_id=?",peer.priceModule());jdbc.update("UPDATE lp_price_fixed_item SET business_unit_type='HOUSEHOLD' WHERE technical_version_id=?",peer.personal());submit(peer);
    var changed=module.resolveCandidates(context(),Set.of(request));assertThat(changed.values()).isEmpty();assertThat(changed.problems().get(request).reason()).isEqualTo(Failure.WAITING);
    jdbc.update("UPDATE lp_oa_technical_flow SET finance_confirmed_fingerprint=? WHERE id=?",json.canonicalHash(workflow.submissionBasis(first.flow())),first.flow());assertThat(module.resolveCandidates(context(),Set.of(request)).problems()).isEmpty();
  }
  @Test void eachClaimKeepsItsOwnMaterialAndUnitWhenApprovalIsSharedAcrossItems() throws Exception {
    var fixture=fixed(2,false);String first=fixture.materials().get(0),second=fixture.materials().get(1);
    var secondItem=new PriceItem("ITEM:"+second,second,"210","KG","CNY","FIXED",new BigDecimal("7"),null,null,"actual isolated fixture",null,null,null);
    String body=content.pricesJson(new Prices(List.of(item(first,"7"),secondItem)));reseal(fixture.personal(),body);reseal(fixture.effective(),body);
    jdbc.update("UPDATE lp_quote_tech_price_claim SET price_unit='KG' WHERE material_code=?",second);jdbc.update("UPDATE lp_price_fixed_item SET unit='KG' WHERE material_code=?",second);jdbc.update("UPDATE lp_material_master_raw SET unit='KG' WHERE material_code=?",second);
    var a=request(first);var b=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(MONTH),"COMMERCIAL",second,"SPEC","KG","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);
    for(var ordered:List.of(new LinkedHashSet<>(List.of(a,b)),new LinkedHashSet<>(List.of(b,a)))) {
      var found=source.read(context(),ordered);assertThat(found.problems()).isEmpty();
      for(var request:ordered){var snapshot=new ObjectMapper().readTree(found.values().get(request).selection().fact().evidence().json());assertThat(snapshot.path("source").path("approval").path("original").path("materialCode").asText()).isEqualTo(request.family().materialCode());assertThat(snapshot.path("source").path("approval").path("original").path("unit").asText()).isEqualTo(request.family().unit());}
    }
  }
  @Test void anUnrelatedConcurrentDraftPointerCannotCrashTheCapturedSourceRead(){
    var fixture=fixed(1,false);long later=sequence.incrementAndGet();
    jdbc.update("INSERT INTO lp_quote_tech_data_version(id,product_id,version_no,version_status,content_schema_version) VALUES(?,?,3,'DRAFT',2)",later,fixture.product());
    jdbc.update("INSERT INTO lp_quote_tech_package_item(version_id,line_no,component_material_no,component_name,quantity,original_unit,standard_quantity,standard_unit,conversion_factor) VALUES(?,1,'PACK-LATER','later draft fixture',1,'件',1,'件',1)",later);
    afterVersionRead.set(()->concurrent.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{
      connection.setAutoCommit(false);try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT CONNECTION_ID()")){result.next();assertThat(result.getLong(1)).isNotEqualTo(versionConnection.get());assertThat(versionConnection.get()).isPositive();}
      try(var statement=connection.prepareStatement("UPDATE lp_quote_tech_module SET current_version_id=? WHERE product_id=? AND module_type='PACKAGE'")){statement.setLong(1,later);statement.setLong(2,fixture.product());assertThat(statement.executeUpdate()).isEqualTo(1);}connection.commit();return null;
    }));
    var request=request(fixture.materials().getFirst());var found=module.resolveCandidates(context(),Set.of(request));assertThat(afterVersionRead.get()).isNull();assertThat(found.problems()).isEmpty();assertThat(found.values().get(request).selection().fact().price()).isEqualByComparingTo("7");
  }
  @Test void aClaimPointingAtANonPriceModuleCannotPublishAFixedPrice(){
    var fixture=fixed(1,false);jdbc.update("UPDATE lp_quote_tech_module SET required_flag=1,source_availability='MISSING',module_status='SUBMITTED',current_version_id=? WHERE product_id=? AND module_type='PACKAGE'",fixture.personal(),fixture.product());long wrong=jdbc.queryForObject("SELECT id FROM lp_quote_tech_module WHERE product_id=? AND module_type='PACKAGE'",Long.class,fixture.product());jdbc.update("UPDATE lp_quote_tech_price_claim SET owner_module_id=? WHERE material_code=?",wrong,fixture.materials().getFirst());var request=request(fixture.materials().getFirst());var found=module.resolveCandidates(context(),Set.of(request));assertThat(found.values()).isEmpty();assertThat(found.problems().get(request).reason()).isEqualTo(Failure.INVALID_INPUT);
  }
}
