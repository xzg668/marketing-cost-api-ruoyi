package com.sanhua.marketingcost.service.supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.SupplierSupplyRatioImportService;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.materialshape.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.Context;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.*;

/** Real import transactions and production scalar/batch mappers feed each distinct selection policy. */
@Tag("integration")
class SupplierSupplyRatioSelectionIntegrationTest {
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
      System.getProperty("mpp.mysql.image", "mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mrp03_selection_test").withUsername("root").withPassword("mrp03-test-only")
      .withConfigurationOverride("monthly-pricing/mysql");
  static HikariDataSource ds; static JdbcTemplate jdbc; static SqlSessionTemplate sql;
  static AnnotationConfigApplicationContext spring; static SupplierSupplyRatioImportService imports;
  static SupplierPriceSelectionService prices; static SupplierSupplyRatioResolveServiceImpl primary;
  static SupplierPreferredPriceSelector preferred; static SupplierRatioShapeResolverImpl shapes;
  static MonthlySupplierInputMapper inputs; static MonthlyFixedPriceSource fixed;
  static MonthlyMaterialPriceService monthly;
  static final com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService workbench = mock(com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService.class);
  @Configuration @EnableTransactionManagement static class Config {
    @Bean SupplierPriceImportRetry retry() {
      return new SupplierPriceImportRetry(sql.getMapper(SupplierPriceDecisionMapper.class),
          new SupplierPriceReviewWorkflow(workbench,sql.getMapper(SupplierPriceDecisionMapper.class),null));
    }
    @Bean PlatformTransactionManager transactionManager() { return new DataSourceTransactionManager(ds); }
    @Bean SupplierSupplyRatioImportService imports(ApplicationEventPublisher events) {
      return new SupplierSupplyRatioImportServiceImpl(sql.getMapper(SupplierSupplyRatioMapper.class),
          new SupplierSupplyRatioWorkbookParserImpl(), events, sql.getMapper(BusinessChangeLogMapper.class),
          new SupplierSupplyRatioImportLock(ds), new ObjectMapper());
    }
  }
  @BeforeAll static void database() throws Exception {
    MYSQL.start(); ds=new HikariDataSource(); ds.setJdbcUrl(MYSQL.getJdbcUrl());
    ds.setUsername(MYSQL.getUsername()); ds.setPassword(MYSQL.getPassword()); jdbc=new JdbcTemplate(ds);
    assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/fixed-source-schema.sql")).execute(ds);
    String logSchema=new ClassPathResource("supplier-ratio/schema.sql").getContentAsString(StandardCharsets.UTF_8);
    jdbc.execute(logSchema.substring(logSchema.indexOf("CREATE TABLE `lp_business_change_log`")));
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql")) {
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/monthly.sql");
      var result=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mrp03_selection_test < /tmp/monthly.sql");
      assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
    }
    var config=new com.baomidou.mybatisplus.core.MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
    for(var type:List.of(SupplierSupplyRatioMapper.class, BusinessChangeLogMapper.class,
        SupplierPriceDecisionMapper.class, MonthlySupplierInputMapper.class, MonthlyFixedSourceMapper.class,
        MonthlyPriceStorageMapper.class, MonthlyPriceLinkMapper.class)) config.addMapper(type);
    var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();
    global.setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig());
    global.setMetaObjectHandler(new com.sanhua.marketingcost.config.MetaObjectHandlerConfig());
    com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,global);
    var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
    factory.setDataSource(ds); factory.setConfiguration(config); sql=new SqlSessionTemplate(factory.getObject());
    spring=new AnnotationConfigApplicationContext(Config.class); imports=spring.getBean(SupplierSupplyRatioImportService.class);
    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(imports)).isTrue();
    var mapper=sql.getMapper(SupplierSupplyRatioMapper.class);
    prices=new SupplierPriceSelectionService(mapper,sql.getMapper(SupplierPriceDecisionMapper.class));
    primary=new SupplierSupplyRatioResolveServiceImpl(mapper); preferred=new SupplierPreferredPriceSelector(primary);
    shapes=new SupplierRatioShapeResolverImpl(mapper); inputs=sql.getMapper(MonthlySupplierInputMapper.class);
    var codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    fixed=new MonthlyFixedPriceSource(sql.getMapper(MonthlyFixedSourceMapper.class),inputs,prices,codec);
    monthly=new MonthlyMaterialPriceService(new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,
        sql.getMapper(MonthlyPriceLinkMapper.class)),new DataSourceTransactionManager(ds),codec,
        (context,requests,dependencies)->fixed.read(context,requests));
  }
  @AfterAll static void stop() { if(spring!=null)spring.close(); if(ds!=null)ds.close(); MYSQL.stop(); }
  @BeforeEach void auth() {
    var a=new UsernamePasswordAuthenticationToken("mrp03-finance",null);
    a.setDetails(Map.of("businessUnitType","COMMERCIAL")); SecurityContextHolder.getContext().setAuthentication(a);
  }
  @AfterEach void logout() { SecurityContextHolder.clearContext(); }
  static String code() { return "000MRP03-"+UUID.randomUUID(); }
  static SupplierSupplyRatioExcelRow row(String code,String supplier,String ratio) {
    var r=new SupplierSupplyRatioExcelRow(); r.setRowNo(2); r.setMaterialCode(code); r.setMaterialName("fixture");
    r.setSpecModel("SPEC"); r.setUnit("件"); r.setMaterialShape(supplier.equals("000A")?"制造件":"采购件");
    r.setSupplierCode(supplier); r.setSupplierName(supplier); r.setSupplyRatio(ratio==null?null:new BigDecimal(ratio)); return r;
  }
  static void save(String unit,SupplierSupplyRatioExcelRow... rows) {
    var previous=SecurityContextHolder.getContext().getAuthentication();
    var auth=new UsernamePasswordAuthenticationToken("mrp03-finance",null); auth.setDetails(Map.of("businessUnitType",unit));
    SecurityContextHolder.getContext().setAuthentication(auth);
    try { imports.importRows(List.of(rows),"mrp03.xlsx",unit,"mrp03-finance"); }
    finally { SecurityContextHolder.getContext().setAuthentication(previous); }
  }
  static SupplierPriceScope scope(String code,String org,String month,String unit) {
    return new SupplierPriceScope(unit,"MRP03",1L,month,YearMonth.parse(month).atDay(1),org,code,"FIXED");
  }
  static SupplierPriceScope scope(String code) { return scope(code,"210","2026-10","COMMERCIAL"); }
  static SupplierPriceCandidate candidate(String supplier,String value) {
    return new SupplierPriceCandidate(supplier,supplier,supplier,new BigDecimal(value),LocalDate.of(2026,1,1),null);
  }
  static MaterialQuoteShapeResolution shapeRequest(String code,String unit,String month) {
    return new MaterialQuoteShapeResolution(unit,code,month,"制造件",QuoteMaterialShape.MANUFACTURE,null,
        MaterialQuoteShapeSource.SUPPLIER_RATIO,81L,"fixture-policy","{}","{}","fixture");
  }
  static MonthlyPriceRequest request(String code,String org,String month) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),"COMMERCIAL",code,null,"件","CNY",
        TaxBasis.EXCLUDED,null),org,NonCuConditions.empty(),BigDecimal.ONE);
  }
  static Context context(String month) {
    return new Context("COMMERCIAL","MRP03",1L,"TOP",YearMonth.parse(month),YearMonth.parse(month).atDay(1).atStartOfDay(),"fixture","210");
  }
  static void price(String code,String supplier,String value) {
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,supplier_code,supplier_name,material_code,material_name,spec_model,unit,fixed_price,tax_included,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,source_batch_no,external_row_id,created_at,updated_at) VALUES('210',?,?,?,'fixture','SPEC','件',?,0,'2026-01-01','2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM','fixed-before-ratio',?,NOW(),NOW())",supplier,supplier,code,new BigDecimal(value),UUID.randomUUID().toString());
  }
  static List<SupplierPriceCandidate> candidates() { return List.of(candidate("000A","10"),candidate("000B","30"),candidate("000C","20")); }
  static void assertChoices(String code,String expected,QuoteMaterialShape shape) {
    assertThat(prices.select(scope(code),candidates()).candidate().supplierCode()).isEqualTo(expected);
    assertThat(primary.resolve("COMMERCIAL",code,null,null,LocalDate.of(2026,10,1)).getSupplierCode()).isEqualTo(expected);
    var resolved=shapes.resolve(shapeRequest(code,"COMMERCIAL","2026-10"));
    assertThat(resolved.selectedSupplierCode()).isEqualTo(expected); assertThat(resolved.effectiveShape()).isEqualTo(shape);
    var req=request(code,"210","2026-10"); var result=fixed.read(context("2026-10"),Set.of(req));
    assertThat(result.problems()).isEmpty();
    assertThat(result.values().get(req).selection().fact().price()).isEqualByComparingTo(
        candidates().stream().filter(c->c.supplierCode().equals(expected)).findFirst().orElseThrow().unitPrice());
  }
  @Test void addingOnlyCStillChoosesUnchangedAWithTheCompleteCurrentSet() {
    String code=code(); save("COMMERCIAL",row(code,"000A","0.7"),row(code,"000B","0.3"));
    var before=prices.activeRatios(scope(code)); save("COMMERCIAL",row(code,"000C","0.2"));
    candidates().forEach(c->price(code,c.supplierCode(),c.unitPrice().toPlainString())); assertChoices(code,"000A",QuoteMaterialShape.MANUFACTURE);
    assertThat(prices.activeRatios(scope(code))).extracting(SupplierSupplyRatio::getId).containsAll(before.stream().map(SupplierSupplyRatio::getId).toList()).hasSize(3);
    assertThat(inputs.ratios("COMMERCIAL",LocalDate.of(2026,10,1),Set.of(code))).extracting(SupplierSupplyRatio::getId)
        .containsExactlyElementsOf(prices.activeRatios(scope(code)).stream().map(SupplierSupplyRatio::getId).toList());
  }
  @Test void changingOnlyAAndBLeavesUnchangedCEligibleForEveryPolicy() {
    String code=code(); save("COMMERCIAL",row(code,"000A","0.7"),row(code,"000B","0.2"),row(code,"000C","0.5"));
    save("COMMERCIAL",row(code,"000A","0.3"),row(code,"000B","0.2"));
    candidates().forEach(c->price(code,c.supplierCode(),c.unitPrice().toPlainString())); assertChoices(code,"000C",QuoteMaterialShape.OUTSOURCE);
  }
  @Test void zeroRetiresTheOldPositiveBeforeSingleSamePriceAndPreferredShortcuts() {
    String code=code(); save("COMMERCIAL",row(code,"000A","0.2"),row(code,"000B","0.8"));
    long old=primary.resolve("COMMERCIAL",code,null,null,LocalDate.of(2026,10,1)).getId(); save("COMMERCIAL",row(code,"000B","0"));
    assertThat(prices.select(scope(code),List.of(candidate("000B","99"))).candidate()).isNull();
    assertThat(prices.select(scope(code),List.of(candidate("000A","10"),candidate("000B","10"))).candidate().supplierCode()).isEqualTo("000A");
    var result=preferred.select(List.of(candidate("000B","99")),"COMMERCIAL",code,null,null,LocalDate.of(2026,10,1),SupplierPriceCandidate::supplierName,SupplierPriceCandidate::supplierCode);
    assertThat(result.row()).isNull();
    assertThat(primary.resolve("COMMERCIAL",code,null,null,LocalDate.of(2026,10,1)).getSupplierCode()).isEqualTo("000A");
    assertThat(shapes.resolve(shapeRequest(code,"COMMERCIAL","2026-10")).selectedSupplierCode()).isEqualTo("000A");
    assertThat(prices.activeRatios(scope(code))).extracting(SupplierSupplyRatio::getId).doesNotContain(old);
    assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE id=?",BigDecimal.class,old)).isEqualByComparingTo("0.8");
    price(code,"000B","99"); var req=request(code,"210","2026-10"); assertThat(fixed.read(context("2026-10"),Set.of(req)).values()).isEmpty();
  }
  @Test void nullAndMissingRemainEligibleButManualFallbackCanNeverRestoreZero() {
    String code=code(); save("COMMERCIAL",row(code,"000A",null),row(code,"000B","0"));
    assertThat(prices.select(scope(code),List.of(candidate("000A","10"))).candidate()).isNotNull();
    var waiting=prices.select(scope(code),candidates()); assertThat(waiting.candidate()).isNull();
    assertThat(waiting.review().candidates()).extracting(c->c.price().supplierCode()).containsExactly("000A","000C");
    var r=waiting.review();
    jdbc.update("INSERT INTO lp_supplier_price_decision(scope_key,fingerprint,business_unit_type,oa_no,oa_form_item_id,period_month,pricing_date,org_code,material_code,price_type,decision,confirmed_by,confirmed_at) VALUES(?,?,'COMMERCIAL','MRP03',1,'2026-10','2026-10-01','210',?,'FIXED','FALLBACK_HIGH','finance',NOW())",r.scopeKey(),r.fingerprint(),code);
    assertThat(prices.select(scope(code),candidates()).candidate().supplierCode()).isEqualTo("000C");
    assertThat(prices.select(scope(code,"220","2026-10","COMMERCIAL"),candidates()).candidate()).isNull();
    save("COMMERCIAL",row(code,"000B",null)); assertThat(prices.select(scope(code),candidates()).candidate()).isNull();
    assertThat(prices.select(scope(code),candidates()).review().candidates()).hasSize(3);
  }
  @Test void equalRatiosKeepPriceTiePrimaryTieAndShapeTiePoliciesDistinct() {
    String code=code(); save("COMMERCIAL",row(code,"000A","0.5"),row(code,"000B","0.5"));
    assertThat(prices.select(scope(code),List.of(candidate("000A","99"),candidate("000B","10"))).candidate().supplierCode()).isEqualTo("000A");
    assertThat(primary.resolve("COMMERCIAL",code,null,null,LocalDate.of(2026,10,1)).getSupplierCode()).isEqualTo("000B");
    assertThat(shapes.resolve(shapeRequest(code,"COMMERCIAL","2026-10")).blockingReason()).contains("并列");
  }
  @Test void undatedImportsWorkAcrossMonthsAndOrganizationsWhileLegacyBoundsAndBusinessUnitsRemainIsolated() {
    String code=code(); save("COMMERCIAL",row(code,"000A","0.4"),row(code,"000B","0.9"),row(code,"000C","0.8"));
    jdbc.update("UPDATE lp_supplier_supply_ratio SET effective_from='2026-01-01',effective_to='2026-09-30' WHERE material_code=? AND supplier_code='000B'",code);
    jdbc.update("UPDATE lp_supplier_supply_ratio SET effective_from='2027-01-01' WHERE material_code=? AND supplier_code='000C'",code);
    save("HOUSEHOLD",row(code,"000A","0"),row(code,"000B","1"));
    for(String month:List.of("2026-10","2026-11")) for(String org:List.of("210","220")) {
      var s=scope(code,org,month,"COMMERCIAL"); assertThat(prices.activeRatios(s)).extracting(SupplierSupplyRatio::getSupplierCode).containsExactly("000A");
      assertThat(prices.prepareRatios("COMMERCIAL",Set.of(code),s.pricingDate()).get(code)).extracting(SupplierSupplyRatio::getSupplierCode).containsExactly("000A");
      assertThat(inputs.ratios("COMMERCIAL",s.pricingDate(),Set.of(code))).extracting(SupplierSupplyRatio::getSupplierCode).containsExactly("000A");
      assertThat(primary.resolve("COMMERCIAL",code,null,null,YearMonth.parse(month).atDay(1)).getSupplierCode()).isEqualTo("000A");
      assertThat(shapes.resolve(shapeRequest(code,"COMMERCIAL",month)).selectedSupplierCode()).isEqualTo("000A");
    }
    assertThat(prices.select(scope(code,"220","2026-11","HOUSEHOLD"),candidates()).candidate().supplierCode()).isEqualTo("000B");
    assertThatThrownBy(()->shapes.resolve(shapeRequest(code,"HOUSEHOLD","2026-11"))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void noPositiveRelationshipFallsBackToU9ShapeWithoutRevivingTheRetiredPrimary() {
    String code=code(); save("COMMERCIAL",row(code,"000B","1")); save("COMMERCIAL",row(code,"000B","0"),row(code,"000C",null));
    var main=primary.resolve("COMMERCIAL",code,null,null,LocalDate.of(2026,10,1)); assertThat(main.isMatched()).isFalse(); assertThat(main.getCurrentRelations()).hasSize(2);
    var shape=shapes.resolve(shapeRequest(code,"COMMERCIAL","2026-10")); assertThat(shape.effectiveShape()).isEqualTo(QuoteMaterialShape.MANUFACTURE); assertThat(shape.selectedRatioRecordId()).isNull();
  }
  @Test void adoptedMonthlyFactAndEvidenceSurviveRatioChangesWhileNextMonthUsesTheNewSet() {
    String code=code(); save("COMMERCIAL",row(code,"000A","0.8"),row(code,"000B","0.2")); price(code,"000A","10"); price(code,"000B","30");
    var req=request(code,"210","2026-10"); var c=context("2026-10");
    var selected=monthly.resolveCandidates(c,Set.of(req)).values().get(req).selection();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,code)).isZero();
    var receipt=monthly.adopt(c,new MonthlyAdoptionPlan(Map.of(req,selected),List.of(new MonthlyAdoptionPlan.Use("root",req,BigDecimal.ONE)),Set.of()));
    var adopted=receipt.plan().selections().get(req); var before=jdbc.queryForMap("SELECT * FROM lp_material_monthly_price WHERE id=?",adopted.monthlyPriceId());
    var sourceBefore=jdbc.queryForList("SELECT * FROM lp_price_fixed_item WHERE material_code=? ORDER BY id",code);
    save("COMMERCIAL",row(code,"000A","0"));
    var reused=monthly.resolveCandidates(context("2026-10"),Set.of(req)).values().get(req).selection();
    assertThat(reused.monthlyPriceId()).isEqualTo(adopted.monthlyPriceId()); assertThat(reused.fact()).isEqualTo(adopted.fact());
    assertThat(monthly.readByIds("COMMERCIAL",Set.of(adopted.monthlyPriceId())).get(adopted.monthlyPriceId()).fact()).isEqualTo(adopted.fact());
    assertThat(jdbc.queryForMap("SELECT * FROM lp_material_monthly_price WHERE id=?",adopted.monthlyPriceId())).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_fixed_item WHERE material_code=? ORDER BY id",code)).isEqualTo(sourceBefore);
    var next=request(code,"210","2026-11"); assertThat(monthly.resolveCandidates(context("2026-11"),Set.of(next)).values().get(next).selection().fact().price()).isEqualByComparingTo("30");
  }
  @Test void committedImportRetriesOnlyItsWaitingProductAndKeepsWithdrawnPricesRetryable() {
    String code=code(); String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    LocalDate date=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingDate();
    save("COMMERCIAL",row(code,"000A","1")); price(code,"000A","10");
    var scope=new SupplierPriceScope("COMMERCIAL","MRP03-RETRY",9003L,month,date,"210",code,"FIXED");
    String key=SupplierPriceSelectionService.scopeKey(scope);
    jdbc.update("INSERT INTO lp_supplier_price_decision(scope_key,fingerprint,business_unit_type,oa_no,oa_form_item_id,period_month,pricing_date,org_code,material_code,price_type,decision,confirmed_by,confirmed_at) VALUES(?,'fixture','COMMERCIAL','MRP03-RETRY',9003,?,?, '210',?,'FIXED','WAIT_IMPORT','finance',NOW())",key,month,date,code);
    reset(workbench);
    when(workbench.generate(eq("MRP03-RETRY"),eq(9003L),any())).thenAnswer(invocation -> {
      // This boundary stands for the quotation workbench; its actual supplier choice still uses SQL.
      var selected=prices.select(scope,List.of(candidate("000A","10")));
      var result=new com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse();
      if(selected.candidate()==null) {
        var gap=new com.sanhua.marketingcost.entity.PricePrepareGap(); gap.setMaterialCode(code); gap.setPriceType("FIXED");
        result.setGaps(new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse(1,List.of(gap)));
      }
      return result;
    });
    save("COMMERCIAL",row(code,"000A","0"));
    assertThat(sql.getMapper(SupplierPriceDecisionMapper.class).latest(key).getRetryStatus()).isEqualTo("WAITING");
    verify(workbench,times(1)).generate(eq("MRP03-RETRY"),eq(9003L),any());
    save("COMMERCIAL",row(code(),"000A","1")); save("COMMERCIAL",row(code,"000A","0"));
    verify(workbench,times(1)).generate(eq("MRP03-RETRY"),eq(9003L),any());
    save("COMMERCIAL",row(code,"000A","1"));
    assertThat(sql.getMapper(SupplierPriceDecisionMapper.class).latest(key).getRetryStatus()).isEqualTo("RESOLVED");
    verify(workbench,times(2)).generate(eq("MRP03-RETRY"),eq(9003L),any());
    verifyNoMoreInteractions(workbench);
  }
}
