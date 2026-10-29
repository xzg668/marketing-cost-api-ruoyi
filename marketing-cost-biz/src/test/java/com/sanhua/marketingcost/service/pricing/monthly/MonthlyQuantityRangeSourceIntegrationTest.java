package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.impl.SupplierSupplyRatioResolveServiceImpl;
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
class MonthlyQuantityRangeSourceIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mpp_quantity_range_test").withUsername("root").withPassword("mpp-quantity-range-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static SupplierSupplyRatioResolveServiceImpl ratioService;static SupplierPreferredPriceSelector preferred;static java.util.concurrent.atomic.AtomicReference<Runnable> afterRatioRead=new java.util.concurrent.atomic.AtomicReference<>();
  static JdbcTemplate jdbc;static MonthlyQuantityRangeSource source;static AtomicInteger prepared=new AtomicInteger();
  static MonthlyMaterialPriceService operation;static MonthlyPriceDocumentCodec codec;static AtomicInteger sourceCalls=new AtomicInteger();
  @BeforeAll static void database() throws Exception {
    MYSQL.start();var original=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    var ds=new DelegatingDataSource(original){@Override public java.sql.Connection getConnection() throws java.sql.SQLException{
      var connection=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
        if(method.getName().equals("prepareStatement"))prepared.incrementAndGet();
        var value=invoke(connection,method,args);
        if(value instanceof java.sql.PreparedStatement statement && args!=null && args.length>0 && args[0] instanceof String query && query.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT") && query.toLowerCase(Locale.ROOT).contains("lp_supplier_supply_ratio"))return observeRatioRead(statement);
        return value;
      });
    }};jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/quantity-range-source-schema.sql")).execute(ds);
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql")){
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/fixed-monthly.sql");var result=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_quantity_range_test < /tmp/fixed-monthly.sql");assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
    }
    var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(MonthlyQuantityRangeSourceMapper.class);configuration.addMapper(SupplierSupplyRatioMapper.class);configuration.addMapper(MonthlySupplierInputMapper.class);configuration.addMapper(MonthlyPriceStorageMapper.class);configuration.addMapper(MonthlyPriceLinkMapper.class);var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
    var sql=new SqlSessionTemplate(factory.getObject());codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    ratioService=new SupplierSupplyRatioResolveServiceImpl(sql.getMapper(SupplierSupplyRatioMapper.class));preferred=new SupplierPreferredPriceSelector(ratioService);
    source=new MonthlyQuantityRangeSource(sql.getMapper(MonthlyQuantityRangeSourceMapper.class),sql.getMapper(MonthlySupplierInputMapper.class),preferred,codec);
    var storage=new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class));operation=new MonthlyMaterialPriceService(storage,new DataSourceTransactionManager(ds),codec,(context,requests,dependencies)->{sourceCalls.incrementAndGet();return source.read(context,requests);});
  }
  @AfterAll static void stop(){MYSQL.stop();}
  static Context context(){return new Context("COMMERCIAL","MPP05-QTY-RANGE",1L,"TOP",YearMonth.of(2026,10),LocalDateTime.of(2026,10,23,0,0),"fixture","210");}
  static String code(){return "MPP05-qty-"+UUID.randomUUID();}
  static MonthlyPriceRequest request(String code,String quantity){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",code,"SPEC","件","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),new BigDecimal(quantity));}
  static long range(String code,String supplier,String low,boolean lowInclusive,String high,boolean highInclusive,String price,String from){
    jdbc.update("INSERT INTO lp_price_range_item(org_code,supplier_code,supplier_name,material_code,material_name,spec_model,unit,range_low,lower_inclusive,range_high,upper_inclusive,range_basis,price_excl_tax,effective_from,effective_to,business_unit_type,created_at,updated_at,source_sheet_name,source_cell_ref,source_value_text,source_formula_expr,import_batch_no) VALUES('210',?,? ,?,'fixture quantity material','SPEC','件',?,?,?,?, 'QTY',?,?, '2027-01-01','COMMERCIAL',NOW(),NOW(),'数量原稿','H8',?,'original range formula','quantity-source-original')",supplier,supplier,code,low==null?null:new BigDecimal(low),lowInclusive?1:0,high==null?null:new BigDecimal(high),highInclusive?1:0,new BigDecimal(price),from,price);
    return jdbc.queryForObject("SELECT MAX(id) FROM lp_price_range_item WHERE material_code=?",Long.class,code);
  }
  static void ratio(String code,String supplier,String value){jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted,source_batch_no) VALUES('COMMERCIAL',?,'fixture material','SPEC',?,?,?,1,0,'quantity-ratio-original')",code,supplier,supplier,new BigDecimal(value));}
  static MonthlyAdoptionPlan plan(MonthlyPriceRequest request,MonthlyAdoptionPlan.Selection selection){return new MonthlyAdoptionPlan(Map.of(request,selection),List.of(new MonthlyAdoptionPlan.Use("root/quantity",request,BigDecimal.ONE)),Set.of());}
  @Test void overlappingSameSupplierVersionsUseOnlyTheActualWinningQuantityRegion(){
    String code=code();range(code,"S1","0",true,"100",true,"10","2026-01-01");range(code,"S1","50",false,"100",true,"20","2026-02-01");var a=request(code,"25");var c=context();var candidates=operation.resolveCandidates(c,Set.of(a));assertThat(candidates.problems()).isEmpty();var chosen=candidates.values().get(a).selection();assertThat(chosen.fact().price()).isEqualByComparingTo("10");assertThat(chosen.applicability().matches(request(code,"50"))).isTrue();assertThat(chosen.applicability().matches(request(code,"50.0000000000000001"))).isFalse();
    var saved=operation.adopt(c,plan(a,chosen));jdbc.update("UPDATE lp_price_range_item SET price_excl_tax=price_excl_tax+1 WHERE material_code=?",code);var at50=operation.resolveCandidates(c,Set.of(request(code,"50"))).values().get(request(code,"50")).selection();assertThat(at50.monthlyPriceId()).isEqualTo(saved.plan().selections().get(a).monthlyPriceId());assertThat(at50.fact().price()).isEqualByComparingTo("10");assertThat(operation.resolveCandidates(c,Set.of(request(code,"80"))).values().get(request(code,"80")).selection().fact().price()).isEqualByComparingTo("21");
  }
  @Test void oneChosenRowCanHaveDisjointDecisionRegionsAroundAnotherSuppliersBand(){
    String code=code();range(code,"S1","0",true,"100",true,"10","2026-01-01");range(code,"S2","40",true,"60",true,"20","2026-01-01");ratio(code,"S1","0.2");ratio(code,"S2","0.8");var a=request(code,"20");var c=context();var result=operation.resolveCandidates(c,Set.of(a));assertThat(result.problems()).isEmpty();var chosen=result.values().get(a).selection();assertThat(chosen.fact().price()).isEqualByComparingTo("10");assertThat(chosen.applicability().matches(request(code,"80"))).isTrue();assertThat(chosen.applicability().matches(request(code,"40"))).isFalse();assertThat(chosen.applicability().matches(request(code,"60"))).isFalse();
    var saved=operation.adopt(c,plan(a,chosen));int calls=sourceCalls.get();var at80=request(code,"80");assertThat(operation.resolveCandidates(c,Set.of(at80)).values().get(at80).selection().monthlyPriceId()).isEqualTo(saved.plan().selections().get(a).monthlyPriceId());assertThat(sourceCalls.get()).isEqualTo(calls);assertThat(operation.resolveCandidates(c,Set.of(request(code,"50"))).values().get(request(code,"50")).selection().fact().price()).isEqualByComparingTo("20");
  }
  @Test void exactOpenClosedEndpointsAndExplicitZeroAreNotApproximated(){
    String code=code();range(code,null,"1",true,"10",false,"0","2026-01-01");range(code,null,"10",false,"20",true,"5","2026-01-01");var c=context();for(String quantity:List.of("1","9.9999999999999999")){var r=request(code,quantity);assertThat(source.read(c,Set.of(r)).values().get(r).selection().fact().price()).isZero();}var at10=request(code,"10");var missing=operation.resolveCandidates(c,Set.of(at10));assertThat(missing.values()).isEmpty();assertThat(missing.problems().get(at10).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);assertThat(operation.lookup(Set.of(at10)).missing()).contains(at10);var at20=request(code,"20");assertThat(source.read(c,Set.of(at20)).values().get(at20).selection().fact().price()).isEqualByComparingTo("5");
  }
  @Test void existingPrimarySupplierPolicyWaitsForRatiosAndDoesNotUseTheNewFallbackReviewPolicy(){
    String code=code();range(code,"S1","0",true,"100",true,"10","2026-01-01");range(code,"S2","0",true,"100",true,"20","2026-01-01");var r=request(code,"1");var c=context();var waiting=operation.resolveCandidates(c,Set.of(r));assertThat(waiting.problems().get(r).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);assertThat(c.supplierReviews().reviews()).isEmpty();ratio(code,"S1","0.8");ratio(code,"S2","0.2");assertThat(operation.resolveCandidates(c,Set.of(r)).values().get(r).selection().fact().price()).isEqualByComparingTo("10");
  }
  @Test void adoptedQuantityFactRetainsFullRawRowsAndRatioEvidenceWhenCurrentRowsAreDeleted(){
    String code=code();range(code,"S1","0",true,"100",true,"10","2026-01-01");range(code,"S2","0",true,"100",true,"20","2026-01-01");ratio(code,"S1","0.8");ratio(code,"S2","0.2");var r=request(code,"1");var c=context();var chosen=operation.resolveCandidates(c,Set.of(r)).values().get(r).selection();assertThat(chosen.fact().evidence().json()).contains("数量原稿","H8","original range formula","quantity-source-original","quantity-ratio-original");var saved=operation.adopt(c,plan(r,chosen));long id=saved.plan().selections().get(r).monthlyPriceId();jdbc.update("DELETE FROM lp_price_range_item WHERE material_code=?",code);jdbc.update("DELETE FROM lp_supplier_supply_ratio WHERE material_code=?",code);int calls=sourceCalls.get();assertThat(operation.resolveCandidates(c,Set.of(r)).values().get(r).selection().monthlyPriceId()).isEqualTo(id);assertThat(sourceCalls.get()).isEqualTo(calls);assertThat(operation.readByIds("COMMERCIAL",Set.of(id)).get(id).fact().evidence()).isEqualTo(chosen.fact().evidence());
  }
  @Test void columnTaxPriorityAndBasisAreExplicitWithoutGuessingACurrencyOrConversion(){
    String code=code();long id=range(code,null,"0",true,"100",true,"8","2026-01-01");jdbc.update("UPDATE lp_price_range_item SET price_incl_tax=9 WHERE id=?",id);var original=request(code,"1");var f=original.family();var included=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(f.month(),f.businessUnitType(),code,"SPEC","件","CNY",TaxBasis.INCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);var result=source.read(context(),Set.of(included));assertThat(result.problems()).isEmpty();assertThat(result.values().get(included).selection().fact().price()).isEqualByComparingTo("9");assertThat(source.read(context(),Set.of(original)).problems().get(original).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
    var usd=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(f.month(),f.businessUnitType(),code,"SPEC","件","USD",TaxBasis.INCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);assertThat(source.read(context(),Set.of(usd)).problems().get(usd).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
  }
  @Test void thousandQuantityMaterialsUseBoundedBatchReadsAndDoNotWrite(){
    var requests=new LinkedHashSet<MonthlyPriceRequest>();for(int i=0;i<1000;i++){String code=code();range(code,"S1","0",true,"100",true,"10","2026-01-01");requests.add(request(code,"1"));}long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_range_item",Long.class);prepared.set(0);var result=source.read(context(),requests);int count=prepared.get();assertThat(result.values()).hasSize(1000);assertThat(result.problems()).isEmpty();assertThat(count).as("four batches <=20 each; no per-node reads").isLessThanOrEqualTo(80);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_range_item",Long.class)).isEqualTo(before);
  }
  static Object invoke(Object target,java.lang.reflect.Method method,Object[] arguments) throws Throwable {try{return method.invoke(target,arguments);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}}
  static java.sql.PreparedStatement observeRatioRead(java.sql.PreparedStatement statement){
    return (java.sql.PreparedStatement)java.lang.reflect.Proxy.newProxyInstance(MonthlyQuantityRangeSourceIntegrationTest.class.getClassLoader(),new Class<?>[]{java.sql.PreparedStatement.class},(proxy,method,args)->{
      var value=invoke(statement,method,args);if(value instanceof java.sql.ResultSet result)return java.lang.reflect.Proxy.newProxyInstance(MonthlyQuantityRangeSourceIntegrationTest.class.getClassLoader(),new Class<?>[]{java.sql.ResultSet.class},(rowProxy,rowMethod,rowArgs)->{
        var rowValue=invoke(result,rowMethod,rowArgs);if(rowMethod.getName().equals("next") && Boolean.FALSE.equals(rowValue)){var after=afterRatioRead.getAndSet(null);if(after!=null)after.run();}return rowValue;
      });return value;
    });
  }
  @Test void ratioSelectionAndEvidenceUseTheSameCapturedRowsAcrossARealConcurrentUpdate() throws Exception {
    String code=code();range(code,"S1","0",true,"100",true,"10","2026-01-01");range(code,"S2","0",true,"100",true,"20","2026-01-01");ratio(code,"S1","0.8");ratio(code,"S2","0.2");var r=request(code,"1");var flips=new AtomicInteger();
    afterRatioRead.set(()->{var independent=new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword()));independent.update("UPDATE lp_supplier_supply_ratio SET supply_ratio=CASE supplier_code WHEN 'S1' THEN 0.1 ELSE 0.9 END WHERE material_code=?",code);flips.incrementAndGet();});
    MonthlyMaterialPrices.Candidates result;try{result=source.read(context(),Set.of(r));}finally{afterRatioRead.set(null);}assertThat(flips.get()).isEqualTo(1);assertThat(result.problems()).isEmpty();var chosen=result.values().get(r).selection();assertThat(chosen.fact().price()).isEqualByComparingTo("10");var snapshot=new ObjectMapper().readTree(chosen.fact().evidence().json());
    var savedRatios=new HashMap<String,BigDecimal>();snapshot.path("source").path("ratios").forEach(row->savedRatios.put(row.path("supplierCode").asText(),new BigDecimal(row.path("supplyRatio").asText())));assertThat(savedRatios.get("S1")).isEqualByComparingTo("0.8");assertThat(savedRatios.get("S2")).isEqualByComparingTo("0.2");assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE material_code=? AND supplier_code='S2'",BigDecimal.class,code)).isEqualByComparingTo("0.9");assertThat(source.read(context(),Set.of(r)).values().get(r).selection().fact().price()).isEqualByComparingTo("20");
  }
  @Test void legacySupplierInputNormalizationDoesNotChangeTheExactMonthlyMaterialIdentity() throws Exception {
    String code="MPP05 QTY "+UUID.randomUUID();String ratioCode=com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(code);range(code,"S1","0",true,"100",true,"10","2026-01-01");range(code,"S2","0",true,"100",true,"20","2026-01-01");ratio(ratioCode,"S1","0.8");ratio(ratioCode,"S2","0.2");
    var actualRows=jdbc.query("SELECT * FROM lp_price_range_item WHERE material_code=? ORDER BY effective_from DESC,created_at DESC,id DESC",org.springframework.jdbc.core.BeanPropertyRowMapper.newInstance(com.sanhua.marketingcost.entity.PriceRangeItem.class),code);var legacy=preferred.select(actualRows,"COMMERCIAL",code,"fixture","SPEC",context().priceAsOfTime().toLocalDate(),com.sanhua.marketingcost.entity.PriceRangeItem::getSupplierName,com.sanhua.marketingcost.entity.PriceRangeItem::getSupplierCode);assertThat(legacy.failed()).isFalse();assertThat(legacy.row().getPriceExclTax()).isEqualByComparingTo("10");
    var r=request(code,"1");var result=operation.resolveCandidates(context(),Set.of(r));assertThat(result.problems()).isEmpty();var chosen=result.values().get(r).selection();assertThat(chosen.fact().price()).isEqualByComparingTo("10");assertThat(r.family().materialCode()).isEqualTo(code);assertThat(chosen.fact().evidence().json()).contains(ratioCode);operation.adopt(context(),plan(r,chosen));var distinct=request(ratioCode,"1");assertThat(operation.lookup(Set.of(distinct)).missing()).contains(distinct);
  }
  @Test void capturedPrimaryPolicyMatchesTheExistingSqlPolicyForTiesDatesAndInactiveRows(){
    String code=code();range(code,"S1","0",true,"100",true,"10","2026-01-01");range(code,"S2","0",true,"100",true,"20","2026-01-01");ratio(code,"S1","0.5");ratio(code,"S2","0.5");jdbc.update("UPDATE lp_supplier_supply_ratio SET updated_at='2026-01-01 00:00:00' WHERE material_code=?",code);
    var actualRows=jdbc.query("SELECT * FROM lp_price_range_item WHERE material_code=? ORDER BY effective_from DESC,created_at DESC,id DESC",org.springframework.jdbc.core.BeanPropertyRowMapper.newInstance(com.sanhua.marketingcost.entity.PriceRangeItem.class),code);var r=request(code,"1");
    for(int step=0;step<3;step++){
      if(step==1)jdbc.update("UPDATE lp_supplier_supply_ratio SET updated_at='2026-02-01 00:00:00' WHERE material_code=? AND supplier_code='S1'",code);
      if(step==2)jdbc.update("UPDATE lp_supplier_supply_ratio SET effective_from='2026-11-01' WHERE material_code=? AND supplier_code='S1'",code);
      var old=preferred.select(actualRows,"COMMERCIAL",code,"fixture","SPEC",context().priceAsOfTime().toLocalDate(),com.sanhua.marketingcost.entity.PriceRangeItem::getSupplierName,com.sanhua.marketingcost.entity.PriceRangeItem::getSupplierCode);assertThat(old.failed()).isFalse();var result=source.read(context(),Set.of(r));assertThat(result.problems()).isEmpty();assertThat(result.values().get(r).selection().fact().price()).isEqualByComparingTo(old.row().getPriceExclTax());assertThat(old.row().getSupplierCode()).isEqualTo(step==1?"S1":"S2");
    }
    jdbc.update("UPDATE lp_supplier_supply_ratio SET is_active=0 WHERE material_code=? AND supplier_code='S2'",code);assertThat(source.read(context(),Set.of(r)).problems().get(r).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);
  }
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void supplierLookupRetainsTheExistingDatabaseCollationWithoutFoldingMonthlyCodes(boolean accented){
    String suffix=UUID.randomUUID().toString();String code="MPP05-Cas"+(accented?"\u00e9":"e")+"Mat-"+suffix;String ratioCode="MPP05-CASEMAT-"+suffix.toUpperCase(Locale.ROOT);range(code,"S1","0",true,"100",true,"10","2026-01-01");range(code,"S2","0",true,"100",true,"20","2026-01-01");ratio(ratioCode,"S1","0.8");ratio(ratioCode,"S2","0.2");
    var actualRows=jdbc.query("SELECT * FROM lp_price_range_item WHERE material_code=? ORDER BY effective_from DESC,created_at DESC,id DESC",org.springframework.jdbc.core.BeanPropertyRowMapper.newInstance(com.sanhua.marketingcost.entity.PriceRangeItem.class),code);var old=preferred.select(actualRows,"COMMERCIAL",code,"fixture","SPEC",context().priceAsOfTime().toLocalDate(),com.sanhua.marketingcost.entity.PriceRangeItem::getSupplierName,com.sanhua.marketingcost.entity.PriceRangeItem::getSupplierCode);assertThat(old.failed()).isFalse();assertThat(old.row().getPriceExclTax()).isEqualByComparingTo("10");
    var r=request(code,"1");var result=operation.resolveCandidates(context(),Set.of(r));assertThat(result.problems()).isEmpty();var chosen=result.values().get(r).selection();assertThat(chosen.fact().price()).isEqualByComparingTo("10");assertThat(r.family().materialCode()).isEqualTo(code);operation.adopt(context(),plan(r,chosen));assertThat(operation.lookup(Set.of(request(ratioCode,"1"))).missing()).contains(request(ratioCode,"1"));
  }
  @Test void existingBulkSupplierLookupKeepsItsDatabaseBusinessUnitMatching(){
    String code=code();ratio(code,"S1","1");var result=ratioService.resolveAll("commercial",Set.of(code),context().priceAsOfTime().toLocalDate());assertThat(result.get(code).isMatched()).isTrue();assertThat(result.get(code).getSupplierCode()).isEqualTo("S1");
  }
  @Test void singleSupplierQuantityPriceDoesNotDependOnRatio() {
    String code=code(); range(code,"S1","0",true,"100",true,"10","2026-01-01"); var req=request(code,"10");
    assertThat(source.read(context(),Set.of(req)).problems()).isEmpty(); ratio(code,"S1","0");
    var result=source.read(context(),Set.of(req)); assertThat(result.problems()).isEmpty(); assertThat(result.values()).containsKey(req);
  }
}
