package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.*;
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
class MonthlyOriginalLinkedPriceSourceIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mpp_original_linked_test").withUsername("root").withPassword("mpp-linked-test-only").withConfigurationOverride("monthly-pricing/mysql")
          .withLabel("sales-cost.task",System.getProperty("mpp.mysql.task","monthly-price-tests"))
          .withLabel("sales-cost.run",UUID.randomUUID().toString())
          .withReuse(Boolean.getBoolean("mpp.mysql.retain"));
  static com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader financeReader;static JdbcTemplate jdbc;static MonthlyOriginalLinkedPriceSource source;static AtomicInteger prepared=new AtomicInteger();
  static PriceLinkedItemMapper rules;static OriginalLinkedRuleSnapshot snapshots;static FinanceEffectivePriceService effective;static org.springframework.transaction.support.TransactionTemplate transaction;
  static MonthlyMaterialPriceService operation;static MonthlyPriceDocumentCodec codec;static AtomicInteger sourceCalls=new AtomicInteger();
  @BeforeAll static void database() throws Exception {
    MYSQL.start();var original=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    var ds=new DelegatingDataSource(original){@Override public java.sql.Connection getConnection() throws java.sql.SQLException{
      var connection=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
        if(method.getName().equals("prepareStatement"))prepared.incrementAndGet();try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
      });
    }};jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/original-linked-source-schema.sql")).execute(ds);
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql","V354__invoice_price_import.sql","V360__finance_correction_monthly_price.sql","V364__household_inquiry_header_schema.sql","V365__household_monthly_source_revision.sql","V372__monthly_price_disable.sql","V366__household_model_specification_match.sql")){
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/scrap-monthly.sql");var result=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_original_linked_test < /tmp/scrap-monthly.sql");assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
    }
    var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(MonthlyOriginalLinkedSourceMapper.class);for(var mapper:List.of(PriceLinkedItemMapper.class,PriceRangeFactorRuleMapper.class,FinanceEffectivePriceMapper.class,MaterialPriceMonthMapper.class))configuration.addMapper(mapper);for(var mapper:List.of(com.sanhua.marketingcost.mapper.FactorIdentityMapper.class,com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class,com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper.class,com.sanhua.marketingcost.mapper.FactorRowRefMapper.class))configuration.addMapper(mapper);configuration.addMapper(MonthlySupplierInputMapper.class);configuration.addMapper(MonthlyPriceStorageMapper.class);configuration.addMapper(MonthlyPriceLinkMapper.class);var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
    var sql=new SqlSessionTemplate(factory.getObject());codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    var finance=new com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader(sql.getMapper(com.sanhua.marketingcost.mapper.FactorUploadBatchMapper.class),sql.getMapper(com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper.class),sql.getMapper(com.sanhua.marketingcost.mapper.FactorIdentityMapper.class),sql.getMapper(com.sanhua.marketingcost.mapper.FactorRowRefMapper.class));
    financeReader=finance;
    var json=new ObjectMapper().findAndRegisterModules();rules=sql.getMapper(PriceLinkedItemMapper.class);snapshots=new OriginalLinkedRuleSnapshot(json);var calculator=new OriginalLinkedRuleCalculator();
    var publicationSource=new OriginalLinkedPublicationSource(new OriginalLinkedWorkbookParser(new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl())),snapshots,sql.getMapper(FactorUploadBatchMapper.class),sql.getMapper(FactorIdentityMapper.class));
    effective=new FinanceEffectivePriceService(sql.getMapper(FinanceEffectivePriceMapper.class),rules,sql.getMapper(PriceRangeFactorRuleMapper.class),sql.getMapper(FactorUploadBatchMapper.class),finance,new OriginalRangeRuleSnapshot(json),calculator,new OriginalRangeRuleCalculator(),json,publicationSource);
    transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(ds));
    source=new MonthlyOriginalLinkedPriceSource(sql.getMapper(MonthlyOriginalLinkedSourceMapper.class),new MaterialPriceMonthSelector(sql.getMapper(MaterialPriceMonthMapper.class)),finance,effective,sql.getMapper(MonthlySupplierInputMapper.class),new SupplierPriceSelectionService(null,null),new OriginalLinkedPriceRuntime(snapshots,calculator,finance,json),codec);
    var storage=new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class));operation=new MonthlyMaterialPriceService(storage,new DataSourceTransactionManager(ds),codec,(context,requests,dependencies)->{sourceCalls.incrementAndGet();return source.read(context,requests);});
  }
  @AfterAll static void stop(){if (!Boolean.getBoolean("mpp.mysql.retain")) MYSQL.stop();}
  static Context context(String bu,String month,String org){return new Context(bu,"MPP05-LINKED",1L,"TOP",YearMonth.parse(month),YearMonth.parse(month).atDay(23).atStartOfDay(),"fixture",org);}
  static MonthlyPriceRequest request(String code,String bu,String month,String org,NonCuConditions terms){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),bu,code,"SPEC","件","CNY",TaxBasis.EXCLUDED,null),org,terms,BigDecimal.ONE);}
  static String code(){return "MPP05-linked-"+UUID.randomUUID();}
  static PriceLinkedItem rule(String code,String bu,String month,String org,String supplier,String expression,boolean board){
    return rule(code,bu,month,org,supplier,supplier,expression,board);
  }
  static PriceLinkedItem rule(String code,String bu,String month,String org,String supplier,String supplierName,String expression,boolean board){
    return rule(code,bu,month,org,supplier,supplierName,expression,board,"SPEC","件");
  }
  static PriceLinkedItem rule(String code,String bu,String month,String org,String supplier,String supplierName,String expression,boolean board,String spec,String unit){
    var key=new MaterialPriceRuleKey(bu,org,code,supplier,supplierName,spec,unit);var factors=new LinkedHashMap<String,OriginalLinkedWorkbookParser.FactorInput>();factors.put("cu",new OriginalLinkedWorkbookParser.FactorInput(PriceFactorContext.COPPER,new Address("财务基价","D3"),FactorPriceUnit.CNY_KG,"CNY/KG",PriceFactorContext.COPPER,"SAME_CANONICAL_FACTOR"));if(board)factors.put("sus",new OriginalLinkedWorkbookParser.FactorInput("AVG|304/2B/1219",new Address("财务基价","D4"),FactorPriceUnit.CNY_KG,"CNY/KG","AVG|304/2B/1219","SAME_CANONICAL_FACTOR"));
    var original=new OriginalLinkedWorkbookParser.Rule(key,"fixture linked material","原稿",8,"H8",expression,expression,"0.000000",false,new CompiledFormula(expression,List.of(),Map.of()),factors,BigDecimal.ZERO);
    var row=new PriceLinkedItem();row.setRuleIdentityKey(key.identityKey());row.setRuleRevision(1);row.setRuleStatus("PUBLISHED");row.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);row.setSourceKind("PUBLIC");row.setBusinessUnitType(bu);row.setOrgCode(org);row.setPricingMonth(month);row.setMaterialCode(code);row.setMaterialName("fixture linked material");row.setSpecModel(spec);row.setUnit(unit);row.setSupplierCode(supplier);row.setSupplierName(supplierName);row.setTaxIncluded(0);row.setFormulaExpr(expression);row.setSourceInputSnapshotJson(snapshots.write(List.of(original),"controlled-original-version"));row.setSourceSheetName("原稿");row.setSourceRowNumber(8);row.setSourceFormulaCellRef("H8");row.setSourceFormulaExpr("original H8 formula");row.setEffectiveFrom(YearMonth.parse(month).atDay(1));row.setEffectiveTo(LocalDate.of(2099,1,1));row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(LocalDateTime.now());row.setDeleted(0);row.setTechnicalRevision(0);rules.insert(row);return row;
  }
  static long publication(String bu,String month,String org){return publication(bu,month,org,1);}
  static long publication(String bu,String month,String org,int revision){
    String batch=UUID.randomUUID().toString();jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,?,'finance-linked-original.xlsx',?,?,'finance-fixture','PUBLISHED',?,?,'DEFAULT',NOW())",batch,month,bu,"a".repeat(64),"b".repeat(64),org,revision);long id=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,batch);
    for(String factor:org.equals("220")?List.of(PriceFactorContext.COPPER,"AVG|304/2B/1219"):List.of(PriceFactorContext.COPPER)){
      String seq=UUID.randomUUID().toString();jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES(?, ?,?,?,'AVG',?)",bu,seq,factor,factor,factor);long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,seq);
      for(String condition:org.equals("220")?List.of("NON_TENDER","TENDER"):List.of("GENERAL")){
        int value=factor.equals(PriceFactorContext.COPPER)?100:condition.equals("TENDER")?5:10;
        jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,?,1,?,'PUBLISHED',?,'DEFAULT',?,1,'CNY/KG',NOW())",identity,month,value,id,org,condition);long priceId=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=? AND condition_code=?",Long.class,identity,condition);
        jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,source_number_format,conversion_basis) VALUES(?,'财务基价',?,?,?,4,?,'DEFAULT',?,?,'CNY/KG','0.000000','IDENTITY')",id,factor.equals(PriceFactorContext.COPPER)?3:4,identity,priceId,factor.equals(PriceFactorContext.COPPER)?"D3":"D4",condition,Integer.toString(value));
      }
    }return id;
  }
  static void publish(String bu,String org){transaction.executeWithoutResult(status->effective.generateScope(bu,org));}
  static MonthlyAdoptionPlan plan(MonthlyPriceRequest request,MonthlyAdoptionPlan.Selection selection){return new MonthlyAdoptionPlan(Map.of(request,selection),List.of(new MonthlyAdoptionPlan.Use("root/linked",request,BigDecimal.ONE)),Set.of());}
  @Test void bomMaterialCodeAloneSelectsPublishedLinkedPriceDespiteDisplayFields() {
    String month="2058-01", material=code();
    publication("COMMERCIAL",month,"210");
    var original=rule(material,"COMMERCIAL",month,"210","S1","[cu]+5",false);
    publish("COMMERCIAL","210");
    var req=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),"COMMERCIAL",
        material,"BOM规格 Φ2（型号）","支","CNY",TaxBasis.EXCLUDED,null),
        "210",NonCuConditions.empty(),BigDecimal.ONE);
    var ctx=context("COMMERCIAL",month,"210");
    var resolved=operation.resolveCandidates(ctx,Set.of(req));
    assertThat(resolved.problems()).as("BOM已有料号，来源规格和单位不能再次限制取价").isEmpty();
    var selected=resolved.values().get(req).selection();
    assertThat(selected.fact().price()).isEqualByComparingTo("105");
    var captured=codec.decode(codec.wrap(selected.fact().evidence().json()),MonthlyOriginalLinkedPriceSource.Snapshot.class);
    assertThat(captured.source().selected().getId()).isEqualTo(original.getId());
    assertThat(captured.source().selected().getSpecModel()).isEqualTo("SPEC");
    assertThat(captured.source().selected().getUnit()).isEqualTo("件");
    long id=operation.adopt(ctx,plan(req,selected)).plan().selections().get(req).monthlyPriceId();
    assertThat(operation.resolveCandidates(ctx,Set.of(req)).values().get(req).selection().monthlyPriceId()).isEqualTo(id);
    var pair=MonthlyScenarioTestSupport.create(null).calculate(
        Map.of(req,operation.readByIds("COMMERCIAL",Set.of(id)).get(id)),new BigDecimal("120000")).get(id);
    assertThat(pair.financialPrice()).isEqualByComparingTo("105");
    assertThat(pair.customerPrice()).isEqualByComparingTo("125");
    var absent=request(code(),"COMMERCIAL",month,"210",NonCuConditions.empty());
    assertThat(source.read(ctx,Set.of(absent)).problems().get(absent).reason())
        .as("BOM有料号但无对应来源，不能借用同规格的其他料号")
        .isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);
  }

  @Test void bomWithoutMaterialCodeMatchesPriceSpecificationAndKeepsTheBomUnchanged() {
    String month="2058-02", material=code();
    publication("COMMERCIAL",month,"210");
    rule(material,"COMMERCIAL",month,"210","S1","[cu]+5",false);
    rule(material,"COMMERCIAL",month,"210","S2","S2","[cu]+999",false,"OTHER-SPEC","KG");
    publish("COMMERCIAL","210");
    var reader=new MonthlyQuotationPrices(operation,new HouseholdMonthlyPriceSource(null,codec,null,null),
        new BomSpecificationPriceIdentity(jdbc));
    var bom=new MonthlyQuotationPrices.Item(null,"210","支","SPEC",TaxBasis.EXCLUDED,null,
        NonCuConditions.empty(),BigDecimal.ONE,MonthlyQuotationPrices.Purpose.MATERIAL);
    var ctx=context("COMMERCIAL",month,"210");
    var read=reader.read(ctx,List.of(bom));
    assertThat(read.problems()).isEmpty();
    var bound=read.values().get(bom);
    assertThat(bound.request().family().specificationOnly()).isTrue();
    assertThat(bound.request().materialCode()).isEqualTo(material);
    assertThat(bound.candidate().selection().fact().price()).isEqualByComparingTo("105");
    assertThat(bom.materialCode()).as("原BOM无料号事实保持").isNull();
    var node=new MonthlyBomPricingScope.Node("TOP/anonymous-row","","TOP",bound.request(),BigDecimal.ONE,"SPEC");
    assertThat(node.materialCode()).isEmpty();
    var saved=operation.adopt(ctx,plan(bound.request(),bound.candidate().selection()));
    long id=saved.plan().selections().get(bound.request()).monthlyPriceId();
    assertThat(reader.read(ctx,List.of(bom)).values().get(bom).candidate().selection().monthlyPriceId()).isEqualTo(id);
    var pair=MonthlyScenarioTestSupport.create(null).calculate(
        Map.of(bound.request(),operation.readByIds("COMMERCIAL",Set.of(id)).get(id)),new BigDecimal("120000")).get(id);
    assertThat(pair.customerPrice()).isEqualByComparingTo("125");
    assertThat(jdbc.queryForObject("SELECT material_code FROM lp_material_monthly_price WHERE id=?",String.class,id))
        .as("原BOM保持无料号，月价保留真实来源料号及按规格匹配依据").isEqualTo(material);
  }

  @Test void reportedUnitSpacesAndDrawingSuffixDifferencesKeepAllCodeMatchedSuppliers() {
    String month="2058-03";publication("COMMERCIAL",month,"210");
    var cases=List.of(
        List.of("SHF-H35-020002","SHF-H35-020002","支","只"),
        List.of("BAg1.2CuPIn Φ23×1.8","BAg1.2CuPIn\u00a0Φ23×1.8","千克","千克"),
        List.of("BCu91PAg Φ40.8×1.2(SHF-H35672-001-FZ03)","BCu91PAg Ф40.8×1.2","千克","千克"));
    var requests=new ArrayList<MonthlyPriceRequest>();
    for(var fields:cases) {
      String material=code();
      rule(material,"COMMERCIAL",month,"210","S1","S1","[cu]+5",false,fields.get(0),fields.get(2));
      rule(material,"COMMERCIAL",month,"210","S2","S2","[cu]+6",false,fields.get(1),fields.get(3));
      for(String supplier:List.of("S1","S2"))
        jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_code,supplier_name,supply_ratio,is_active,deleted) VALUES('COMMERCIAL',?,'fixture','SPEC',?,?,?,1,0)",material,supplier,supplier,new BigDecimal(supplier.equals("S2")?"0.65":"0.35"));
      requests.add(new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),"COMMERCIAL",material,fields.get(0),fields.get(2),"CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE));
    }
    publish("COMMERCIAL","210");var ctx=context("COMMERCIAL",month,"210");
    var result=operation.resolveCandidates(ctx,Set.copyOf(requests));assertThat(result.problems()).isEmpty();
    for(var request:requests) {
      var selected=result.values().get(request).selection();
      assertThat(selected.fact().price()).isEqualByComparingTo("106");
      var evidence=codec.decode(codec.wrap(selected.fact().evidence().json()),MonthlyOriginalLinkedPriceSource.Snapshot.class);
      assertThat(evidence.source().candidates()).hasSize(2);
      assertThat(evidence.source().selected().getSupplierCode()).isEqualTo("S2");
    }
  }

  @Test void explicitRatioConfigurationUsesHighestCalculatedLinkedPriceWithoutReview() {
    String month="2057-02",material=code();publication("COMMERCIAL",month,"210");
    rule(material,"COMMERCIAL",month,"210","S1","[cu]+5",false);
    rule(material,"COMMERCIAL",month,"210","S2","[cu]+15",false);publish("COMMERCIAL","210");
    jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted,selection_rule) VALUES('COMMERCIAL',?,'fixture','SPEC','S1','S1',NULL,1,0,'无供货比率，取最高价')",material);
    var request=request(material,"COMMERCIAL",month,"210",NonCuConditions.empty());var c=context("COMMERCIAL",month,"210");
    var resolved=operation.resolveCandidates(c,Set.of(request));assertThat(resolved.problems()).isEmpty();
    var selected=resolved.values().get(request).selection();assertThat(selected.fact().price()).isEqualByComparingTo("115");
    assertThat(selected.fact().evidence().json()).contains("CONFIGURED_HIGH","无供货比率");
    assertThat(c.supplierReviews().reviews()).isEmpty();
    assertThat(operation.adopt(c,plan(request,selected)).plan().selections().get(request).monthlyPriceId()).isNotNull();
  }

  @Test void financeCorrectionRefreshesAdoptedMonthlyPriceWithoutRewritingHistory() {
    String month="2056-01", material=code();
    publication("COMMERCIAL",month,"210");
    rule(material,"COMMERCIAL",month,"210","S1","[cu]+5",false);
    publish("COMMERCIAL","210");
    var r=request(material,"COMMERCIAL",month,"210",NonCuConditions.empty());
    var c=context("COMMERCIAL",month,"210");
    var before=operation.adopt(c,plan(r,operation.resolveCandidates(c,Set.of(r)).values().get(r).selection())).plan().selections().get(r);
    assertThat(before.fact().price()).isEqualByComparingTo("105");
    long corrected=publication("COMMERCIAL",month,"210",2);
    jdbc.update("UPDATE lp_factor_monthly_price SET revision_no=2,price=110 WHERE source_upload_batch_id=?",corrected);
    publish("COMMERCIAL","210");
    transaction.executeWithoutResult(status->new com.sanhua.marketingcost.service.financefactor.FinanceMonthlyPriceRefreshService(jdbc,codec)
        .refresh(new com.sanhua.marketingcost.dto.FinanceFactorImportContext("COMMERCIAL","210",month,null,null),corrected));
    assertThat(operation.lookup(Set.of(r)).missing()).as("更正财务基价后，旧月价退出当前取价").contains(r);
    var after=operation.adopt(c,plan(r,operation.resolveCandidates(c,Set.of(r)).values().get(r).selection())).plan().selections().get(r);
    assertThat(after.fact().price()).isEqualByComparingTo("115");
    assertThat(after.monthlyPriceId()).isNotEqualTo(before.monthlyPriceId());
    assertThat(operation.readByIds("COMMERCIAL",Set.of(before.monthlyPriceId())).get(before.monthlyPriceId())).isEqualTo(before);
  }
  @Test void actualPublishedOriginalLinkedPriceAdoptsThenSurvivesTheRemovalOfSourceAndFinance() throws Exception {
    String month="2026-09";String code=code();long batch=publication("COMMERCIAL",month,"210");var row=rule(code,"COMMERCIAL",month,"210","S1","[cu]+5",false);publish("COMMERCIAL","210");var request=request(code,"COMMERCIAL",month,"210",NonCuConditions.empty());var c=context("COMMERCIAL",month,"210");long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_finance_effective_price",Long.class);var candidates=operation.resolveCandidates(c,Set.of(request));assertThat(candidates.problems()).isEmpty();var chosen=candidates.values().get(request).selection();assertThat(chosen.fact().price()).isEqualByComparingTo("105");assertThat(chosen.applicability().matches(request(code,"COMMERCIAL",month,"220",NonCuConditions.empty()))).isFalse();assertThat(chosen.fact().evidence().json()).contains("finance-linked-original.xlsx","D3","H8","controlled-original-version").doesNotContain("consumerOrg","oaNo");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_finance_effective_price",Long.class)).isEqualTo(before);
    var saved=operation.adopt(c,plan(request,chosen));long id=saved.plan().selections().get(request).monthlyPriceId();jdbc.update("DELETE FROM lp_finance_effective_price WHERE original_id=? AND price_type='LINKED'",row.getId());jdbc.update("DELETE FROM lp_price_linked_item WHERE id=?",row.getId());jdbc.update("DELETE FROM lp_factor_row_ref WHERE factor_upload_batch_id=?",batch);jdbc.update("DELETE FROM lp_factor_monthly_price WHERE source_upload_batch_id=?",batch);jdbc.update("DELETE FROM lp_factor_upload_batch WHERE id=?",batch);int calls=sourceCalls.get();assertThat(operation.resolveCandidates(c,Set.of(request)).values().get(request).selection().monthlyPriceId()).isEqualTo(id);assertThat(sourceCalls.get()).isEqualTo(calls);assertThat(operation.readByIds("COMMERCIAL",Set.of(id)).get(id).fact().evidence()).isEqualTo(chosen.fact().evidence());
    var savedFacts=operation.readByIds("COMMERCIAL",Set.of(id));
    var projection=MonthlyScenarioTestSupport.create(null);
    for(String copper:List.of("80000","100000","120000")) {
      var pair=projection.calculate(Map.of(request,savedFacts.get(id)),new BigDecimal(copper)).get(id);
      assertThat(pair.financialPrice()).isEqualByComparingTo("105");
      assertThat(pair.customerPrice()).isEqualByComparingTo(new BigDecimal(copper).divide(new BigDecimal("1000")).add(new BigDecimal("5")));
      assertThat(pair.sourceEvidenceHash()).isEqualTo(chosen.fact().evidence().sha256());
    }
    assertThat(operation.readByIds("COMMERCIAL",Set.of(id)).get(id).fact()).isEqualTo(chosen.fact());

  }
  @Test void financeAndEffectivePublicationAreRequiredAndPureReadsNeverGenerateThem(){
    String month="2026-01";String code=code();rule(code,"HOUSEHOLD",month,"210","S1","[cu]+5",false);var c=context("HOUSEHOLD",month,"210");var r=request(code,"HOUSEHOLD",month,"210",NonCuConditions.empty());var waiting=operation.resolveCandidates(c,Set.of(r));assertThat(waiting.problems().get(r).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);publication("HOUSEHOLD",month,"210");long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_finance_effective_price",Long.class);assertThat(operation.resolveCandidates(c,Set.of(r)).problems().get(r).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_finance_effective_price",Long.class)).isEqualTo(before);publish("HOUSEHOLD","210");assertThat(operation.resolveCandidates(c,Set.of(r)).values().get(r).selection().fact().price()).isEqualByComparingTo("105");
  }
  @Test void realSupplierRatioRowsSupportPreviewAdoptionAndSavedCopperProjection() {
    String month="2035-01";
    String code=code();
    publication("COMMERCIAL",month,"210");
    rule(code,"COMMERCIAL",month,"210","S1","[cu]+5",false);
    rule(code,"COMMERCIAL",month,"210","S2","[cu]+6",false);
    for(String supplier:List.of("S1","S2")) {
      jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,unit,supplier_name,supplier_code,supply_ratio,is_active,deleted) VALUES('COMMERCIAL',?,'ratio regression','SPEC','件',?,?,?,1,0)",
          code,supplier,supplier,supplier.equals("S1")?new BigDecimal("0.8"):new BigDecimal("0.2"));
    }
    publish("COMMERCIAL","210");
    var request=request(code,"COMMERCIAL",month,"210",NonCuConditions.empty());
    var context=context("COMMERCIAL",month,"210");
    var selected=operation.resolveCandidates(context,Set.of(request));
    assertThat(selected.problems()).isEmpty();
    var selection=selected.values().get(request).selection();
    var evidence=codec.decode(codec.wrap(selection.fact().evidence().json()),MonthlyOriginalLinkedPriceSource.Snapshot.class);
    assertThat(evidence.source().ratios()).hasSize(2);
    assertThat(evidence.source().ratios().getFirst().getMaterialCode()).isEqualTo(code);
    assertThat(selection.fact().evidence().json()).doesNotContain("requestedMaterialCode");
    var projection=MonthlyScenarioTestSupport.create(null);
    var preview=projection.preview(Map.of(request,selection),new BigDecimal("80000"),Map.of(request,context.priceAsOfTime().toLocalDate())).get(request);
    assertThat(preview.financialPrice()).isEqualByComparingTo("105");
    assertThat(preview.customerPrice()).isEqualByComparingTo("85");
    var saved=operation.adopt(context,plan(request,selection));
    long id=saved.plan().selections().get(request).monthlyPriceId();
    var restored=projection.calculate(Map.of(request,operation.readByIds("COMMERCIAL",Set.of(id)).get(id)),new BigDecimal("80000")).get(id);
    assertThat(restored.financialPrice()).isEqualByComparingTo("105");
    assertThat(restored.customerPrice()).isEqualByComparingTo("85");
  }
  @Test void wholeScopeMonthCannotFallBackPerMaterialAndZeroIsARealPrice(){
    String month="2026-03";String code=code();publication("HOUSEHOLD",month,"210");rule(code,"HOUSEHOLD",month,"210","S1","[cu]*0",false);publish("HOUSEHOLD","210");var c=context("HOUSEHOLD",month,"210");var r=request(code,"HOUSEHOLD",month,"210",NonCuConditions.empty());assertThat(operation.resolveCandidates(c,Set.of(r)).values().get(r).selection().fact().price()).isZero();rule(code(),"HOUSEHOLD","2026-04","210","S1","[cu]+5",false);var next=request(code,"HOUSEHOLD","2026-04","210",NonCuConditions.empty());var missing=source.read(context("HOUSEHOLD","2026-04","210"),Set.of(next));assertThat(missing.values()).isEmpty();assertThat(missing.problems().get(next).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);
  }
  @Test void selectedSupplierUsesTheExistingPublishedCandidatePolicyAndApprovalRemainsConsumerContext() throws Exception {
    String month="2026-05";String code=code();publication("COMMERCIAL",month,"210");rule(code,"COMMERCIAL",month,"210","S1","[cu]+5",false);rule(code,"COMMERCIAL",month,"210","S2","[cu]+6",false);publish("COMMERCIAL","210");var c=context("COMMERCIAL",month,"210");var r=request(code,"COMMERCIAL",month,"210",NonCuConditions.empty());var waiting=operation.resolveCandidates(c,Set.of(r));assertThat(waiting.problems().get(r).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);var review=c.supplierReviews().reviews().getFirst();jdbc.update("INSERT INTO lp_supplier_price_decision(scope_key,fingerprint,business_unit_type,oa_no,oa_form_item_id,period_month,pricing_date,org_code,material_code,price_type,decision,confirmed_by,confirmed_at) VALUES(?,?,'COMMERCIAL','MPP05-LINKED',1,?,?,'210',?,'LINKED','FALLBACK_HIGH','this-consumer-only',NOW())",review.scopeKey(),review.fingerprint(),month,c.priceAsOfTime().toLocalDate(),code);var ready=operation.resolveCandidates(c,Set.of(r));assertThat(ready.problems()).isEmpty();var chosen=ready.values().get(r).selection();assertThat(chosen.fact().price()).isEqualByComparingTo("106");assertThat(chosen.fact().evidence().json()).doesNotContain("this-consumer-only","scopeKey");var receipt=operation.adopt(c,plan(r,chosen));var origin=new ObjectMapper().findAndRegisterModules().valueToTree(Map.of("supplierReviews",receipt.supplierReviews()));assertThat(origin.path("supplierReviews").size()).isEqualTo(1);
  }
  @Test void boardStainlessAndTenderAreRealNonCopperConditionsAndDoNotBecomeProductCostInputs(){
    String month="2026-06";String code=code();publication("COMMERCIAL",month,"220");rule(code,"COMMERCIAL",month,"220","S1","[cu]+[sus]",true);publish("COMMERCIAL","220");var terms=new NonCuConditions(new BoardTerms(new BigDecimal("25000"),null,false));var r=request(code,"COMMERCIAL",month,"220",terms);var c=context("COMMERCIAL",month,"220");var result=operation.resolveCandidates(c,Set.of(r));assertThat(result.problems()).isEmpty();var chosen=result.values().get(r).selection();assertThat(chosen.fact().price()).isEqualByComparingTo("125");var other=request(code,"COMMERCIAL",month,"220",new NonCuConditions(new BoardTerms(new BigDecimal("26000"),null,false)));assertThat(chosen.applicability().matches(other)).isFalse();var saved=operation.adopt(c,plan(r,chosen));assertThat(operation.resolveCandidates(c,Set.of(r)).values().get(r).selection().monthlyPriceId()).isEqualTo(saved.plan().selections().get(r).monthlyPriceId());assertThat(operation.resolveCandidates(c,Set.of(other)).values().get(other).selection().fact().price()).isEqualByComparingTo("126");var tender=request(code,"COMMERCIAL",month,"220",new NonCuConditions(new BoardTerms(null,null,true)));assertThat(operation.resolveCandidates(c,Set.of(tender)).values().get(tender).selection().fact().price()).isEqualByComparingTo("105");
  }
  @Test void thousandOriginalLinkedIdentitiesAreReadInBoundedBatchesWithoutPerNodeFinanceQueries(){
    String month="2026-08";publication("HOUSEHOLD",month,"210");var requests=new LinkedHashSet<MonthlyPriceRequest>();for(int i=0;i<1000;i++){String code=code();rule(code,"HOUSEHOLD",month,"210","S1","[cu]+5",false);requests.add(request(code,"HOUSEHOLD",month,"210",NonCuConditions.empty()));}publish("HOUSEHOLD","210");long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_finance_effective_price",Long.class);prepared.set(0);var result=source.read(context("HOUSEHOLD",month,"210"),requests);int count=prepared.get();assertThat(result.values()).hasSize(1000);assertThat(result.problems()).isEmpty();assertThat(count).as("common metadata <=8, four batches <=16 each").isLessThanOrEqualTo(72);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_finance_effective_price",Long.class)).isEqualTo(before);
  }
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,false","true,false","false,true","true,true"})
  void aPublishedOriginalHeadIsNotBlockedByAnOlderLegacyExpressionRevisionWithTheSameIdentity(boolean unknownLegacyRevision,boolean unknownLegacyKey){
    String code=code();String month=unknownLegacyRevision?(unknownLegacyKey?"2026-10":"2026-12"):(unknownLegacyKey?"2026-09":"2026-07");publication("HOUSEHOLD",month,"210");var current=rule(code,"HOUSEHOLD",month,"210","S1","[cu]+5",false);jdbc.update("UPDATE lp_price_linked_item SET rule_revision=2 WHERE id=?",current.getId());current.setRuleRevision(2);
    jdbc.update("INSERT INTO lp_price_linked_item(pricing_month,org_code,supplier_code,supplier_name,material_code,material_name,spec_model,unit,formula_expr,tax_included,effective_from,effective_to,business_unit_type,source_kind,rule_identity_key,rule_revision,rule_status,expression_version,created_at,updated_at) VALUES(?,'210','S1','S1',?,'legacy fixture','SPEC','件','[old_variable]',0,?,?,'HOUSEHOLD','PUBLIC',?,?,'PUBLISHED','LEGACY',DATE_SUB(NOW(),INTERVAL 1 DAY),DATE_SUB(NOW(),INTERVAL 1 DAY))",month,code,YearMonth.parse(month).atDay(1),LocalDate.of(2099,1,1),unknownLegacyKey?null:current.getRuleIdentityKey(),unknownLegacyRevision?null:1);publish("HOUSEHOLD","210");
    var r=request(code,"HOUSEHOLD",month,"210",NonCuConditions.empty());var result=operation.resolveCandidates(context("HOUSEHOLD",month,"210"),Set.of(r));assertThat(result.problems()).isEmpty();assertThat(result.values().get(r).selection().fact().price()).isEqualByComparingTo("105");assertThat(result.values().get(r).selection().fact().evidence().json()).contains("ORIGINAL_V1").doesNotContain("old_variable");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_linked_item WHERE material_code=?",Long.class,code)).isEqualTo(2);
  }
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void anExplicitApplicableIdentityIgnoresLegacyHeadsOfOtherUnitsAndSpecifications(boolean unknownLegacyIdentity){
    String code=code();String month=unknownLegacyIdentity?"2026-11":"2026-02";publication("HOUSEHOLD",month,"210");rule(code,"HOUSEHOLD",month,"210","S1","[cu]+5",false);var other=new MaterialPriceRuleKey("HOUSEHOLD","210",code,"S1","S1","OTHER","KG");
    jdbc.update("INSERT INTO lp_price_linked_item(pricing_month,org_code,supplier_code,supplier_name,material_code,material_name,spec_model,unit,formula_expr,tax_included,effective_from,effective_to,business_unit_type,source_kind,rule_identity_key,rule_revision,rule_status,expression_version,created_at,updated_at) VALUES(?,'210','S1','S1',?,'legacy other identity','OTHER','KG','[old_variable]',0,?,?,'HOUSEHOLD','PUBLIC',?,1,'PUBLISHED','LEGACY',NOW(),NOW())",month,code,YearMonth.parse(month).atDay(1),LocalDate.of(2099,1,1),unknownLegacyIdentity?null:other.identityKey());publish("HOUSEHOLD","210");var r=request(code,"HOUSEHOLD",month,"210",NonCuConditions.empty());var result=operation.resolveCandidates(context("HOUSEHOLD",month,"210"),Set.of(r));assertThat(result.problems()).isEmpty();assertThat(result.values().get(r).selection().fact().price()).isEqualByComparingTo("105");
  }
  @Test void soleQuotedSupplierCanBeAdoptedWhenAnotherSupplierIsConfiguredAtOneHundredPercent() {
    String code=code(),month="2059-01";
    publication("COMMERCIAL",month,"210");
    var original=rule(code,"COMMERCIAL",month,"210","S000052","浙江科宇金属材料有限公司","58.384778761061946903",false);
    publish("COMMERCIAL","210");
    jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted) VALUES('COMMERCIAL',?,'圆形黄铜棒','C3604BDS Φ11','丰山（连云港）新材料有限公司','S001315',1,1,0)",code);
    var sourceBefore=jdbc.queryForList("SELECT * FROM lp_price_linked_item WHERE material_code=?",code);
    var effectiveBefore=jdbc.queryForList("SELECT * FROM lp_finance_effective_price WHERE material_code=?",code);
    var ratioBefore=jdbc.queryForList("SELECT * FROM lp_supplier_supply_ratio WHERE material_code=?",code);
    var request=request(code,"COMMERCIAL",month,"210",NonCuConditions.empty());var context=context("COMMERCIAL",month,"210");
    var result=operation.resolveCandidates(context,Set.of(request));
    assertThat(result.problems()).isEmpty();
    var chosen=result.values().get(request).selection();
    assertThat(chosen.fact().price()).isEqualByComparingTo("58.384778761061946903");
    var captured=codec.decode(codec.wrap(chosen.fact().evidence().json()),MonthlyOriginalLinkedPriceSource.Snapshot.class);
    assertThat(captured.source().selected().getId()).isEqualTo(original.getId());
    assertThat(captured.source().selectionRule()).isEqualTo("SINGLE");
    var adopted=operation.adopt(context,plan(request,chosen));
    var id=adopted.plan().selections().get(request).monthlyPriceId();
    assertThat(id).isNotNull();
    assertThat(operation.resolveCandidates(context,Set.of(request)).values().get(request).selection().monthlyPriceId()).isEqualTo(id);
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_linked_item WHERE material_code=?",code)).isEqualTo(sourceBefore);
    assertThat(jdbc.queryForList("SELECT * FROM lp_finance_effective_price WHERE material_code=?",code)).isEqualTo(effectiveBefore);
    assertThat(jdbc.queryForList("SELECT * FROM lp_supplier_supply_ratio WHERE material_code=?",code)).isEqualTo(ratioBefore);
  }
  @Test void singleSupplierWithZeroRatioUsesItsPublishedPriceWithoutRegeneration() {
    String code=code(),month="2040-01"; publication("COMMERCIAL",month,"210"); rule(code,"COMMERCIAL",month,"210","S1","100",false); publish("COMMERCIAL","210");
    var before=jdbc.queryForList("SELECT * FROM lp_finance_effective_price WHERE material_code=? ORDER BY id",code);
    var req=request(code,"COMMERCIAL",month,"210",NonCuConditions.empty()); assertThat(source.read(context("COMMERCIAL",month,"210"),Set.of(req)).problems()).isEmpty();
    jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted) VALUES('COMMERCIAL',?,'fixture','SPEC','S1','S1',0,1,0)",code);
    var result=source.read(context("COMMERCIAL",month,"210"),Set.of(req)); assertThat(result.problems()).isEmpty(); assertThat(result.values()).containsKey(req);
    assertThat(jdbc.queryForList("SELECT * FROM lp_finance_effective_price WHERE material_code=? ORDER BY id",code)).isEqualTo(before);
  }
  @Test void publishedOriginalWithSupplierNameOnlyIsAdoptedInsteadOfRequestedAgain() {
    String code=code(),month="2055-01";
    publication("COMMERCIAL",month,"210");
    var original=rule(code,"COMMERCIAL",month,"210","","实际供管部原稿供应商","[cu]+5",false);
    publish("COMMERCIAL","210");
    var request=request(code,"COMMERCIAL",month,"210",NonCuConditions.empty());
    var result=operation.resolveCandidates(context("COMMERCIAL",month,"210"),Set.of(request));
    assertThat(result.problems()).isEmpty();
    assertThat(result.values().get(request).selection().fact().price()).isEqualByComparingTo("105");
    assertThat(result.values().get(request).selection().fact().evidence().json()).contains(original.getRuleIdentityKey());
    var included=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(request.family().month(),"COMMERCIAL",code,"SPEC","件","CNY",TaxBasis.INCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);
    assertThat(source.read(context("COMMERCIAL",month,"210"),Set.of(included)).problems().get(included).reason())
        .isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);
  }

  @Test void originalWithoutSupplierCodeUsesTheCodedPrimaryRatioAndKeepsSavedEvidence() {
    String material=code(),month="2055-02",name="杭州华光焊接新材料股份有限公司";
    publication("COMMERCIAL",month,"210");
    var original=rule(material,"COMMERCIAL",month,"210","",name,"[cu]+5",false);
    jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted) VALUES('COMMERCIAL',?,'fixture','SPEC',?,'S000332',1,1,0)",material,name);
    publish("COMMERCIAL","210");
    var before=jdbc.queryForList("SELECT * FROM lp_finance_effective_price WHERE material_code=? ORDER BY id",material);
    var request=request(material,"COMMERCIAL",month,"210",NonCuConditions.empty());
    var ctx=context("COMMERCIAL",month,"210");
    var candidates=operation.resolveCandidates(ctx,Set.of(request));
    assertThat(candidates.problems()).isEmpty();
    var selected=candidates.values().get(request).selection();
    assertThat(selected.fact().price()).isEqualByComparingTo("105");
    var captured=codec.decode(codec.wrap(selected.fact().evidence().json()),MonthlyOriginalLinkedPriceSource.Snapshot.class);
    assertThat(captured.source().selectionRule()).isEqualTo("SINGLE");
    assertThat(captured.source().ratios()).anyMatch(r->"S000332".equals(r.getSupplierCode()));
    assertThat(captured.source().selected().getSupplierCode()).isEmpty();
    long id=operation.adopt(ctx,plan(request,selected)).plan().selections().get(request).monthlyPriceId();
    assertThat(operation.resolveCandidates(ctx,Set.of(request)).values().get(request).selection().monthlyPriceId()).isEqualTo(id);
    var pair=MonthlyScenarioTestSupport.create(null).calculate(Map.of(request,operation.readByIds("COMMERCIAL",Set.of(id)).get(id)),new BigDecimal("120000")).get(id);
    assertThat(pair.financialPrice()).isEqualByComparingTo("105");
    assertThat(pair.customerPrice()).isEqualByComparingTo("125");
    assertThat(rules.selectById(original.getId()).getSupplierCode()).isEmpty();
    assertThat(jdbc.queryForList("SELECT * FROM lp_finance_effective_price WHERE material_code=? ORDER BY id",material)).isEqualTo(before);
  }

}
