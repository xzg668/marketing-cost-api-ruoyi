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
class MonthlyFixedPriceSourceIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mpp_fixed_source_test").withUsername("root").withPassword("mpp-fixed-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static JdbcTemplate jdbc;static MonthlyFixedPriceSource source;static AtomicInteger prepared=new AtomicInteger();
  static MonthlyMaterialPriceService operation;static MonthlyPriceDocumentCodec codec;static AtomicInteger sourceCalls=new AtomicInteger();
  @BeforeAll static void database() throws Exception {
    MYSQL.start();var original=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    var ds=new DelegatingDataSource(original){@Override public java.sql.Connection getConnection() throws java.sql.SQLException{
      var connection=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
        if(method.getName().equals("prepareStatement"))prepared.incrementAndGet();try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
      });
    }};jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/fixed-source-schema.sql")).execute(ds);
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql")){
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/fixed-monthly.sql");var result=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_fixed_source_test < /tmp/fixed-monthly.sql");assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
    }
    var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(MonthlyFixedSourceMapper.class);configuration.addMapper(MonthlySupplierInputMapper.class);configuration.addMapper(MonthlyPriceStorageMapper.class);configuration.addMapper(MonthlyPriceLinkMapper.class);var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
    var sql=new SqlSessionTemplate(factory.getObject());codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    source=new MonthlyFixedPriceSource(sql.getMapper(MonthlyFixedSourceMapper.class),sql.getMapper(MonthlySupplierInputMapper.class),new SupplierPriceSelectionService(null,null),codec);
    var storage=new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class));operation=new MonthlyMaterialPriceService(storage,new DataSourceTransactionManager(ds),codec,(context,requests,dependencies)->{sourceCalls.incrementAndGet();return source.read(context,requests);});
  }
  @AfterAll static void stop(){MYSQL.stop();}
  static Context context(){return new Context("COMMERCIAL","MPP05-FIXED",1L,"TOP",YearMonth.of(2026,10),LocalDateTime.of(2026,10,23,0,0),"fixture","210");}
  static MonthlyPriceRequest request(String code,String org){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",code,null,"件","CNY",TaxBasis.EXCLUDED,null),org,NonCuConditions.empty(),BigDecimal.ONE);}
  static String code(){return "MPP05-fixed-"+UUID.randomUUID();}
  static void price(String code,String org,String supplier,String value,String from,String to){jdbc.update("INSERT INTO lp_price_fixed_item(org_code,supplier_code,supplier_name,material_code,material_name,spec_model,unit,fixed_price,tax_included,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,source_batch_no,external_row_id,process_no,created_at,updated_at) VALUES(?,?,?,?,'fixture material','fixture specification','件',?,0,?,?,'COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM','fixture-day-1',?,'fixture-process-original',NOW(),NOW())",org,supplier,supplier,code,new BigDecimal(value),from,to,UUID.randomUUID().toString());}
  @Test void actualSourceSelectsLatestSupplierVersionKeepsZeroAndCapturesDailyRowIndependently() throws Exception {
    String code=code();price(code,"210","S1","9","2026-09-01","2027-01-01");price(code,"210","S1","0","2026-10-01","2027-01-01");price(code,"220","S1","50","2026-10-01","2027-01-01");
    var request=request(code,"210");var result=source.read(context(),Set.of(request));assertThat(result.problems()).isEmpty();var selected=result.values().get(request).selection();assertThat(selected.fact().price()).isZero();assertThat(selected.applicability().matches(request(code,"220"))).isFalse();
    var snapshot=new ObjectMapper().readTree(selected.fact().evidence().json());assertThat(snapshot.path("sourceKind").asText()).isEqualTo("PURCHASE_FIXED");assertThat(snapshot.path("source").path("selected").path("processNo").asText()).isEqualTo("fixture-process-original");
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?",code);assertThat(new ObjectMapper().readTree(selected.fact().evidence().json()).path("source").path("selected").path("fixedPrice").decimalValue()).isZero();
  }
  @Test void missingRatiosWaitAndAnExactConsumerDecisionEnablesFallbackWithoutLeakingReviewIntoPublicFact(){
    String code=code();price(code,"210","S1","10","2026-10-01","2027-01-01");price(code,"210","S2","20","2026-10-01","2027-01-01");var request=request(code,"210");var first=context();
    var waiting=source.read(first,Set.of(request));assertThat(waiting.values()).isEmpty();assertThat(waiting.problems().get(request).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);var review=first.supplierReviews().reviews().getFirst();assertThat(review.scope().orgCode()).isEqualTo("210");
    jdbc.update("INSERT INTO lp_supplier_price_decision(scope_key,fingerprint,business_unit_type,oa_no,oa_form_item_id,period_month,pricing_date,org_code,material_code,price_type,decision,confirmed_by,confirmed_at) VALUES(?,?,'COMMERCIAL','MPP05-FIXED',1,'2026-10','2026-10-23','210',?,'FIXED','FALLBACK_HIGH','specific-consumer-reviewer',NOW())",review.scopeKey(),review.fingerprint(),code);
    var next=context();var ready=source.read(next,Set.of(request));assertThat(ready.values().get(request).selection().fact().price()).isEqualByComparingTo("20");assertThat(next.supplierReviews().reviews().getFirst().status()).isEqualTo("FALLBACK_HIGH");
    assertThat(ready.values().get(request).selection().fact().evidence().json()).doesNotContain("specific-consumer-reviewer","scopeKey","confirmedBy","consumerOrg");
    var c=context();var other=new Context(c.businessUnitType(),"ANOTHER-OA",2L,c.productCode(),c.month(),c.priceAsOfTime(),c.actor(),c.consumerOrg());assertThat(source.read(other,Set.of(request)).problems().get(request).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);assertThat(other.supplierReviews().reviews().getFirst().scopeKey()).isNotEqualTo(review.scopeKey());
  }
  @Test void supplyRatiosUseTheExistingPolicyAndSamePeriodPriceConflictsBlock(){
    String code=code();price(code,"210","S1","10","2026-10-01","2027-01-01");price(code,"210","S2","20","2026-10-01","2027-01-01");
    jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted,source_batch_no) VALUES('COMMERCIAL',?,'fixture material','fixture spec','supplier one','S1',0.8,1,0,'fixture'),('COMMERCIAL',?,'fixture material','fixture spec','supplier two','S2',0.2,1,0,'fixture')",code,code);
    var request=request(code,"210");assertThat(source.read(context(),Set.of(request)).values().get(request).selection().fact().price()).isEqualByComparingTo("10");price(code,"210","S1","11","2026-10-01","2027-01-01");
    var conflict=source.read(context(),Set.of(request));assertThat(conflict.values()).isEmpty();assertThat(conflict.problems().get(request).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
  }
  @Test void thousandMaterialsReadInBoundedBatchesAndCandidateResolutionWritesNothing(){
    var requests=new LinkedHashSet<MonthlyPriceRequest>();
    for(int i=0;i<1000;i++){String code=code();price(code,"210","S1","10","2026-10-01","2027-01-01");requests.add(request(code,"210"));}
    long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_fixed_item",Long.class);prepared.set(0);var result=source.read(context(),requests);int count=prepared.get();
    assertThat(result.values()).hasSize(1000);assertThat(result.problems()).isEmpty();assertThat(count).as("four groups of <=256 fixed identities; all source/ratio/decision SQL included").isLessThanOrEqualTo(32);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_fixed_item",Long.class)).isEqualTo(before);
  }
  @Test void actualFixedAdoptionSurvivesDailyReplacementAndKeepsEachConsumersReviewContextSeparate() throws Exception {
    String code=code();price(code,"210","S1","10","2026-10-01","2027-01-01");price(code,"210","S2","20","2026-10-01","2027-01-01");var request=request(code,"210");var first=context();
    var waiting=operation.resolveCandidates(first,Set.of(request));assertThat(waiting.problems().get(request).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);var review=first.supplierReviews().reviews().getFirst();
    jdbc.update("INSERT INTO lp_supplier_price_decision(scope_key,fingerprint,business_unit_type,oa_no,oa_form_item_id,period_month,pricing_date,org_code,material_code,price_type,decision,confirmed_by,confirmed_at) VALUES(?,?,'COMMERCIAL','MPP05-FIXED',1,'2026-10','2026-10-23','210',?,'FIXED','FALLBACK_HIGH','original-consumer-approval',NOW())",review.scopeKey(),review.fingerprint(),code);
    var selected=operation.resolveCandidates(first,Set.of(request)).values().get(request).selection();
    var plan=new MonthlyAdoptionPlan(Map.of(request,selected),List.of(new MonthlyAdoptionPlan.Use("root",request,BigDecimal.ONE)),Set.of());var receipt=operation.adopt(first,plan);
    long id=receipt.plan().selections().get(request).monthlyPriceId();var firstOrigin=new ObjectMapper().findAndRegisterModules().valueToTree(Map.of("supplierReviews",receipt.supplierReviews()));assertThat(firstOrigin.path("supplierReviews").size()).isEqualTo(1);
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?",code);int calls=sourceCalls.get();var c=context();var second=new Context(c.businessUnitType(),"AFTER-DAILY-REPLACEMENT",2L,"OTHER-TOP",c.month(),c.priceAsOfTime(),c.actor(),c.consumerOrg());
    var reused=operation.resolveCandidates(second,Set.of(request)).values().get(request).selection();assertThat(reused.monthlyPriceId()).isEqualTo(id);assertThat(reused.fact().price()).isEqualByComparingTo("20");assertThat(sourceCalls.get()).isEqualTo(calls);assertThat(second.supplierReviews().reviews()).isEmpty();
    var another=operation.adopt(second,new MonthlyAdoptionPlan(Map.of(request,reused),List.of(new MonthlyAdoptionPlan.Use("root/other",request,new BigDecimal("2"))),Set.of()));
    var origin=new ObjectMapper().findAndRegisterModules().valueToTree(Map.of("supplierReviews",another.supplierReviews()));assertThat(origin.path("supplierReviews").size()).isZero();assertThat(second.oaNo()).isEqualTo("AFTER-DAILY-REPLACEMENT");
    assertThat(reused.fact().evidence().json()).doesNotContain("original-consumer-approval","scopeKey");assertThat(operation.readByIds("COMMERCIAL",Set.of(id)).get(id).fact()).isEqualTo(reused.fact());
  }
  @Test void explicitlyRequestedSupplierIsNotBlockedByAnotherSuppliersConflictingVersions(){
    String code=code();price(code,"210","S1","10","2026-10-01","2027-01-01");price(code,"210","S1","11","2026-10-01","2027-01-01");price(code,"210","S2","20","2026-10-01","2027-01-01");
    var family=request(code,"210").family();var explicit=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(family.month(),family.businessUnitType(),code,null,"件","CNY",TaxBasis.EXCLUDED,"S2"),"210",NonCuConditions.empty(),BigDecimal.ONE);
    var result=source.read(context(),Set.of(explicit));assertThat(result.problems()).isEmpty();assertThat(result.values().get(explicit).selection().fact().price()).isEqualByComparingTo("20");
  }
  @Test void fixedSourceDoesNotGuessUnitCurrencyOrTaxConversions(){
    String code=code();price(code,"210","S1","10","2026-10-01","2027-01-01");var c=context();
    for(var family:List.of(new MonthlyPriceFamilyKey(c.month(),c.businessUnitType(),code,null,"KG","CNY",TaxBasis.EXCLUDED,null),new MonthlyPriceFamilyKey(c.month(),c.businessUnitType(),code,null,"件","USD",TaxBasis.EXCLUDED,null),new MonthlyPriceFamilyKey(c.month(),c.businessUnitType(),code,null,"件","CNY",TaxBasis.INCLUDED,null))){
      var request=new MonthlyPriceRequest(family,"210",NonCuConditions.empty(),BigDecimal.ONE);var result=source.read(c,Set.of(request));assertThat(result.values()).isEmpty();assertThat(result.problems().get(request).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
    }
  }
  static String approvedMaterial(){
    String code=code();price(code,"210","S1","10","2026-10-01","2027-01-01");price(code,"210","S2","20","2026-10-01","2027-01-01");var probe=context();source.read(probe,Set.of(request(code,"210")));var review=probe.supplierReviews().reviews().getFirst();
    jdbc.update("INSERT INTO lp_supplier_price_decision(scope_key,fingerprint,business_unit_type,oa_no,oa_form_item_id,period_month,pricing_date,org_code,material_code,price_type,decision,confirmed_by,confirmed_at) VALUES(?,?,'COMMERCIAL','MPP05-FIXED',1,'2026-10','2026-10-23','210',?,'FIXED','FALLBACK_HIGH','specific-consumer-reviewer',NOW())",review.scopeKey(),review.fingerprint(),code);return code;
  }
  @Test void onePlanMayResolveSeveralBatchesAndItsReceiptKeepsOnlyTheReviewsForItsFinalFacts() throws Exception {
    var a=request(approvedMaterial(),"210");var b=request(approvedMaterial(),"210");var pruned=request(approvedMaterial(),"210");var c=context();
    var first=operation.resolveCandidates(c,Set.of(a)).values().get(a).selection();var second=operation.resolveCandidates(c,Set.of(b)).values().get(b).selection();operation.resolveCandidates(c,Set.of(pruned));assertThat(c.supplierReviews().reviews()).hasSize(3);
    var receipt=operation.adopt(c,new MonthlyAdoptionPlan(Map.of(a,first,b,second),List.of(new MonthlyAdoptionPlan.Use("root/a",a,BigDecimal.ONE),new MonthlyAdoptionPlan.Use("root/b",b,BigDecimal.ONE)),Set.of()));
    var origin=new ObjectMapper().findAndRegisterModules().valueToTree(Map.of("supplierReviews",receipt.supplierReviews()));var codes=new ArrayList<String>();origin.path("supplierReviews").forEach(review->codes.add(review.path("scope").path("materialCode").asText()));assertThat(codes).containsExactlyInAnyOrder(a.family().materialCode(),b.family().materialCode());assertThat(operation.lookup(Set.of(pruned)).missing()).contains(pruned);
  }
  @Test void retryClearsTheLosingPlansReviewsBeforeTheNextPlanAndStillPreservesItsOwnBatches() throws Exception {
    var a=request(approvedMaterial(),"210");var b=request(approvedMaterial(),"210");var discarded=request(approvedMaterial(),"210");var c=context();var attempts=new AtomicInteger();
    var receipt=new MonthlyAdoptionExecutor(operation).execute(c,remaining->{
      assertThat(c.supplierReviews().reviews()).isEmpty();if(attempts.getAndIncrement()==0){operation.resolveCandidates(c,Set.of(discarded));throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED,"controlled plan invalidation after source read");}
      var first=operation.resolveCandidates(c,Set.of(a)).values().get(a).selection();var second=operation.resolveCandidates(c,Set.of(b)).values().get(b).selection();return new MonthlyAdoptionPlan(Map.of(a,first,b,second),List.of(new MonthlyAdoptionPlan.Use("root/a",a,BigDecimal.ONE),new MonthlyAdoptionPlan.Use("root/b",b,BigDecimal.ONE)),Set.of());
    });assertThat(attempts.get()).isEqualTo(2);var origin=new ObjectMapper().findAndRegisterModules().valueToTree(Map.of("supplierReviews",receipt.supplierReviews()));assertThat(origin.path("supplierReviews").size()).isEqualTo(2);assertThat(origin.path("supplierReviews").toString()).doesNotContain(discarded.family().materialCode());
  }
  @Test void rereadingAScopeDoesNotEraseTheApprovalOfAnEarlierCandidateThatTheFinalPlanActuallyUses() throws Exception {
    var request=request(approvedMaterial(),"210");var c=context();var first=operation.resolveCandidates(c,Set.of(request)).values().get(request).selection();var approval=c.supplierReviews().reviews().getFirst();
    jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=21 WHERE material_code=? AND supplier_code='S2'",request.family().materialCode());var changed=operation.resolveCandidates(c,Set.of(request));assertThat(changed.problems().get(request).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);assertThat(c.supplierReviews().reviews().getFirst().status()).isEqualTo("PENDING");
    var receipt=operation.adopt(c,new MonthlyAdoptionPlan(Map.of(request,first),List.of(new MonthlyAdoptionPlan.Use("root",request,BigDecimal.ONE)),Set.of()));var origin=new ObjectMapper().findAndRegisterModules().valueToTree(Map.of("supplierReviews",receipt.supplierReviews()));
    assertThat(origin.path("supplierReviews").size()).isEqualTo(1);assertThat(origin.path("supplierReviews").get(0).path("fingerprint").asText()).isEqualTo(approval.fingerprint());assertThat(origin.path("supplierReviews").get(0).path("status").asText()).isEqualTo("FALLBACK_HIGH");assertThat(receipt.plan().selections().get(request).fact().price()).isEqualByComparingTo("20");
  }
}
