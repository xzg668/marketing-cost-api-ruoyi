package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.settlement.*;
import com.sanhua.marketingcost.service.pricing.settlement.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.*;

@Tag("integration")
class MonthlySettlementPriceSourceIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mpp_settlement_source_test").withUsername("root").withPassword("mpp-settlement-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static JdbcTemplate jdbc;static MonthlySettlementPriceSource source;static AtomicInteger prepared=new AtomicInteger();
  static SettlementPriceRepository rules;static SettlementEffectivePriceRepository results;static SettlementFinanceBaseReader bases;static SettlementPriceCalculator calculator;static SettlementPriceEvidence evidence;static org.springframework.transaction.support.TransactionTemplate transaction;static Map<MonthlyPriceRequest,SettlementPriceType> routeTypes=new HashMap<>();
  static MonthlyMaterialPriceService operation;static MonthlyPriceDocumentCodec codec;static AtomicInteger sourceCalls=new AtomicInteger();
  @BeforeAll static void database() throws Exception {
    MYSQL.start();var original=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    var ds=new DelegatingDataSource(original){@Override public java.sql.Connection getConnection() throws java.sql.SQLException{
      var connection=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
        if(method.getName().equals("prepareStatement"))prepared.incrementAndGet();try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
      });
    }};jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/settlement-source-schema.sql")).execute(ds);
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql")){
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/scrap-monthly.sql");var result=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_settlement_source_test < /tmp/scrap-monthly.sql");assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
    }
    var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);for(var mapper:List.of(com.sanhua.marketingcost.mapper.FactorIdentityMapper.class,com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class,com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper.class,com.sanhua.marketingcost.mapper.FactorRowRefMapper.class))configuration.addMapper(mapper);configuration.addMapper(MonthlyPriceStorageMapper.class);configuration.addMapper(MonthlyPriceLinkMapper.class);var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
    var sql=new SqlSessionTemplate(factory.getObject());codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    var finance=new com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader(sql.getMapper(com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class),sql.getMapper(com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper.class),sql.getMapper(com.sanhua.marketingcost.mapper.FactorIdentityMapper.class),sql.getMapper(com.sanhua.marketingcost.mapper.FactorRowRefMapper.class));
    rules=new SettlementPriceRepository(jdbc);results=new SettlementEffectivePriceRepository(jdbc);bases=new SettlementFinanceBaseReader(finance);calculator=new SettlementPriceCalculator();evidence=new SettlementPriceEvidence(new ObjectMapper().findAndRegisterModules());transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));
    source=new MonthlySettlementPriceSource(new SettlementPriceReaderImpl(rules,bases,calculator,results,evidence),codec,new ObjectMapper().findAndRegisterModules());
    var storage=new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class));operation=new MonthlyMaterialPriceService(storage,new DataSourceTransactionManager(ds),codec,(context,requests,dependencies)->{sourceCalls.incrementAndGet();return source.read(context,requests.stream().collect(java.util.stream.Collectors.toMap(java.util.function.Function.identity(),routeTypes::get)));});
  }
  @AfterAll static void stop(){MYSQL.stop();}
  static long publication(String bu,String month,String price){
    String batch=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,?,'finance-original.xlsx',?,?,'finance-fixture','PUBLISHED','210',1,'DEFAULT',NOW())",batch,month,bu,"a".repeat(64),"b".repeat(64));long batchId=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,batch);
    jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES(?,?,'1#铜','1#CU','AVG','AVG|1#CU')",bu,batch);long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,batch);
    jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,?,1,?,'PUBLISHED','210','DEFAULT','GENERAL',1,'CNY/KG',NOW())",identity,month,new BigDecimal(price),batchId);long priceId=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=?",Long.class,identity);
    jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,source_number_format,conversion_basis) VALUES(?,'商用财务基价',3,?,?,4,'D3','DEFAULT','GENERAL',?,'CNY/KG','0.000000','IDENTITY')",batchId,identity,priceId,price);return batchId;
  }
  static Context context(String month,String org){return new Context("COMMERCIAL","MPP05-SETTLEMENT",1L,"TOP",YearMonth.parse(month),YearMonth.parse(month).atDay(23).atStartOfDay(),"fixture",org);}
  static MonthlyPriceRequest request(String code,String month,String org,SettlementPriceType type){var r=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),"COMMERCIAL",code,null,"件","CNY",TaxBasis.EXCLUDED,null),org,NonCuConditions.empty(),BigDecimal.ONE);routeTypes.put(r,type);return r;}
  static String code(){return "MPP05-settle-"+UUID.randomUUID();}
  static SettlementPriceRecord rule(String code,String month,SettlementPriceType type,String given,boolean formula){
    var row=new SettlementPriceRecord();row.setBusinessUnitType("COMMERCIAL");row.setMaterialCode(code);row.setMaterialName("fixture settlement");row.setSpecModel("fixture specification");row.setPriceType(type);row.setPriceMonth(month);row.setRevisionNo(1);row.setActive(true);row.setGivenPrice(new BigDecimal(given));row.setPlannedPrice(new BigDecimal("10"));row.setMarkupRatio(new BigDecimal("1.05"));row.setBudgetCu(new BigDecimal("80"));row.setBudgetZn(new BigDecimal("20"));row.setTaxDivisor(new BigDecimal("1.13"));row.setExpression(SettlementFormula.EXPRESSION);row.setFormulaVersion(SettlementFormula.VERSION);row.setUnitBasis("BOM_MATERIAL_UNIT");row.setSourceSystem("EXCEL");row.setSourceFileName("settlement-original.xls");row.setSourceFileSha256("f".repeat(64));row.setSourceSheet("结算原稿");row.setSourceRowsJson("[{\"row\":8,\"sourceFormula\":\"original-cell-formula\"}]");row.setContentHash("b".repeat(64));row.setCreatedBy("fixture");
    if(formula){row.setCopperWeightG(new BigDecimal("2"));row.setZincWeightG(new BigDecimal("3"));row.setWeightsConfirmed(true);row.setWeightConfirmationNote("source weights confirmed");row.setWeightsConfirmedBy("finance-fixture");row.setWeightsConfirmedAt(LocalDateTime.now());}
    return rules.insert(row);
  }
  static void generate(SettlementPriceRecord row){
    var base=calculator.mode(row)==SettlementPriceMode.LINKED_FORMULA?bases.effective(new SettlementScope("COMMERCIAL",row.getPriceMonth())).orElse(null):null;var value=calculator.calculate(row,base);
    // Controlled fixture publication uses the production calculator, capture and repository.
    var parameters=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("unit","COMMERCIAL").addValue("material",row.getMaterialCode()).addValue("type",row.getPriceType().name()).addValue("month",row.getPriceMonth()).addValue("revision",1).addValue("ruleId",row.getId()).addValue("ruleMonth",row.getPriceMonth()).addValue("ruleRevision",1).addValue("financeId",base==null?0:base.id()).addValue("financeMonth",base==null?null:base.priceMonth()).addValue("financeRevision",base==null?null:base.revisionNo()).addValue("mode",value.mode().name()).addValue("price",value.unitPrice()).addValue("calculator",SettlementPriceCalculator.CALCULATOR_VERSION).addValue("fingerprint",com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity.hash(UUID.randomUUID().toString())).addValue("evidence",evidence.capture(row,base,value)).addValue("actor","fixture");transaction.executeWithoutResult(status->results.insert(List.of(parameters)));
  }
  static Candidates read(Context context,MonthlyPriceRequest... requests){var routes=new LinkedHashMap<MonthlyPriceRequest,SettlementPriceType>();for(var r:requests)routes.put(r,routeTypes.get(r));return source.read(context,routes);}
  static MonthlyAdoptionPlan plan(MonthlyPriceRequest request,MonthlyAdoptionPlan.Selection selection){return new MonthlyAdoptionPlan(Map.of(request,selection),List.of(new MonthlyAdoptionPlan.Use("root/settlement",request,BigDecimal.ONE)),Set.of());}
  @Test void actualFixedZeroAndLinkedGivenReuseSavedResultsAndShareBothOrganizations() throws Exception {
    for(var type:SettlementPriceType.values()){
      String code=code();var row=rule(code,"2026-01",type,type==SettlementPriceType.SETTLE_FIXED?"0":"4.841",false);generate(row);var a=request(code,"2026-02","210",type);var b=request(code,"2026-02","220",type);long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_effective_price",Long.class);prepared.set(0);var result=read(context("2026-02","210"),a,b);int count=prepared.get();assertThat(result.problems()).isEmpty();var selected=result.values().get(a).selection();assertThat(selected.fact().price()).isEqualByComparingTo(row.getGivenPrice());assertThat(selected.applicability().matches(b)).isTrue();assertThat(selected.fact()).isEqualTo(result.values().get(b).selection().fact());assertThat(selected.fact().evidence().json()).contains("settlement-original.xls","original-cell-formula","effectivePrice").doesNotContain("consumerOrgCode");assertThat(count).isLessThanOrEqualTo(8);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_effective_price",Long.class)).isEqualTo(before);
    }
  }
  @Test void aRuleWithoutItsPublishedResultWaitsAndNeverGeneratesOneDuringCandidateReads(){
    String code=code();var row=rule(code,"2026-03",SettlementPriceType.SETTLE_FIXED,"10",false);var r=request(code,"2026-03","210",SettlementPriceType.SETTLE_FIXED);long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_effective_price",Long.class);var waiting=operation.resolveCandidates(context("2026-03","210"),Set.of(r));assertThat(waiting.values()).isEmpty();assertThat(waiting.problems().get(r).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_effective_price",Long.class)).isEqualTo(before);assertThat(operation.lookup(Set.of(r)).missing()).contains(r);generate(row);assertThat(operation.resolveCandidates(context("2026-03","210"),Set.of(r)).values().get(r).selection().fact().price()).isEqualByComparingTo("10");
  }
  @Test void linkedFormulaUsesActualFinanceAndSavedEvidenceThenMonthlyAdoptionSurvivesSourceReplacement(){
    String code=code();String month="2026-07";long batch=publication("COMMERCIAL",month,"100");
    long cu=jdbc.queryForObject("SELECT factor_identity_id FROM lp_factor_monthly_price WHERE source_upload_batch_id=?",Long.class,batch);jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES('COMMERCIAL',?,'1#锌','1#ZN','AVG','AVG|1#ZN')",UUID.randomUUID().toString());long zn=jdbc.queryForObject("SELECT MAX(id) FROM lp_factor_identity",Long.class);
    jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,30,1,?,'PUBLISHED','210','DEFAULT','GENERAL',1,'CNY/KG',NOW())",zn,month,batch);long znPrice=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=?",Long.class,zn);jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit) VALUES(?,'商用财务基价',4,?,?,4,'D4','DEFAULT','GENERAL','30','CNY/KG')",batch,zn,znPrice);
    var row=rule(code,month,SettlementPriceType.SETTLE_LINKED,"4.841",true);generate(row);var r=request(code,month,"210",SettlementPriceType.SETTLE_LINKED);var c=context(month,"210");var candidates=operation.resolveCandidates(c,Set.of(r));assertThat(candidates.problems()).isEmpty();var chosen=candidates.values().get(r).selection();assertThat(chosen.fact().price()).isEqualByComparingTo("10.565044247788");assertThat(chosen.fact().evidence().json()).contains("D3","D4","finance-original.xlsx","1#锌");
    var saved=operation.adopt(c,plan(r,chosen));long id=saved.plan().selections().get(r).monthlyPriceId();jdbc.update("DELETE FROM lp_settlement_effective_price WHERE rule_id=?",row.getId());jdbc.update("DELETE FROM lp_settlement_price WHERE id=?",row.getId());jdbc.update("DELETE FROM lp_factor_row_ref WHERE factor_upload_batch_id=?",batch);jdbc.update("DELETE FROM lp_factor_monthly_price WHERE source_upload_batch_id=?",batch);jdbc.update("DELETE FROM lp_factor_upload_batch WHERE id=?",batch);int calls=sourceCalls.get();var b=request(code,month,"220",SettlementPriceType.SETTLE_LINKED);var reused=operation.resolveCandidates(context(month,"220"),Set.of(b)).values().get(b).selection();assertThat(reused.monthlyPriceId()).isEqualTo(id);assertThat(reused.fact().evidence()).isEqualTo(chosen.fact().evidence());assertThat(sourceCalls.get()).isEqualTo(calls);
    var pair=MonthlyScenarioTestSupport.create(null).calculate(Map.of(r,reused),new BigDecimal("80000")).get(id);
    assertThat(pair.financialPrice()).isEqualByComparingTo("10.565044247788");assertThat(pair.customerPrice()).isEqualByComparingTo("10.527876106195");

  }
  @Test void absentRulesAndBlockingCalculationFailuresRemainDistinct(){
    var missing=request(code(),"2026-08","210",SettlementPriceType.SETTLE_FIXED);assertThat(read(context("2026-08","210"),missing).problems().get(missing).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);
    String code=code();var row=rule(code,"2026-08",SettlementPriceType.SETTLE_LINKED,"10",true);var request=request(code,"2026-08","210",SettlementPriceType.SETTLE_LINKED);assertThat(read(context("2026-08","210"),request).problems().get(request).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);jdbc.update("UPDATE lp_settlement_price SET expression='bad formula' WHERE id=?",row.getId());var invalid=read(context("2026-08","210"),request);assertThat(invalid.values()).isEmpty();assertThat(invalid.problems().get(request).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
  }
  @Test void settlementDoesNotGuessCurrencyTaxOrExplicitUnitConversions(){
    String code=code();var row=rule(code,"2026-04",SettlementPriceType.SETTLE_FIXED,"10",false);row.setMaterialUnit("件");jdbc.update("UPDATE lp_settlement_price SET material_unit='件' WHERE id=?",row.getId());generate(row);var c=context("2026-04","210");for(var family:List.of(new MonthlyPriceFamilyKey(c.month(),"COMMERCIAL",code,null,"KG","CNY",TaxBasis.EXCLUDED,null),new MonthlyPriceFamilyKey(c.month(),"COMMERCIAL",code,null,"件","USD",TaxBasis.EXCLUDED,null),new MonthlyPriceFamilyKey(c.month(),"COMMERCIAL",code,null,"件","CNY",TaxBasis.INCLUDED,null))){var r=new MonthlyPriceRequest(family,"210",NonCuConditions.empty(),BigDecimal.ONE);routeTypes.put(r,SettlementPriceType.SETTLE_FIXED);assertThat(read(c,r).problems().get(r).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);}
  }
  @Test void thousandIdentitiesUseBoundedReadsAndDoNotPublishResults(){
    var requests=new ArrayList<MonthlyPriceRequest>();for(int i=0;i<1000;i++){String code=code();var row=rule(code,"2026-05",SettlementPriceType.SETTLE_FIXED,"10",false);generate(row);requests.add(request(code,"2026-06",i%2==0?"210":"220",SettlementPriceType.SETTLE_FIXED));}long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_effective_price",Long.class);prepared.set(0);var result=read(context("2026-06","210"),requests.toArray(MonthlyPriceRequest[]::new));int count=prepared.get();assertThat(result.values()).hasSize(1000);assertThat(result.problems()).isEmpty();assertThat(count).isLessThanOrEqualTo(32);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_effective_price",Long.class)).isEqualTo(before);
  }
}
