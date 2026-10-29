package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
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
class MonthlyScrapPriceSourceIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mpp_scrap_source_test").withUsername("root").withPassword("mpp-scrap-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader financeReader;static JdbcTemplate jdbc;static MonthlyScrapPriceSource source;static AtomicInteger prepared=new AtomicInteger();
  static MonthlyMaterialPriceService operation;static MonthlyPriceDocumentCodec codec;static AtomicInteger sourceCalls=new AtomicInteger();
  @BeforeAll static void database() throws Exception {
    MYSQL.start();var original=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    var ds=new DelegatingDataSource(original){@Override public java.sql.Connection getConnection() throws java.sql.SQLException{
      var connection=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
        if(method.getName().equals("prepareStatement"))prepared.incrementAndGet();try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
      });
    }};jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/scrap-source-schema.sql")).execute(ds);
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql")){
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/scrap-monthly.sql");var result=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_scrap_source_test < /tmp/scrap-monthly.sql");assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
    }
    var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(MonthlyScrapSourceMapper.class);for(var mapper:List.of(com.sanhua.marketingcost.mapper.FactorIdentityMapper.class,com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class,com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper.class,com.sanhua.marketingcost.mapper.FactorRowRefMapper.class))configuration.addMapper(mapper);configuration.addMapper(MonthlySupplierInputMapper.class);configuration.addMapper(MonthlyPriceStorageMapper.class);configuration.addMapper(MonthlyPriceLinkMapper.class);var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
    var sql=new SqlSessionTemplate(factory.getObject());codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    var finance=new com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader(sql.getMapper(com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class),sql.getMapper(com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper.class),sql.getMapper(com.sanhua.marketingcost.mapper.FactorIdentityMapper.class),sql.getMapper(com.sanhua.marketingcost.mapper.FactorRowRefMapper.class));
    financeReader=finance;
    source=new MonthlyScrapPriceSource(sql.getMapper(MonthlyScrapSourceMapper.class),sql.getMapper(MonthlySupplierInputMapper.class),new SupplierPriceSelectionService(null,null),finance,new com.sanhua.marketingcost.service.scrap.ScrapPriceCalculator(new com.sanhua.marketingcost.service.scrap.ScrapPriceRuleCodec(new ObjectMapper().findAndRegisterModules())),codec);
    var storage=new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class));operation=new MonthlyMaterialPriceService(storage,new DataSourceTransactionManager(ds),codec,(context,requests,dependencies)->{sourceCalls.incrementAndGet();return source.read(context,requests);});
  }
  @AfterAll static void stop(){MYSQL.stop();}
  static Context context(String bu,String month,String org){return new Context(bu,"MPP05-SCRAP",1L,"TOP",YearMonth.parse(month),YearMonth.parse(month).atDay(23).atStartOfDay(),"fixture",org);}
  static MonthlyPriceRequest request(String code,String bu,String month,String org){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),bu,code,null,"KG","CNY",TaxBasis.EXCLUDED,null),org,NonCuConditions.empty(),BigDecimal.ONE);}
  static String code(){return "MPP05-scrap-"+UUID.randomUUID();}
  static long rule(String code,String bu,String month,String supplier,String price,String expression){
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,supplier_code,supplier_name,price_type,fixed_price,source_formula,expression,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at,source_file_name,source_file_sha256,source_sheet,source_row,source_json) VALUES(?,?,?,'fixture scrap',?,?,?, ?,?,?,?,'IMPORT',? ,?,'fixture',NOW(),'fixture',NOW(),'scrap-original.xlsx',?,'废料表',8,?)",bu,month,code,supplier,supplier,expression==null?"FIXED":"LINKED",price==null?null:new BigDecimal(price),expression,expression,expression==null?"{}":"{\"Cu\":\"AVG|1#CU\"}",month,"c".repeat(64),"d".repeat(64),"{\"originalFormula\":\"fixture source rule\"}");
    return jdbc.queryForObject("SELECT id FROM lp_scrap_price WHERE business_unit_type=? AND price_month=? AND material_code=? AND supplier_code <=> ?",Long.class,bu,month,code,supplier);
  }
  static long publication(String bu,String month,String price){
    String batch=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,?,'finance-original.xlsx',?,?,'finance-fixture','PUBLISHED','210',1,'DEFAULT',NOW())",batch,month,bu,"a".repeat(64),"b".repeat(64));long batchId=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,batch);
    jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES(?,?,'1#铜','1#CU','AVG','AVG|1#CU')",bu,batch);long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,batch);
    jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,?,1,?,'PUBLISHED','210','DEFAULT','GENERAL',1,'CNY/KG',NOW())",identity,month,new BigDecimal(price),batchId);long priceId=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=?",Long.class,identity);
    jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,source_number_format,conversion_basis) VALUES(?,'商用财务基价',3,?,?,4,'D3','DEFAULT','GENERAL',?,'CNY/KG','0.000000','IDENTITY')",batchId,identity,priceId,price);return batchId;
  }
  static MonthlyAdoptionPlan plan(MonthlyPriceRequest request,MonthlyAdoptionPlan.Selection selection){return new MonthlyAdoptionPlan(Map.of(request,selection),List.of(new MonthlyAdoptionPlan.Use("root/scrap",request,BigDecimal.ONE)),Set.of());}
  @Test void fixedScrapIncludesZeroAndSharesNormalFactAcross210And220WithoutWritingCarryRows() throws Exception {
    String code=code();long ruleId=rule(code,"COMMERCIAL","2026-01",null,"0",null);var r210=request(code,"COMMERCIAL","2026-02","210");var r220=request(code,"COMMERCIAL","2026-02","220");
    long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_scrap_price",Long.class);prepared.set(0);var candidates=source.read(context("COMMERCIAL","2026-02","210"),Set.of(r210,r220));int count=prepared.get();
    assertThat(candidates.problems()).isEmpty();var selection=candidates.values().get(r210).selection();assertThat(selection.fact().price()).isZero();assertThat(selection.fact().type()).isEqualTo(MonthlyAdoptionPlan.PricingType.NORMAL);assertThat(selection.applicability().matches(r220)).isTrue();assertThat(candidates.values().get(r220).selection().fact()).isEqualTo(selection.fact());
    assertThat(count).isLessThanOrEqualTo(12);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_scrap_price",Long.class)).isEqualTo(before);var evidence=new ObjectMapper().readTree(selection.fact().evidence().json());assertThat(evidence.path("sourceKind").asText()).isEqualTo("SCRAP_PRICE");assertThat(evidence.path("source").path("selected").path("id").asLong()).isEqualTo(ruleId);assertThat(selection.fact().evidence().json()).contains("scrap-original.xlsx","fixture source rule").doesNotContain("consumerOrg","scopeKey","customerCopper");
  }
  @Test void linkedScrapUsesActualPublished210FinanceAndRetainsAllInputsAfterSourcesAreRemoved() throws Exception {
    String code=code();String month="2026-09";long batch=publication("COMMERCIAL",month,"100");long ruleId=rule(code,"COMMERCIAL",month,"S1",null,"[Cu]*0.982/1.13");var c=context("COMMERCIAL",month,"210");var request=request(code,"COMMERCIAL",month,"210");
    var ready=operation.resolveCandidates(c,Set.of(request));assertThat(ready.problems()).isEmpty();var selected=ready.values().get(request).selection();assertThat(selected.fact().price()).isEqualByComparingTo("86.902654867256637168");var saved=operation.adopt(c,plan(request,selected));long id=saved.plan().selections().get(request).monthlyPriceId();String snapshot=saved.plan().selections().get(request).fact().evidence().json();assertThat(snapshot).contains("finance-original.xlsx","商用财务基价","D3","100","[Cu]*0.982/1.13");
    jdbc.update("DELETE FROM lp_scrap_price WHERE id=?",ruleId);jdbc.update("DELETE FROM lp_factor_row_ref WHERE factor_upload_batch_id=?",batch);jdbc.update("DELETE FROM lp_factor_monthly_price WHERE source_upload_batch_id=?",batch);jdbc.update("DELETE FROM lp_factor_upload_batch WHERE id=?",batch);int calls=sourceCalls.get();var other=request(code,"COMMERCIAL",month,"220");var reused=operation.resolveCandidates(context("COMMERCIAL",month,"220"),Set.of(other)).values().get(other).selection();
    assertThat(reused.monthlyPriceId()).isEqualTo(id);assertThat(reused.fact().evidence().json()).isEqualTo(snapshot);assertThat(sourceCalls.get()).isEqualTo(calls);assertThat(operation.readByIds("COMMERCIAL",Set.of(id)).get(id).fact()).isEqualTo(reused.fact());
    var pair=MonthlyScenarioTestSupport.create(null).calculate(Map.of(request,reused),new BigDecimal("80000")).get(id);
    assertThat(pair.financialPrice()).isEqualByComparingTo("86.902654867256637168");assertThat(pair.customerPrice()).isEqualByComparingTo("69.522123893805309735");

  }
  @Test void missingFinanceWaitsThenRecoversAndCalculationErrorsDoNotAdopt(){
    String code=code();String month="2026-01";rule(code,"HOUSEHOLD",month,null,null,"[Cu]*0.5");var c=context("HOUSEHOLD",month,"220");var request=request(code,"HOUSEHOLD",month,"220");
    var waiting=operation.resolveCandidates(c,Set.of(request));assertThat(waiting.values()).isEmpty();assertThat(waiting.problems().get(request).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);assertThat(operation.lookup(Set.of(request)).missing()).contains(request);
    publication("HOUSEHOLD",month,"100");var ready=operation.resolveCandidates(c,Set.of(request));assertThat(ready.problems()).isEmpty();assertThat(ready.values().get(request).selection().fact().price()).isEqualByComparingTo("50");
    String invalid=code();rule(invalid,"HOUSEHOLD",month,null,null,"[Cu]/0");var bad=request(invalid,"HOUSEHOLD",month,"210");var failed=operation.resolveCandidates(c,Set.of(bad));assertThat(failed.values()).isEmpty();assertThat(failed.problems().get(bad).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);assertThat(operation.lookup(Set.of(bad)).missing()).contains(bad);
  }
  @Test void registeredFutureOnlyIsMissingAndUnregisteredMaterialRemainsAvailableForOrdinaryRouting(){
    String future=code();rule(future,"COMMERCIAL","2027-01",null,"10",null);var c=context("COMMERCIAL","2026-10","210");var registered=request(future,"COMMERCIAL","2026-10","210");var absent=request(code(),"COMMERCIAL","2026-10","210");var result=source.read(c,Set.of(registered,absent));
    assertThat(result.values()).isEmpty();assertThat(result.problems().get(registered).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);assertThat(result.problems()).doesNotContainKey(absent);
  }
  @Test void supplierReviewRemainsConsumerSpecificWhileAdoptedScrapFactIsShared(){
    String code=code();String month="2026-04";rule(code,"COMMERCIAL",month,"S1","10",null);rule(code,"COMMERCIAL",month,"S2","20",null);var r210=request(code,"COMMERCIAL",month,"210");var c=context("COMMERCIAL",month,"210");
    var waiting=operation.resolveCandidates(c,Set.of(r210));assertThat(waiting.problems().get(r210).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);var review=c.supplierReviews().reviews().getFirst();
    jdbc.update("INSERT INTO lp_supplier_price_decision(scope_key,fingerprint,business_unit_type,oa_no,oa_form_item_id,period_month,pricing_date,org_code,material_code,price_type,decision,confirmed_by,confirmed_at) VALUES(?,?,'COMMERCIAL','MPP05-SCRAP',1,? ,?,'210',?,'SCRAP_PRICE','FALLBACK_HIGH','only-consumer-210',NOW())",review.scopeKey(),review.fingerprint(),month,c.priceAsOfTime().toLocalDate(),code);
    var other=context("COMMERCIAL",month,"220");var r220=request(code,"COMMERCIAL",month,"220");assertThat(source.read(other,Set.of(r220)).problems().get(r220).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);
    var chosen=operation.resolveCandidates(c,Set.of(r210)).values().get(r210).selection();assertThat(chosen.fact().price()).isEqualByComparingTo("20");long id=operation.adopt(c,plan(r210,chosen)).plan().selections().get(r210).monthlyPriceId();var another=context("COMMERCIAL",month,"220");var shared=operation.resolveCandidates(another,Set.of(r220)).values().get(r220).selection();assertThat(shared.monthlyPriceId()).isEqualTo(id);assertThat(another.supplierReviews().reviews()).isEmpty();assertThat(shared.fact().evidence().json()).doesNotContain("only-consumer-210","scopeKey","consumerOrg");
  }
  @Test void thousandFixedScrapsAreBatchedWithoutFinanceReadsOrSourceWrites(){
    var requests=new LinkedHashSet<MonthlyPriceRequest>();for(int i=0;i<1000;i++){String code=code();rule(code,"COMMERCIAL","2026-05","S1","10",null);requests.add(request(code,"COMMERCIAL","2026-06","210"));}
    long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_scrap_price",Long.class);prepared.set(0);var result=source.read(context("COMMERCIAL","2026-06","210"),requests);int count=prepared.get();assertThat(result.values()).hasSize(1000);assertThat(result.problems()).isEmpty();assertThat(count).as("four <=256 batches; no publication read for FIXED").isLessThanOrEqualTo(48);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_scrap_price",Long.class)).isEqualTo(before);
  }
  @Test void explicitSupplierCannotResurrectAnIdentitySupersededByAnotherSuppliersLaterRule() throws Exception {
    String code=code();rule(code,"HOUSEHOLD","2026-01","S1","10",null);long replacement=rule(code,"HOUSEHOLD","2026-02","S2","20",null);
    String oldIdentity=new com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity("HOUSEHOLD","2026-02",code,"S1","S1").materialSupplierKey();jdbc.update("UPDATE lp_scrap_price SET superseded_identity_keys_json=? WHERE id=?",new ObjectMapper().writeValueAsString(List.of(oldIdentity)),replacement);
    var c=context("HOUSEHOLD","2026-04","210");var r=request(code,"HOUSEHOLD","2026-04","210");var f=r.family();var explicit=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(f.month(),f.businessUnitType(),code,null,"KG","CNY",TaxBasis.EXCLUDED,"S1"),"210",NonCuConditions.empty(),BigDecimal.ONE);
    var result=source.read(c,Set.of(explicit));assertThat(result.values()).isEmpty();assertThat(result.problems().get(explicit).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);assertThat(source.read(c,Set.of(r)).values().get(r).selection().fact().price()).isEqualByComparingTo("20");
  }
  @Test void existingLinkedScrapApprovalFingerprintIsPreservedWithTheActualMaterialIdentity(){
    String code=code();String month="2026-11";publication("HOUSEHOLD",month,"100");rule(code,"HOUSEHOLD",month,"S1",null,"[Cu]*0.5");rule(code,"HOUSEHOLD",month,"S2",null,"[Cu]*0.6");
    var c=context("HOUSEHOLD",month,"210");var request=request(code,"HOUSEHOLD",month,"210");
    var financial=financeReader.readDefault(new com.sanhua.marketingcost.dto.FinanceFactorImportContext("HOUSEHOLD","210",month,null,null),code).financial();var calculator=new com.sanhua.marketingcost.service.scrap.ScrapPriceCalculator(new com.sanhua.marketingcost.service.scrap.ScrapPriceRuleCodec(new ObjectMapper().findAndRegisterModules()));
    var oldCandidates=new ArrayList<com.sanhua.marketingcost.service.pricing.SupplierPriceCandidate>();
    for(var row:jdbc.query("SELECT * FROM lp_scrap_price WHERE material_code=?",org.springframework.jdbc.core.BeanPropertyRowMapper.newInstance(com.sanhua.marketingcost.entity.ScrapPrice.class),code)){
      var input=calculator.prepare(row,financial);oldCandidates.add(new com.sanhua.marketingcost.service.pricing.SupplierPriceCandidate(input.fingerprint(),row.getSupplierCode(),row.getSupplierName(),calculator.calculate(input),null,null));
    }
    var scope=new com.sanhua.marketingcost.service.pricing.SupplierPriceScope("HOUSEHOLD",c.oaNo(),c.oaFormItemId(),month,c.priceAsOfTime().toLocalDate(),"210",code,"SCRAP_PRICE");var oldSelection=new SupplierPriceSelectionService(null,null).selectPrepared(scope,oldCandidates,List.of(),null);var review=oldSelection.review();
    jdbc.update("INSERT INTO lp_supplier_price_decision(scope_key,fingerprint,business_unit_type,oa_no,oa_form_item_id,period_month,pricing_date,org_code,material_code,price_type,decision,confirmed_by,confirmed_at) VALUES(?,?,'HOUSEHOLD','MPP05-SCRAP',1,?,?,'210',?,'SCRAP_PRICE','FALLBACK_HIGH','existing-source-review',NOW())",review.scopeKey(),review.fingerprint(),month,c.priceAsOfTime().toLocalDate(),code);
    var result=source.read(c,Set.of(request));assertThat(result.problems()).isEmpty();assertThat(result.values().get(request).selection().fact().price()).isEqualByComparingTo("60");assertThat(c.supplierReviews().reviews().getFirst().fingerprint()).isEqualTo(review.fingerprint());assertThat(result.values().get(request).selection().fact().evidence().json()).doesNotContain("MONTHLY_SCRAP");
  }
  @Test void anIncompletePublishedFinanceSnapshotIsInvalidAndNeverBecomesAWaitOrZeroPrice(){
    String code=code();String month="2026-02";long batch=publication("HOUSEHOLD",month,"100");rule(code,"HOUSEHOLD",month,null,null,"[Cu]*0.5");jdbc.update("DELETE FROM lp_factor_row_ref WHERE factor_upload_batch_id=?",batch);var r=request(code,"HOUSEHOLD",month,"210");var result=operation.resolveCandidates(context("HOUSEHOLD",month,"210"),Set.of(r));assertThat(result.values()).isEmpty();assertThat(result.problems().get(r).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);assertThat(operation.lookup(Set.of(r)).missing()).contains(r);
  }
  @Test void monthlyPreparationPreservesTheScrapKilogramContractForExistingManufacturingConversions() {
    String code=code();String month="2026-10";
    rule(code,"COMMERCIAL",month,null,"10",null);
    var request=request(code,"COMMERCIAL",month,"210");
    var selection=source.read(context("COMMERCIAL",month,"210"),Set.of(request)).values().get(request).selection();
    var input=new QuotationPriceSession.Value(request,selection,new BigDecimal("10"),new BigDecimal("10"));
    String captured=codec.encode(MonthlyPreparationPrice.read(input));
    assertThat(com.sanhua.marketingcost.service.scrap.ScrapPriceCalculationEvidence.isScrap(captured)).isTrue();
    jdbc.update("UPDATE lp_scrap_price SET fixed_price=99 WHERE material_code=?",code);
    assertThat(com.sanhua.marketingcost.service.scrap.ScrapPriceCalculationEvidence.isScrap(captured)).isTrue();
    assertThat(codec.decode(captured,MonthlyPreparationPrice.class).financialPrice()).isEqualByComparingTo("10");
    assertThat(operation.lookup(Set.of(request)).missing()).containsExactly(request);
  }
  @Test void aDiscarded210AutomaticReviewCannotMatch220SharedEvidenceAndAnUnrelated210ExplicitSelection() throws Exception {
    String code=code();String month="2026-12";rule(code,"COMMERCIAL",month,"S1","10",null);rule(code,"COMMERCIAL",month,"S2","20",null);var a=request(code,"COMMERCIAL",month,"210");var b=request(code,"COMMERCIAL",month,"220");var probe=context("COMMERCIAL",month,"210");source.read(probe,Set.of(a,b));assertThat(probe.supplierReviews().reviews()).hasSize(2);
    for(var review:probe.supplierReviews().reviews())jdbc.update("INSERT INTO lp_supplier_price_decision(scope_key,fingerprint,business_unit_type,oa_no,oa_form_item_id,period_month,pricing_date,org_code,material_code,price_type,decision,confirmed_by,confirmed_at) VALUES(?,?,'COMMERCIAL','MPP05-SCRAP',1,?,?,?,?,'SCRAP_PRICE','FALLBACK_HIGH','specific-consumer-reviewer',NOW())",review.scopeKey(),review.fingerprint(),month,probe.priceAsOfTime().toLocalDate(),review.scope().orgCode(),code);
    var f=a.family();var explicit=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(f.month(),f.businessUnitType(),code,null,"KG","CNY",TaxBasis.EXCLUDED,"S1"),"210",NonCuConditions.empty(),BigDecimal.ONE);var c=context("COMMERCIAL",month,"210");var candidates=operation.resolveCandidates(c,Set.of(a,b,explicit));assertThat(candidates.problems()).isEmpty();assertThat(candidates.values().get(a).selection().fact()).isEqualTo(candidates.values().get(b).selection().fact());
    var selected=new LinkedHashMap<MonthlyPriceRequest,MonthlyAdoptionPlan.Selection>();for(var r:List.of(b,explicit))selected.put(r,candidates.values().get(r).selection());
    var receipt=operation.adopt(c,new MonthlyAdoptionPlan(selected,List.of(new MonthlyAdoptionPlan.Use("root/shared220",b,BigDecimal.ONE),new MonthlyAdoptionPlan.Use("root/explicit210",explicit,BigDecimal.ONE)),Set.of()));var origin=new ObjectMapper().findAndRegisterModules().valueToTree(Map.of("supplierReviews",receipt.supplierReviews()));assertThat(origin.path("supplierReviews").size()).isEqualTo(1);assertThat(origin.path("supplierReviews").get(0).path("scope").path("orgCode").asText()).isEqualTo("220");
  }
  @Test void explicitWithdrawalAppliesBeforeSingleAndSamePriceScrapShortcuts() {
    String code=code(),month="2040-03"; rule(code,"COMMERCIAL",month,"S1","10",null); var req=request(code,"COMMERCIAL",month,"210");
    assertThat(source.read(context("COMMERCIAL",month,"210"),Set.of(req)).problems()).isEmpty();
    jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted) VALUES('COMMERCIAL',?,'fixture','SPEC','S1','S1',0,1,0)",code);
    assertThat(source.read(context("COMMERCIAL",month,"210"),Set.of(req)).values()).isEmpty();
    rule(code,"COMMERCIAL",month,"S2","10",null); var result=source.read(context("COMMERCIAL",month,"210"),Set.of(req));
    assertThat(result.problems()).isEmpty(); assertThat(result.values().get(req).selection().fact().evidence().json()).contains("S2");
  }
}
