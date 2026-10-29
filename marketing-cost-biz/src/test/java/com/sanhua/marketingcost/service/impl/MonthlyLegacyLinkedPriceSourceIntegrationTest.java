package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.*;

/** Actual rule/catalog/financial/mapping SQL, original evaluator, monthly storage and scrap source. */
@Tag("integration")
class MonthlyLegacyLinkedPriceSourceIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql")).withDatabaseName("mpp_legacy_linked").withUsername("root").withPassword("mpp-linked-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static JdbcTemplate jdbc;
  static MonthlyLegacyLinkedPriceSource source;
  static MonthlyScrapPriceSource scraps;
  static MonthlyMaterialPriceService module;
  static MonthlyPriceDocumentCodec codec;
  static AtomicInteger statements=new AtomicInteger(),calls=new AtomicInteger();
  static PreparedFormulaInputsTest calculator=new PreparedFormulaInputsTest();
  static Set<String> publications=new HashSet<>();

  @BeforeAll static void database() throws Exception {
    MYSQL.start();var original=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    var ds=new DelegatingDataSource(original){@Override public java.sql.Connection getConnection() throws java.sql.SQLException{var c=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{if(method.getName().equals("prepareStatement"))statements.incrementAndGet();try{return method.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}});}};
    jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/legacy-range-source-schema.sql"),new ClassPathResource("monthly-pricing/legacy-linked-extra-schema.sql")).execute(ds);
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql")){MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/legacy-linked.sql");var result=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_legacy_linked < /tmp/legacy-linked.sql");assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();}
    var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
    for(var mapper:List.of(MonthlyOriginalLinkedSourceMapper.class,MonthlyLinkedFormulaInputMapper.class,MonthlyPrivateFactorInputMapper.class,MonthlySupplierInputMapper.class,MonthlyScrapSourceMapper.class,MonthlyPriceStorageMapper.class,MonthlyPriceLinkMapper.class,FactorIdentityMapper.class,FactorMonthlyPriceMapper.class,FactorUploadBatchMapper.class,FactorRowRefMapper.class,MaterialPriceMonthMapper.class,SupplierSupplyRatioMapper.class))config.addMapper(mapper);
    var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);var sql=new SqlSessionTemplate(factory.getObject());
    codec=new MonthlyPriceDocumentCodec(new ObjectMapper());var finance=new FinanceFactorPriceReader(sql.getMapper(FactorUploadBatchMapper.class),sql.getMapper(FactorMonthlyPriceMapper.class),sql.getMapper(FactorIdentityMapper.class),sql.getMapper(FactorRowRefMapper.class));
    source=new MonthlyLegacyLinkedPriceSource(sql.getMapper(MonthlyOriginalLinkedSourceMapper.class),sql.getMapper(MonthlyLinkedFormulaInputMapper.class),sql.getMapper(MonthlyPrivateFactorInputMapper.class),sql.getMapper(MonthlySupplierInputMapper.class),new MaterialPriceMonthSelector(sql.getMapper(MaterialPriceMonthMapper.class)),finance,new SupplierPreferredPriceSelector(new SupplierSupplyRatioResolveServiceImpl(sql.getMapper(SupplierSupplyRatioMapper.class))),calculator.service(),codec,new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
    scraps=new MonthlyScrapPriceSource(sql.getMapper(MonthlyScrapSourceMapper.class),sql.getMapper(MonthlySupplierInputMapper.class),new SupplierPriceSelectionService(null,null),finance,new com.sanhua.marketingcost.service.scrap.ScrapPriceCalculator(new com.sanhua.marketingcost.service.scrap.ScrapPriceRuleCodec(new ObjectMapper().findAndRegisterModules())),codec);
    module=new MonthlyMaterialPriceService(new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class)),new DataSourceTransactionManager(ds),codec,(context,requests,dependencies)->{calls.incrementAndGet();var children=new LinkedHashSet<MonthlyPriceRequest>();var parents=new LinkedHashSet<MonthlyPriceRequest>();requests.forEach(request->{if(request.family().unit().equals("KG"))children.add(request);else parents.add(request);});var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();if(!children.isEmpty()){var found=scraps.read(context,children);values.putAll(found.values());problems.putAll(found.problems());for(var child:children)if(!values.containsKey(child)&&!problems.containsKey(child))problems.put(child,new Problem(Failure.MISSING,"No current scrap source"));}if(!parents.isEmpty()){var found=source.read(context,parents,dependencies);values.putAll(found.values());problems.putAll(found.problems());}return new Candidates(values,problems);});
    variable("Cu","FINANCE","{\"factorCode\":\"Cu\",\"priceSource\":\"平均价\"}","INCL");variable("blank_weight","ENTITY","{\"entity\":\"linkedItem\",\"field\":\"blankWeight\",\"unitScale\":0.001}","NONE");variable("vat_rate","CONST","{\"value\":\"0.13\"}","NONE");variable("scrap","DERIVED","{\"strategy\":\"SCRAP_REF\"}","INCL");
  }
  @AfterAll static void stop(){MYSQL.stop();}
  @AfterEach void noHiddenFormulaIo(){verifyNoInteractions(calculator.variables,calculator.rules,calculator.bindings,calculator.placeholders,calculator.identities,calculator.prices,calculator.finance,calculator.scrapReferences,calculator.scrapPrices);}
  static String code(){return "MPP05-L-"+UUID.randomUUID();}
  static Context context(String month,String org){return new Context("COMMERCIAL","MPP05-LINKED",1L,"TOP",YearMonth.parse(month),YearMonth.parse(month).atDay(23).atStartOfDay(),"fixture",org);}
  static MonthlyPriceRequest request(String code,String month,String org){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),"COMMERCIAL",code,"SPEC","件","CNY",TaxBasis.EXCLUDED,null),org,NonCuConditions.empty(),BigDecimal.ONE);}
  static void variable(String code,String kind,String params,String tax){jdbc.update("INSERT INTO lp_price_variable(variable_code,variable_name,business_unit_type,source_type,status,resolver_kind,resolver_params,tax_mode,created_at,updated_at) VALUES(?,?,'COMMERCIAL','FINANCE','active',?,?,?,NOW(),NOW())",code,code,kind,params,tax);}
  static long rule(String code,String month,String org,String supplier,String expression,int tax){jdbc.update("INSERT INTO lp_price_linked_item(pricing_month,org_code,business_unit_type,material_code,material_name,spec_model,unit,supplier_code,supplier_name,formula_expr,blank_weight,tax_included,effective_from,created_at,updated_at) VALUES(?,?,'COMMERCIAL',?,'fixture linked','SPEC','件',?,?,?,20,?,?,NOW(),NOW())",month,org,code,supplier,supplier,expression,tax,YearMonth.parse(month).atDay(1));return jdbc.queryForObject("SELECT MAX(id) FROM lp_price_linked_item WHERE material_code=? AND supplier_code<=>?",Long.class,code,supplier);}
  static void publication(String month,String org){if(!publications.add(month+org))return;String batch=UUID.randomUUID().toString();jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,'COMMERCIAL','finance-linked.xlsx',?,?,'fixture','PUBLISHED',?,1,'DEFAULT',NOW())",batch,month,"a".repeat(64),"b".repeat(64),org);long id=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,batch);String seq=UUID.randomUUID().toString();jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES('COMMERCIAL',?,'copper','1#Cu','AVG','AVG|1#CU')",seq);long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,seq);for(String condition:org.equals("220")?List.of("TENDER","NON_TENDER"):List.of("GENERAL")){jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,100,1,?,'PUBLISHED',?,'DEFAULT',?,1,'CNY/KG',NOW())",identity,month,id,org,condition);long price=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=? AND condition_code=?",Long.class,identity,condition);jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,conversion_basis) VALUES(?,'财务基价',3,?,?,4,'D3','DEFAULT',?,'100','CNY/KG','IDENTITY')",id,identity,price,condition);}}
  static AdoptionResult adopt(Context context,Candidates candidates,Set<MonthlyPriceRequest> roots){var selections=new LinkedHashMap<MonthlyPriceRequest,Selection>();candidates.values().forEach((request,candidate)->selections.put(request,candidate.selection()));return module.adopt(context,new MonthlyAdoptionPlan(selections,roots.stream().map(root->new Use("root/"+root.family().materialCode(),root,BigDecimal.ONE)).toList(),Set.of()));}

  @Test void constantZeroNeedsNoFinancialPublicationAndReadHasNoWrites(){String month="2018-01",code=code();rule(code,month,"210",null,"0",1);var request=request(code,month,"210");long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class);var result=module.resolveCandidates(context(month,"210"),Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().price()).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class)).isEqualTo(before);assertThat(adopt(context(month,"210"),result,Set.of(request)).plan().selections().get(request).monthlyPriceId()).isPositive();}
  @Test void actualFinanceAndEntityInputsKeepExistingTaxAndSixDecimalBoundary(){String month="2026-01",code=code();publication(month,"210");rule(code,month,"210","S1","[Cu]*[blank_weight]+0.00000049",0);var request=request(code,month,"210");var result=module.resolveCandidates(context(month,"210"),Set.of(request));assertThat(result.problems()).isEmpty();var fact=result.values().get(request).selection().fact();assertThat(fact.price()).isEqualByComparingTo(new BigDecimal("2.00000049").divide(new BigDecimal("1.13"),new MathContext(20,RoundingMode.HALF_UP)).setScale(6,RoundingMode.HALF_UP));assertThat(fact.evidence().json()).contains("finance-linked.xlsx","D3","blank_weight","MONTHLY_LINKED_FORMULA_V1").doesNotContain("MPP05-LINKED");}
  @Test void supplierSelectionPrecedesFormulaEvaluation(){String month="2026-02",code=code();rule(code,month,"210","S1","[missing_unselected_variable]",1);rule(code,month,"210","S2","10",1);jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted) VALUES('COMMERCIAL',?,'fixture','SPEC','S2','S2',0.8,1,0)",code);var request=request(code,month,"210");var result=module.resolveCandidates(context(month,"210"),Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().price()).isEqualByComparingTo("10");assertThat(result.values().get(request).selection().fact().evidence().json()).contains("按主供应商供货比例匹配价格");}
  @Test void missingFinanceAndInvalidSelectedFormulaNeverBecomeZero(){String month="2017-01",a=code(),b=code();rule(a,month,"210","S1","[Cu]",1);rule(b,month,"210","S1","1/0",1);var ar=request(a,month,"210");var br=request(b,month,"210");var result=module.resolveCandidates(context(month,"210"),Set.of(ar,br));assertThat(result.values()).isEmpty();assertThat(result.problems().get(ar).reason()).isEqualTo(Failure.WAITING);assertThat(result.problems().get(br).reason()).isEqualTo(Failure.INVALID_INPUT);assertThat(module.lookup(Set.of(ar,br)).missing()).containsExactlyInAnyOrder(ar,br);}
  @Test void latestWholeScopeMonthDoesNotFallBackPerMaterial(){String code=code();rule(code,"2026-03","210","S1","30",1);rule(code(),"2026-04","210","S1","40",1);var request=request(code,"2026-04","210");var result=source.read(context("2026-04","210"),Set.of(request),ignored->{throw new AssertionError("No dependencies expected");});assertThat(result.values()).isEmpty();assertThat(result.problems().get(request).reason()).isEqualTo(Failure.MISSING);}
  @Test void realFormulaScrapChildIsAtomicallyAdoptedAndOldGraphSurvivesSourceReplacement(){String month="2026-05",code=code(),child=code();rule(code,month,"210","S1","[scrap]",0);jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,ratio,business_unit_type,source_doc_no) VALUES(?,?,0.5,'COMMERCIAL','CMS-REAL-INPUT')",code,child);jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,supplier_code,supplier_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,source_json,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL',?,?,'scrap','S1','S1','FIXED',10,'{}','IMPORT',?,?,'{}','fixture',NOW(),'fixture',NOW())",month,child,month,"d".repeat(64));var request=request(code,month,"210");var first=adopt(context(month,"210"),module.resolveCandidates(context(month,"210"),Set.of(request)),Set.of(request));long id=first.plan().selections().get(request).monthlyPriceId();assertThat(first.plan().selections().get(request).fact().price()).isEqualByComparingTo("5");assertThat(module.readByIds("COMMERCIAL",Set.of(id))).hasSize(2);jdbc.update("DELETE FROM lp_scrap_price WHERE material_code=?",child);jdbc.update("DELETE FROM lp_material_scrap_ref WHERE material_code=?",code);jdbc.update("DELETE FROM lp_price_linked_item WHERE material_code=?",code);int before=calls.get();assertThat(module.resolveCandidates(context(month,"210"),Set.of(request)).values().get(request).selection().monthlyPriceId()).isEqualTo(id);assertThat(calls.get()).isEqualTo(before);assertThat(module.readByIds("COMMERCIAL",Set.of(id)).get(id).fact().evidence().json()).contains("CMS-REAL-INPUT","LINKED_LEGACY");
    var projected=MonthlyScenarioTestSupport.create(calculator.service()).calculate(first.plan().selections(),new BigDecimal("80000"));
    assertThat(projected.get(id).financialPrice()).isEqualByComparingTo("5");assertThat(projected.get(id).customerPrice()).isEqualByComparingTo("5");
}
  @Test void thousandRealFinancialFormulasAreBatched(){String month="2026-06";publication(month,"210");var requests=new LinkedHashSet<MonthlyPriceRequest>();for(int i=0;i<1000;i++){String code=code();rule(code,month,"210","S1","[Cu]*[blank_weight]",0);requests.add(request(code,month,"210"));}statements.set(0);var result=source.read(context(month,"210"),requests,ignored->{throw new AssertionError("No dependencies expected");});int count=statements.get();System.out.println("MPP05_LEGACY_LINKED_1000_SQL="+count);assertThat(result.problems()).isEmpty();assertThat(result.values()).hasSize(1000);assertThat(count).isLessThanOrEqualTo(8+16*4);}

  static long privateIdentity(String month,String businessUnit){
    String key=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES(?,?,'private steel',?,'AVG',?)",businessUnit,key,key,"AVG|PRIVATE_"+key);
    long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,key);
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status) VALUES(?,'TECHNICAL',?,?,'private-original.xlsx',?,?,'fixture','SUCCESS')",key,month,businessUnit,"e".repeat(64),"f".repeat(64));
    long batch=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,key);
    jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,status,scheme_code,unit,source_upload_batch_id) VALUES(?,?,7,'ACTIVE','LEGACY','CNY/KG',?)",identity,month,batch);
    long price=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=?",Long.class,identity);
    jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_workbook_name,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_cell_ref,source_value_text,source_unit) VALUES(?,'private-original.xlsx','私有因素',7,?,?,'R7','7','CNY/KG')",batch,identity,price);
    return identity;
  }
  static void placeholder(String code,String token,String status){jdbc.update("INSERT INTO lp_row_local_placeholder(code,display_name,token_names_json,status,deleted) VALUES(?,?,?,?,0)",code,token,"[\""+token+"\"]",status);}
  static void binding(long rule,long identity,String token){jdbc.update("INSERT INTO lp_price_variable_binding(linked_item_id,token_name,factor_identity_id,effective_date,source,deleted) VALUES(?,?,?,'2026-01-01','MANUAL',0)",rule,token,identity);}

  @Test void privateFactorKeepsItsFullFileAndCellEvidenceAfterAllCurrentInputsAreRemoved(){
    String month="2026-07",code=code(),variable="private_"+UUID.randomUUID().toString().replace("-","");publication(month,"210");
    long identity=privateIdentity(month,"COMMERCIAL");variable(variable,"FINANCE","{\"factorIdentityId\":"+identity+"}","INCL");rule(code,month,"210","S1","["+variable+"]",1);
    var request=request(code,month,"210");var result=module.resolveCandidates(context(month,"210"),Set.of(request));assertThat(result.problems()).isEmpty();
    var first=adopt(context(month,"210"),result,Set.of(request));long id=first.plan().selections().get(request).monthlyPriceId();
    assertThat(first.plan().selections().get(request).fact().price()).isEqualByComparingTo("7");
    assertThat(first.plan().selections().get(request).fact().evidence().json()).contains("private-original.xlsx","R7","e".repeat(64),"usedPrivateFactors","capturedFactorIdentities");
    long batch=jdbc.queryForObject("SELECT source_upload_batch_id FROM lp_factor_monthly_price WHERE factor_identity_id=?",Long.class,identity);
    jdbc.update("DELETE FROM lp_factor_row_ref WHERE factor_identity_id=?",identity);jdbc.update("DELETE FROM lp_factor_monthly_price WHERE factor_identity_id=?",identity);jdbc.update("DELETE FROM lp_factor_upload_batch WHERE id=?",batch);jdbc.update("DELETE FROM lp_factor_identity WHERE id=?",identity);jdbc.update("DELETE FROM lp_price_variable WHERE variable_code=?",variable);jdbc.update("DELETE FROM lp_price_linked_item WHERE material_code=?",code);
    int before=calls.get();assertThat(module.resolveCandidates(context(month,"210"),Set.of(request)).values().get(request).selection().monthlyPriceId()).isEqualTo(id);assertThat(calls.get()).isEqualTo(before);assertThat(module.readByIds("COMMERCIAL",Set.of(id)).get(id).fact().evidence()).isEqualTo(first.plan().selections().get(request).fact().evidence());
  }

  @Test void rowLocalBindingUsesItsCapturedIdentityAndRejectsAForeignBusinessUnit(){
    String month="2026-08",a=code(),b=code();publication(month,"210");String token="行材料含税价";placeholder("__fixture_material",token,"active");
    long local=privateIdentity(month,"COMMERCIAL"),foreign=privateIdentity(month,"HOUSEHOLD");
    binding(rule(a,month,"210","S1",token,1),local,token);binding(rule(b,month,"210","S1",token,1),foreign,token);
    var ar=request(a,month,"210");var br=request(b,month,"210");var result=module.resolveCandidates(context(month,"210"),Set.of(ar,br));
    assertThat(result.values().get(ar).selection().fact().price()).isEqualByComparingTo("7");assertThat(result.problems().get(br).reason()).isEqualTo(Failure.INVALID_INPUT);
    assertThat(result.values().get(ar).selection().fact().evidence().json()).contains("capturedBindings",token,"R7");assertThat(module.lookup(Set.of(ar,br)).missing()).containsExactlyInAnyOrder(ar,br);
  }

  @ParameterizedTest @ValueSource(strings={"ACTIVE","Áctivé","active "})
  void sqlMatchedPlaceholderStatusKeepsItsRawMetadataAndWorksLikeTheOriginalRegistry(String status){
    String month="2026-09",code=code(),token="供管含税价";publication(month,"210");long identity=privateIdentity(month,"COMMERCIAL");
    placeholder("__fixture_accent",token,status);binding(rule(code,month,"210","S1",token,1),identity,token);var request=request(code,month,"210");
    try{var result=module.resolveCandidates(context(month,"210"),Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().price()).isEqualByComparingTo("7");assertThat(result.values().get(request).selection().fact().evidence().json()).contains(status);}
    finally{jdbc.update("DELETE FROM lp_row_local_placeholder WHERE code='__fixture_accent'");}
  }

  @Test void legacyImportOrderingUsesCreatedTimeAndIdWhenEitherRevisionIsUnknown(){
    String month="2026-10",code=code();long old=rule(code,month,"210","S1","1",1);jdbc.update("UPDATE lp_price_linked_item SET rule_revision=99 WHERE id=?",old);long current=rule(code,month,"210","S1","2",1);
    var request=request(code,month,"210");var result=module.resolveCandidates(context(month,"210"),Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().price()).isEqualByComparingTo("2");assertThat(result.values().get(request).selection().fact().evidence().json()).contains("\"id\":"+current);
  }
  @Test void threeMixedRevisionHistoryRowsUseOneConsistentOrderForTheWholeIdentity(){
    String month="2027-05",code=code();long a=rule(code,month,"210","S1","1",1),b=rule(code,month,"210","S1","2",1),c=rule(code,month,"210","S1","3",1);
    jdbc.update("UPDATE lp_price_linked_item SET rule_revision=1,created_at='2027-05-03 00:00:00' WHERE id=?",a);jdbc.update("UPDATE lp_price_linked_item SET rule_revision=2,created_at='2027-05-01 00:00:00' WHERE id=?",b);jdbc.update("UPDATE lp_price_linked_item SET rule_revision=NULL,created_at='2027-05-02 00:00:00' WHERE id=?",c);
    var request=request(code,month,"210");var result=module.resolveCandidates(context(month,"210"),Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().price()).isEqualByComparingTo("1");
  }

  @Test void failedParentDoesNotReturnItsOtherwiseValidScrapDependencyForAdoption(){
    String month="2026-11",code=code(),child=code();rule(code,month,"210","S1","[scrap]-100",1);
    jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,ratio,business_unit_type) VALUES(?,?,0.5,'COMMERCIAL')",code,child);
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,supplier_code,supplier_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,source_json,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL',?,?,'scrap','S1','S1','FIXED',10,'{}','IMPORT',?,?,'{}','fixture',NOW(),'fixture',NOW())",month,child,month,"d".repeat(64));
    var request=request(code,month,"210");long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class);var result=module.resolveCandidates(context(month,"210"),Set.of(request));assertThat(result.values()).isEmpty();assertThat(result.problems().get(request).reason()).isEqualTo(Failure.INVALID_INPUT);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class)).isEqualTo(before);
  }

  @Test void sqlManagedFinancialKeyCannotFallBackToPrivatePriceBecauseItsStoredSpellingDiffers(){
    String month="2026-12",code=code(),variable="managed_"+UUID.randomUUID().toString().replace("-","");publication(month,"210");publication("2030-01","210");
    long privateId=privateIdentity(month,"COMMERCIAL"),publishedId=privateIdentity("2030-01","COMMERCIAL");
    String suffix=UUID.randomUUID().toString();String key="AVG|PRIVATE_"+suffix;String raw="avg|priváté_"+suffix;
    jdbc.update("UPDATE lp_factor_identity SET canonical_factor_key=? WHERE id=?",key,privateId);jdbc.update("UPDATE lp_factor_identity SET canonical_factor_key=? WHERE id=?",raw,publishedId);
    long publication=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE import_type='FINANCE_BASE' AND price_month='2030-01' AND price_org_code='210'",Long.class);
    jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,'2030-01',99,1,?,'PUBLISHED','210','DEFAULT','GENERAL',1,'CNY/KG',NOW())",publishedId,publication);
    // This is the old FinanceFactorVariableQuery's exact managed-key SQL comparison.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_factor_monthly_price p JOIN lp_factor_identity f ON f.id=p.factor_identity_id JOIN lp_factor_upload_batch b ON b.id=p.source_upload_batch_id WHERE f.business_unit_type='COMMERCIAL' AND f.canonical_factor_key=? AND p.price_org_code='210' AND p.status='PUBLISHED' AND b.import_type='FINANCE_BASE' AND b.status='PUBLISHED'",Long.class,key)).isPositive();
    variable(variable,"FINANCE","{\"factorIdentityId\":"+privateId+"}","INCL");rule(code,month,"210","S1","["+variable+"]",1);var request=request(code,month,"210");
    var result=module.resolveCandidates(context(month,"210"),Set.of(request));assertThat(result.values()).isEmpty();assertThat(result.problems().get(request).reason()).isEqualTo(Failure.INVALID_INPUT);assertThat(module.lookup(Set.of(request)).missing()).contains(request);
  }

  @Test void negativePriceBelowHalfAMicroUnitIsRejectedBeforeRoundingInsteadOfBecomingZero(){
    String month="2016-01",code=code();rule(code,month,"210","S1","-0.00000049",1);var request=request(code,month,"210");var result=module.resolveCandidates(context(month,"210"),Set.of(request));
    assertThat(result.values()).isEmpty();assertThat(result.problems().get(request).reason()).isEqualTo(Failure.INVALID_INPUT);assertThat(module.lookup(Set.of(request)).missing()).contains(request);
  }

  @Test void twoDistinctCanonicalRequestsThatAreSqlEquivalentBothKeepTheirManagedStatus(){
    String month="2027-02",a=code(),b=code(),suffix=UUID.randomUUID().toString();publication(month,"210");publication("2030-01","210");
    long first=privateIdentity(month,"COMMERCIAL"),second=privateIdentity(month,"COMMERCIAL");String k1="AVG|PRIVATE_"+suffix,k2="AVG|PRIVÁTÉ_"+suffix;
    jdbc.update("UPDATE lp_factor_identity SET canonical_factor_key=? WHERE id=?",k1,first);jdbc.update("UPDATE lp_factor_identity SET canonical_factor_key=? WHERE id=?",k2,second);
    long batch=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE import_type='FINANCE_BASE' AND price_month='2030-01' AND price_org_code='210'",Long.class);
    jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,'2030-01',99,1,?,'PUBLISHED','210','DEFAULT','GENERAL',1,'CNY/KG',NOW())",first,batch);
    String v1="canon_"+UUID.randomUUID().toString().replace("-",""),v2="canon_"+UUID.randomUUID().toString().replace("-","");variable(v1,"FINANCE","{\"factorIdentityId\":"+first+"}","INCL");variable(v2,"FINANCE","{\"factorIdentityId\":"+second+"}","INCL");rule(a,month,"210","S1","["+v1+"]",1);rule(b,month,"210","S1","["+v2+"]",1);
    var ar=request(a,month,"210");var br=request(b,month,"210");var result=module.resolveCandidates(context(month,"210"),Set.of(ar,br));assertThat(result.values()).isEmpty();assertThat(result.problems().keySet()).containsExactlyInAnyOrder(ar,br);assertThat(result.problems().values()).allMatch(problem->problem.reason()==Failure.INVALID_INPUT);
  }

  @Test void boardFinancialPriceUsesActualSusAndTenderConditionsAndKeepsTheirCoverageSeparate(){
    String month="2027-01",code=code();publication(month,"220");String seq=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES('COMMERCIAL',?,'steel','304/2B/1219','AVG','AVG|304/2B/1219')",seq);
    long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,seq);long batch=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE import_type='FINANCE_BASE' AND price_month=? AND price_org_code='220'",Long.class,month);
    for(String condition:List.of("NON_TENDER","TENDER")){int amount=condition.equals("TENDER")?5:10;jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,?,1,?,'PUBLISHED','220','DEFAULT',?,1,'CNY/KG',NOW())",identity,month,amount,batch,condition);long price=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=? AND condition_code=?",Long.class,identity,condition);jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_cell_ref,source_value_text,source_unit,source_column_number,scheme_code,condition_code) VALUES(?,'财务基价',4,?,?,'D4',?,'CNY/KG',4,'DEFAULT',?)",batch,identity,price,Integer.toString(amount),condition);}
    variable("fixture_sus","FINANCE","{\"shortName\":\"304/2B/1219\",\"priceSource\":\"平均价\"}","INCL");rule(code,month,"220","S1","[fixture_sus]",1);
    var base=request(code,month,"220");var specific=new MonthlyPriceRequest(base.family(),"220",new NonCuConditions(new BoardTerms(new BigDecimal("3000"),null,true)),BigDecimal.ONE);var tender=new MonthlyPriceRequest(base.family(),"220",new NonCuConditions(new BoardTerms(null,null,true)),BigDecimal.ONE);
    var result=module.resolveCandidates(context(month,"220"),Set.of(base,specific,tender));assertThat(result.problems()).isEmpty();assertThat(result.values().get(base).selection().fact().price()).isEqualByComparingTo("10");assertThat(result.values().get(specific).selection().fact().price()).isEqualByComparingTo("3");assertThat(result.values().get(tender).selection().fact().price()).isEqualByComparingTo("5");assertThat(result.values().get(specific).selection().applicability().matches(base)).isFalse();assertThat(result.values().get(specific).selection().fact().evidence().json()).contains("sus304PricePerTon","3000","finalTender");
  }

  @Test void sourceIndependentBoardConstantReusesItsAdoptedFactAcrossDifferentSusTerms(){
    String month="2027-03",code=code();rule(code,month,"220","S1","10",1);var first=request(code,month,"220");var other=new MonthlyPriceRequest(first.family(),"220",new NonCuConditions(new BoardTerms(new BigDecimal("3000"),new BigDecimal("9000"),true)),BigDecimal.ONE);
    var receipt=adopt(context(month,"220"),module.resolveCandidates(context(month,"220"),Set.of(first)),Set.of(first));long id=receipt.plan().selections().get(first).monthlyPriceId();int before=calls.get();assertThat(module.resolveCandidates(context(month,"220"),Set.of(other)).values().get(other).selection().monthlyPriceId()).isEqualTo(id);assertThat(calls.get()).isEqualTo(before);
  }

  @Test void scrapMappingKeepsTheActualDatabaseMatchedRawCodeAndDoesNotJavaRefilterIt(){
    String month="2027-04",suffix=UUID.randomUUID().toString(),code="CAFE-"+suffix,raw="café-"+suffix,child=code();rule(code,month,"210","S1","[scrap]",0);
    jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,ratio,business_unit_type,source_doc_no) VALUES(?,?,0.5,'COMMERCIAL','SQL-COLLATION-RAW')",raw,child);
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,supplier_code,supplier_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,source_json,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL',?,?,'scrap','S1','S1','FIXED',10,'{}','IMPORT',?,?,'{}','fixture',NOW(),'fixture',NOW())",month,child,month,"d".repeat(64));
    var request=request(code,month,"210");var result=module.resolveCandidates(context(month,"210"),Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().price()).isEqualByComparingTo("5");assertThat(result.values().get(request).selection().fact().evidence().json()).contains(raw,"SQL-COLLATION-RAW");
  }
  @Test void financeImportedCorrectionUsesTheExistingLinkedTableAndSavedMonthOutlivesLaterImports() {
    String month="2025-03",code=code();publication(month,"210");publication("2025-04","210");long row=rule(code,month,"210",null,"27",0);
    String batchNo="CORRECTION-"+UUID.randomUUID();
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,import_purpose,price_month,business_unit_type,file_name,file_sha256,status) VALUES(?,'MONTHLY_LINKED_FACTOR','TECH_PRICE_CORRECTION',?,'COMMERCIAL','finance-corrected.xlsx',?,'SUCCESS')",batchNo,month,"c".repeat(64));
    long batch=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,batchNo);
    jdbc.update("UPDATE lp_price_linked_item SET source_kind='TECH_SUPPLEMENTAL',technical_version_id=71,technical_item_key='finance-item',technical_revision=1,technical_publication_status='AVAILABLE',source_upload_batch_id=? WHERE id=?",batch,row);
    var request=request(code,month,"210");var c=context(month,"210");
    var pending=module.resolveCandidates(c,Set.of(request));assertThat(pending.problems()).isEmpty();
    assertThat(module.lookup(Set.of(request)).missing()).containsExactly(request);
    var saved=adopt(c,pending,Set.of(request)).plan().selections().get(request);
    // The established legacy tax_included=0 contract divides by 1.13.
    assertThat(saved.fact().price()).isEqualByComparingTo(new BigDecimal("27").divide(new BigDecimal("1.13"),6,RoundingMode.HALF_UP));
    assertThat(saved.fact().evidence().json()).contains("finance-corrected.xlsx","TECH_PRICE_CORRECTION","TECH_SUPPLEMENTAL");
    jdbc.update("UPDATE lp_price_linked_item SET formula_expr='99' WHERE id=?",row);
    assertThat(module.resolveCandidates(c,Set.of(request)).values().get(request).selection()).isEqualTo(saved);
    var next=request(code,"2025-04","210");
    assertThat(module.resolveCandidates(context("2025-04","210"),Set.of(next)).values().get(next).selection().fact().price()).isEqualByComparingTo(new BigDecimal("99").divide(new BigDecimal("1.13"),6,RoundingMode.HALF_UP));
  }

  @Test void technicalLinkedWithoutASuccessfulFinanceImportIsNotAPriceTableCandidate() {
    String month="2025-05",code=code();long row=rule(code,month,"210",null,"27",0);
    jdbc.update("UPDATE lp_price_linked_item SET source_kind='TECH_SUPPLEMENTAL',technical_publication_status='AVAILABLE' WHERE id=?",row);
    var request=request(code,month,"210");
    assertThat(module.resolveCandidates(context(month,"210"),Set.of(request)).problems().get(request).reason()).isEqualTo(Failure.MISSING);
    assertThat(module.lookup(Set.of(request)).missing()).containsExactly(request);
  }

  @Test void anActualScrapDependencyAlsoRequestedInTheBatchDoesNotRetainALinkedMissingProblem() {
    String month="2025-06",code=code(),child=code();publication(month,"210");
    rule(code,month,"210","S1","[scrap]",0);
    jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,ratio,business_unit_type) VALUES(?,?,1,'COMMERCIAL')",code,child);
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,supplier_code,supplier_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,source_json,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL',?,?,'scrap','S1','S1','FIXED',10,'{}','IMPORT',?,?,'{}','fixture',NOW(),'fixture',NOW())",month,child,month,"d".repeat(64));
    var parent=request(code,month,"210");var dependency=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),"COMMERCIAL",child,null,"KG","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);
    var found=source.read(context(month,"210"),Set.of(parent,dependency),requests->module.resolveScrapCandidates(context(month,"210"),requests));
    assertThat(found.problems()).isEmpty();assertThat(found.values()).containsOnlyKeys(parent,dependency);
    var saved=adopt(context(month,"210"),found,Set.of(parent,dependency));assertThat(saved.plan().selections()).hasSize(2);
  }

  @Test void customerCopperReplaysTheCapturedLegacyFormulaAfterEveryCurrentRowHasBeenRemoved(){
    String month="2028-01",code=code();publication(month,"210");rule(code,month,"210","S1","[Cu]*[blank_weight]",0);
    var request=request(code,month,"210");var receipt=adopt(context(month,"210"),module.resolveCandidates(context(month,"210"),Set.of(request)),Set.of(request));
    jdbc.update("DELETE FROM lp_price_linked_item WHERE material_code=?",code);
    var projection=MonthlyScenarioTestSupport.create(calculator.service());
    var pair=projection.calculate(receipt.plan().selections(),new BigDecimal("80000")).values().iterator().next();
    assertThat(pair.financialPrice()).isEqualByComparingTo("1.769912");assertThat(pair.customerPrice()).isEqualByComparingTo("1.415929");
    assertThat(projection.calculate(receipt.plan().selections(),new BigDecimal("100000")).values().iterator().next().customerPrice()).isEqualByComparingTo("1.769912");
  }

  @Test void singleSupplierLegacyPriceDoesNotDependOnRatio() {
    String code=code(),month="2040-05"; rule(code,month,"210","S1","10",1); var req=request(code,month,"210");
    assertThat(module.resolveCandidates(context(month,"210"),Set.of(req)).problems()).isEmpty();
    jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted) VALUES('COMMERCIAL',?,'fixture','SPEC','S1','S1',0,1,0)",code);
    var result=module.resolveCandidates(context(month,"210"),Set.of(req)); assertThat(result.problems()).isEmpty(); assertThat(result.values()).containsKey(req);
  }
}
