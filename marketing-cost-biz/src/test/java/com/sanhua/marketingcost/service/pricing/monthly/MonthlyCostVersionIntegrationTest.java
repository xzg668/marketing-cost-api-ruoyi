package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.costing.*;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.*;
import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.service.pricing.household.*;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.PreparationBinding;
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
class MonthlyCostVersionIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mpp_cost_version_test").withUsername("root").withPassword("mpp-linked-test-only").withConfigurationOverride("monthly-pricing/mysql")
      .withLabel("sales-cost.task", System.getProperty("mpp.mysql.task", "monthly-reprice-reuse-20261105-r02"))
      .withLabel("sales-cost.run", "monthly-cost-version-" + UUID.randomUUID())
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
    jdbc.execute("CREATE TABLE lp_material_master_raw(id BIGINT PRIMARY KEY AUTO_INCREMENT,material_code VARCHAR(64),organization_code VARCHAR(32),active_flag INT,material_model VARCHAR(255),material_spec VARCHAR(512),global_seg_4_material VARCHAR(255),shape_attr VARCHAR(64),drawing_no VARCHAR(255))");
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql","V354__invoice_price_import.sql","V360__finance_correction_monthly_price.sql","V364__household_inquiry_header_schema.sql","V365__household_monthly_source_revision.sql","V366__household_model_specification_match.sql")){
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/scrap-monthly.sql");var result=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_cost_version_test < /tmp/scrap-monthly.sql");assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
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
    initializeCostStorage();
  }
  @AfterAll static void stop(){if(!Boolean.getBoolean("mpp.mysql.retain"))MYSQL.stop();}
  static Context context(String bu,String month,String org){return new Context(bu,"MPP05-LINKED",1L,"TOP",YearMonth.parse(month),YearMonth.parse(month).atDay(23).atStartOfDay(),"fixture",org);}
  static MonthlyPriceRequest request(String code,String bu,String month,String org,NonCuConditions terms){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),bu,code,"SPEC","件","CNY",TaxBasis.EXCLUDED,null),org,terms,BigDecimal.ONE);}
  static String code(){return "MPP05-linked-"+UUID.randomUUID();}
  static PriceLinkedItem rule(String code,String bu,String month,String org,String supplier,String expression,boolean board){
    var key=new MaterialPriceRuleKey(bu,org,code,supplier,supplier,"SPEC","件");var factors=new LinkedHashMap<String,OriginalLinkedWorkbookParser.FactorInput>();factors.put("cu",new OriginalLinkedWorkbookParser.FactorInput(PriceFactorContext.COPPER,new Address("财务基价","D3"),FactorPriceUnit.CNY_KG,"CNY/KG",PriceFactorContext.COPPER,"SAME_CANONICAL_FACTOR"));if(board)factors.put("sus",new OriginalLinkedWorkbookParser.FactorInput("AVG|304/2B/1219",new Address("财务基价","D4"),FactorPriceUnit.CNY_KG,"CNY/KG","AVG|304/2B/1219","SAME_CANONICAL_FACTOR"));
    var original=new OriginalLinkedWorkbookParser.Rule(key,"fixture linked material","原稿",8,"H8",expression,expression,"0.000000",false,new CompiledFormula(expression,List.of(),Map.of()),factors,BigDecimal.ZERO);
    var row=new PriceLinkedItem();row.setRuleIdentityKey(key.identityKey());row.setRuleRevision(1);row.setRuleStatus("PUBLISHED");row.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);row.setSourceKind("PUBLIC");row.setBusinessUnitType(bu);row.setOrgCode(org);row.setPricingMonth(month);row.setMaterialCode(code);row.setMaterialName("fixture linked material");row.setSpecModel("SPEC");row.setUnit("件");row.setSupplierCode(supplier);row.setSupplierName(supplier);row.setTaxIncluded(0);row.setFormulaExpr(expression);row.setSourceInputSnapshotJson(snapshots.write(List.of(original),"controlled-original-version"));row.setSourceSheetName("原稿");row.setSourceRowNumber(8);row.setSourceFormulaCellRef("H8");row.setSourceFormulaExpr("original H8 formula");row.setEffectiveFrom(YearMonth.parse(month).atDay(1));row.setEffectiveTo(LocalDate.of(2099,1,1));row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(LocalDateTime.now());row.setDeleted(0);row.setTechnicalRevision(0);rules.insert(row);return row;
  }
  static long publication(String bu,String month,String org){
    String batch=UUID.randomUUID().toString();jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,?,'finance-linked-original.xlsx',?,?,'finance-fixture','PUBLISHED',?,1,'DEFAULT',NOW())",batch,month,bu,"a".repeat(64),"b".repeat(64),org);long id=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,batch);
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
  static MonthlyCostVersionService costing;
  static QuoteCostRunVersionMapper versionMapper;
  static PricePrepareItemMapper prepareMapper;
  static com.sanhua.marketingcost.service.QuoteCostRunVersionService versionService;
  static HouseholdInquiryService inquiries;
  static MonthlyPriceStorage priceStorage;
  static HouseholdMonthlyPriceSource household;
  static AtomicInteger fixtureNumber=new AtomicInteger();
  static void initializeCostStorage() throws Exception {
    var ds=jdbc.getDataSource();
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/cost-item-schema.sql")).execute(ds);
    jdbc.execute("CREATE TABLE lp_bom_costing_row(id BIGINT PRIMARY KEY,material_code VARCHAR(64),business_unit_type VARCHAR(32),material_attribute VARCHAR(255),shape_attr VARCHAR(64),material_spec VARCHAR(255))");
    // Current application version ownership added by V348; keep the historical pre-V342 fixture intact.
    jdbc.execute("ALTER TABLE lp_quote_cost_run_version ADD COLUMN reprice_no VARCHAR(64) NULL");
    var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
    var global=new com.baomidou.mybatisplus.core.config.GlobalConfig();global.setDbConfig(new com.baomidou.mybatisplus.core.config.GlobalConfig.DbConfig());global.setMetaObjectHandler(new com.sanhua.marketingcost.config.MetaObjectHandlerConfig());com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.setGlobalConfig(config,global);
    for(var type:List.of(QuoteCostRunVersionMapper.class,PricePrepareItemMapper.class,CostRunPartItemMapper.class,CostRunCostItemMapper.class,
        MonthlyPriceLinkMapper.class,MonthlyPriceStorageMapper.class,MonthlyCostVersionMapper.class,HouseholdInquiryStorageMapper.class,
        com.sanhua.marketingcost.mapper.household.HouseholdInquiryQueryMapper.class))config.addMapper(type);
    var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
    factory.setGlobalConfig(global);
    var sql=new SqlSessionTemplate(factory.getObject());var manager=new DataSourceTransactionManager(ds);
    priceStorage=new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class));
    var inquiryStorage=new HouseholdInquiryStorage(sql.getMapper(HouseholdInquiryStorageMapper.class),codec);
    inquiries=new HouseholdInquiryService(sql.getMapper(com.sanhua.marketingcost.mapper.household.HouseholdInquiryQueryMapper.class),inquiryStorage,
        new HouseholdInquiryWorkbookParser(),new HouseholdInquiryFiles(java.nio.file.Files.createTempDirectory("mpp08-inquiries-").toString()),codec,manager,new ObjectMapper());
    household=new HouseholdMonthlyPriceSource(inquiryStorage,codec, org.mockito.Mockito.mock(com.sanhua.marketingcost.service.MaterialPricePriorityService.class), financeReader);
    operation=new MonthlyMaterialPriceService(priceStorage,manager,codec,(context,requests,dependencies)->{
      var hh=household.read(context,requests);var remaining=new HashSet<>(requests);remaining.removeAll(hh.values().keySet());
      var normal=source.read(context,remaining);var values=new HashMap<>(normal.values());values.putAll(hh.values());
      var problems=new HashMap<>(normal.problems());problems.putAll(hh.problems());return new Candidates(values,problems);
    });
    versionMapper=sql.getMapper(QuoteCostRunVersionMapper.class);prepareMapper=sql.getMapper(PricePrepareItemMapper.class);
    versionService=new QuoteCostRunVersionServiceImpl(versionMapper,new QuoteCostRunVersionNoGeneratorImpl(versionMapper,null),new com.sanhua.marketingcost.service.CostingAlgorithmVersionProvider("COST_V3"));
    costing=new MonthlyCostVersionService(versionMapper,sql.getMapper(MonthlyCostVersionMapper.class),prepareMapper,sql.getMapper(CostRunPartItemMapper.class),sql.getMapper(CostRunCostItemMapper.class),operation,priceStorage,codec,MonthlyScenarioTestSupport.create(null),manager,
        new com.sanhua.marketingcost.service.costing.MonthlyCostSnapshotDocuments(codec,operation));
    jdbc.update("INSERT INTO lp_quote_tech_data_version(id) VALUES(7)");
  }
  record Fixture(QuoteCostRunVersion version,MonthlyAdoptionPlan plan,Ordinary ordinary,EffectiveTechnicalDataInput technical,
      BigDecimal copper,Map<String,PreparationBinding> bindings,long linkedRuleId) {
    com.sanhua.marketingcost.service.pricing.CustomerMaterialPrices customerPrices() {
      return com.sanhua.marketingcost.service.pricing.CustomerMaterialPrices.copperAndZinc(copper, null);
    }
  }
  static BigDecimal d(String v){return new BigDecimal(v);}
  static void household(String code,String price) throws Exception {
    try(var stream=MonthlyCostVersionIntegrationTest.class.getResourceAsStream("/household-inquiry/real-20261023.xls");var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(stream);var bytes=new java.io.ByteArrayOutputStream()){
      var sheet=book.getSheet("10月线圈询价");for(int row=sheet.getLastRowNum();row>2;row--)if(sheet.getRow(row)!=null)sheet.removeRow(sheet.getRow(row));
      sheet.getRow(2).getCell(0).setCellValue(code);sheet.getRow(2).getCell(4).setCellValue(price);sheet.getRow(2).getCell(5).setCellValue(0);sheet.getRow(2).getCell(6).setCellValue(0);book.write(bytes);
      var preview=inquiries.preview("COMMERCIAL","test",bytes.toByteArray(),"mpp08-controlled-input.xls","2026-10");
      inquiries.publish("COMMERCIAL","test",new PublishRequest(preview.previewId(),preview.publishRequestId(),preview.previewFingerprint()));
    }
  }
  static long prepare(QuoteCostRunVersion version,MonthlyPriceRequest r,String batch,String path,BigDecimal q,BigDecimal price){
    var row=new PricePrepareItem();row.setPrepareNo(batch);row.setPeriodMonth(version.getPricingMonth());row.setOaNo(version.getOaNo());row.setOaFormItemId(version.getOaFormItemId());row.setTopProductCode("TOP");
    row.setMaterialCode(r.family().materialCode());row.setQuantity(q);row.setUnitPrice(price.setScale(8,java.math.RoundingMode.HALF_UP));row.setAmount(q.multiply(price).setScale(8,java.math.RoundingMode.HALF_UP));
    row.setPriceOrgCode("210");row.setItemType("NORMAL");row.setStatus("READY");row.setCurrentFlag(1);row.setSettlementKey(path);row.setBusinessUnitType("COMMERCIAL");prepareMapper.insert(row);return row.getId();
  }
  static Fixture fixture(String q,String copper,boolean fees) throws Exception {
    int n=fixtureNumber.incrementAndGet();String month=YearMonth.of(2030,1).plusMonths(n).toString();String code=code(),hh=code();
    publication("COMMERCIAL",month,"210");var linked=rule(code,"COMMERCIAL",month,"210","S1","[cu]",false);publish("COMMERCIAL","210");household(hh,"20.9853");
    var normal=request(code,"COMMERCIAL",month,"210",NonCuConditions.empty());var h=request(hh,"COMMERCIAL",month,"210",NonCuConditions.empty());
    var context=context("COMMERCIAL",month,"210");var candidate=operation.resolveCandidates(context,Set.of(normal,h));assertThat(candidate.problems()).isEmpty();
    var selected=new HashMap<MonthlyPriceRequest,MonthlyAdoptionPlan.Selection>();candidate.values().forEach((r,v)->selected.put(r,v.selection()));
    var adopted=operation.adopt(context,new MonthlyAdoptionPlan(selected,List.of(new MonthlyAdoptionPlan.Use("root/CU",normal,BigDecimal.ONE),new MonthlyAdoptionPlan.Use("root/HH",h,d(q))),Set.of("root/HH/included"))).plan();
    var version=versionService.createRunning(context.oaNo(),1L,"TOP",month,month,"FIN-"+n,"COMMERCIAL");
    version.setFinancePricePrepareNo("FIN-"+n);version.setOaPricePrepareNo("OA-"+n);versionMapper.updateById(version);
    var pairs=MonthlyScenarioTestSupport.create(null).calculate(adopted.selections(),d(copper));
    var bindings=new HashMap<String,PreparationBinding>();
    for(var use:adopted.uses()){
      var pair=pairs.get(adopted.selections().get(use.request()).monthlyPriceId());
      bindings.put(use.path(),new PreparationBinding(prepare(version,use.request(),version.getFinancePricePrepareNo(),use.path(),use.cumulativeQuantity(),pair.financialPrice()),prepare(version,use.request(),version.getOaPricePrepareNo(),use.path(),use.cumulativeQuantity(),pair.customerPrice())));
    }
    var normalPair=pairs.get(adopted.selections().get(normal).monthlyPriceId());
    var line=new MaterialLine("root/CU",code,BigDecimal.ONE,List.of(normalPair.monthlyPriceId()),d("100"),normalPair.customerPrice(),null);
    var ordinary=new Ordinary(List.of(line),BigDecimal.ZERO,d("10"),d("2"),d(".01"),d(".20"),d("1.05"),d(".1"),d(".25"),d(".02"),BigDecimal.ZERO);
    EffectiveTechnicalDataInput technical=fees?new EffectiveTechnicalDataInput(1L,7L,1,month,EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION,"selected-profile",LocalDateTime.now(),false,false,false,null,null,null,List.of(),List.of(),List.of(),new EffectiveTechnicalDataInput.ProductFees(null,d("4"),d("1"),d("2"),"CNY",d("3"),2),null):null;
    return new Fixture(version,adopted,ordinary,technical,d(copper),Map.copyOf(bindings),linked.getId());
  }
  static MonthlyCostVersionService.Stored save(Fixture f){return costing.calculateAndSave("COMMERCIAL",f.version().getId(),f.plan(),f.ordinary(),f.technical(),f.copper(),f.bindings());}

  // The quotation orchestrator publishes SUCCESS only after all output is saved.
  // Reader-only fixtures model that state transition, without seeding any amounts.
  static MonthlyCostVersionService.Stored finishSaved(long id) {
    var saved = costing.read("COMMERCIAL", id);
    assertThat(saved.snapshot()).isNotNull();
    var update = new QuoteCostRunVersion(); update.setId(id); update.setStatus("SUCCESS");
    versionMapper.updateById(update);
    return costing.read("COMMERCIAL", id);
  }
  static MonthlyCostVersionService.Stored saveCompleted(Fixture f) {
    save(f); return finishSaved(f.version().getId());
  }

  @Test void taskBookFullGoldenUsesActualSourceAdoptionDualPreparationsAndVersionStorage() throws Exception {
    var f=fixture("2","92000",true);var stored=save(f);var r=stored.snapshot().calculation();
    assertThat(r.ordinary().loss()).isEqualByComparingTo("1.120000");assertThat(r.ordinary().manufactureCost()).isEqualByComparingTo("141.400000");
    assertThat(r.totalCost()).isEqualByComparingTo("267.627207");assertThat(r.cuMaterialAdjustment()).isEqualByComparingTo("-8");assertThat(r.finalQuoteAmount()).isEqualByComparingTo("259.627207");
    assertThat(stored.version().getStatus()).isEqualTo("RUNNING");assertThat(stored.parts()).hasSize(2);assertThat(stored.costs()).hasSize(18);
    assertThat(stored.parts()).allSatisfy(part->assertThat(priceStorage.costPartLink("COMMERCIAL",part.getId()).monthlyPriceId()).isNotNull());
    assertThat(stored.snapshot().preparations()).allSatisfy((path,binding)->{
      assertThat(priceStorage.preparationLink("COMMERCIAL",binding.financialItemId()).monthlyPriceId()).isNotNull();
      assertThat(priceStorage.preparationLink("COMMERCIAL",binding.customerItemId()).monthlyPriceId()).isNotNull();
    });
    var before=codec.encode(stored.snapshot());jdbc.update("DELETE FROM lp_finance_effective_price WHERE original_id=?",f.linkedRuleId());jdbc.update("DELETE FROM lp_price_linked_item WHERE id=?",f.linkedRuleId());
    assertThat(codec.encode(costing.read("COMMERCIAL",f.version().getId()).snapshot())).isEqualTo(before);
    assertThatThrownBy(()->costing.read("HOUSEHOLD",f.version().getId())).hasMessageContaining("not found");
    assertThatThrownBy(()->save(f)).hasMessageContaining("fresh");
  }
  @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"1,80000,27.111654,-20","2,100000,54.223307,0","2.5,120000,67.779134,20"})
  void fractionalQuantityAndAllCopperSignsSurviveExactMysqlJsonRoundTrips(String q,String copper,String ah,String delta) throws Exception {
    var f=fixture(q,copper,false);var stored=save(f);var r=stored.snapshot().calculation();
    assertThat(r.householdContribution()).isEqualByComparingTo(ah);assertThat(r.cuMaterialAdjustment()).isEqualByComparingTo(delta);
    assertThat(r.fees()).hasSize(4).allMatch(fee->fee.amount()==null);assertThat(stored.costs().stream().filter(cost->cost.getCostCode().startsWith("OTHER_EXP_TECH_"))).allMatch(cost->cost.getAmount()==null);
    assertThat(codec.encode(costing.read("COMMERCIAL",stored.version().getId()).snapshot())).isEqualTo(codec.encode(stored.snapshot()));
    assertThat(operation.readByIds("COMMERCIAL",f.plan().selections().values().stream().map(MonthlyAdoptionPlan.Selection::monthlyPriceId).toList()).values()).allSatisfy(price->{if(price.fact().type()==MonthlyAdoptionPlan.PricingType.HOUSEHOLD_INQUIRY)assertThat(price.fact().price()).isEqualByComparingTo("20.9853");else assertThat(price.fact().price()).isEqualByComparingTo("100");});
  }
  @Test void invalidPreparationRollsBackEveryNewLinkAndOutputButRetainsAdoptedMonthlyFacts() throws Exception {
    var f=fixture("2","92000",true);long monthly=jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class);
    var h=f.bindings().get("root/HH");jdbc.update("UPDATE lp_price_prepare_item SET quantity=999 WHERE id=?",h.customerItemId());
    assertThatThrownBy(()->save(f)).hasMessageContaining("quantity");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE cost_run_version_id=?",Long.class,f.version().getId())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_cost_item WHERE cost_run_version_id=?",Long.class,f.version().getId())).isZero();
    for(var b:f.bindings().values()){assertThat(priceStorage.preparationLink("COMMERCIAL",b.financialItemId()).monthlyPriceId()).isNull();assertThat(priceStorage.preparationLink("COMMERCIAL",b.customerItemId()).monthlyPriceId()).isNull();}
    assertThat(priceStorage.versionSnapshot("COMMERCIAL",f.version().getId()).jianjiSnapshotJson()).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class)).isEqualTo(monthly);
    assertThat(versionMapper.selectById(f.version().getId()).getStatus()).isEqualTo("RUNNING");
    jdbc.update("UPDATE lp_price_prepare_item SET quantity=2 WHERE id=?",h.customerItemId());assertThat(save(f).version().getStatus()).isEqualTo("RUNNING");
  }
  @Test void aLateSqlFailureRollsBackTheSnapshotPartsFeesAndLinksTogether() throws Exception {
    var f=fixture("1","100000",false);
    jdbc.execute("CREATE TRIGGER mpp08_fail_cost BEFORE INSERT ON lp_cost_run_cost_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP08_INJECTED_LATE_FAILURE'");
    try{assertThatThrownBy(()->save(f)).hasMessageContaining("MPP08_INJECTED_LATE_FAILURE");}finally{jdbc.execute("DROP TRIGGER mpp08_fail_cost");}
    assertThat(priceStorage.versionSnapshot("COMMERCIAL",f.version().getId()).jianjiSnapshotJson()).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE cost_run_version_id=?",Long.class,f.version().getId())).isZero();
    for(var b:f.bindings().values())assertThat(priceStorage.preparationLink("COMMERCIAL",b.financialItemId()).monthlyPriceId()).isNull();
    assertThat(save(f).version().getStatus()).isEqualTo("RUNNING");
  }

  @Test void bulkRowsKeepApplicationMetadataTimeWhenMysqlSessionUsesAnotherZone() throws Exception {
    var f=fixture("2","92000",true);
    String originalZone=jdbc.queryForObject("SELECT @@GLOBAL.time_zone",String.class);
    String differentZone=java.time.ZonedDateTime.now().getOffset().equals(java.time.ZoneOffset.UTC)?"+12:00":"+00:00";
    try {
      // This class owns a fresh database. New DriverManager connections inherit this setting.
      jdbc.update("SET GLOBAL time_zone=?",differentZone);
      var earliest=LocalDateTime.now().minusSeconds(1);
      var stored=save(f);
      var latest=LocalDateTime.now().plusSeconds(1);
      assertThat(stored.parts()).allSatisfy(part->{
        assertThat(part.getCreatedAt()).isBetween(earliest,latest);
        assertThat(part.getUpdatedAt()).isBetween(earliest,latest);
      });
      assertThat(stored.costs()).allSatisfy(cost->{
        assertThat(cost.getCreatedAt()).isBetween(earliest,latest);
        assertThat(cost.getUpdatedAt()).isBetween(earliest,latest);
      });
    } finally { jdbc.update("SET GLOBAL time_zone=?",originalZone); }
  }

  @Test void repeatedMaterialPathsAcrossInsertChunksRetainEveryRowAndRollbackAsOneCustomer() throws Exception {
    var original=fixture("2","92000",true);
    var first=original.ordinary().materials().getFirst();
    var firstUse=original.plan().uses().stream().filter(use->use.path().equals(first.path())).findFirst().orElseThrow();
    var uses=new java.util.ArrayList<>(original.plan().uses());
    var lines=new java.util.ArrayList<>(original.ordinary().materials());
    var bindings=new HashMap<>(original.bindings());
    for(int i=1;i<131;i++) {
      String path="root/CU-"+i;
      uses.add(new MonthlyAdoptionPlan.Use(path,firstUse.request(),BigDecimal.ONE));
      lines.add(new MaterialLine(path,first.materialCode(),BigDecimal.ONE,first.monthlyPriceIds(),
          first.financeAmount(),first.customerAmount(),null));
      bindings.put(path,new PreparationBinding(
          prepare(original.version(),firstUse.request(),original.version().getFinancePricePrepareNo(),path,BigDecimal.ONE,d("100")),
          prepare(original.version(),firstUse.request(),original.version().getOaPricePrepareNo(),path,BigDecimal.ONE,d("92"))));
    }
    var o=original.ordinary();
    var ordinary=new Ordinary(lines,o.nonBomMaterial(),o.directLabor(),o.indirectLabor(),o.lossRate(),o.manufactureRate(),
        o.coefficient(),o.managementRate(),o.salesRate(),o.financeRate(),o.otherIndependentFees(),o.details());
    var expanded=new Fixture(original.version(),new MonthlyAdoptionPlan(original.plan().selections(),uses,
        original.plan().suppressedPaths(),original.plan().ordinaryScopeRequests()),ordinary,original.technical(),original.copper(),Map.copyOf(bindings),original.linkedRuleId());
    jdbc.execute("CREATE TRIGGER mpp05_bulk_failure BEFORE INSERT ON lp_cost_run_cost_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP05_BULK_ROLLBACK'");
    try { assertThatThrownBy(()->save(expanded)).hasMessageContaining("MPP05_BULK_ROLLBACK"); }
    finally { jdbc.execute("DROP TRIGGER mpp05_bulk_failure"); }
    long id=original.version().getId();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE cost_run_version_id=?",Long.class,id)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_cost_item WHERE cost_run_version_id=?",Long.class,id)).isZero();
    assertThat(priceStorage.versionSnapshot("COMMERCIAL",id).jianjiSnapshotJson()).isNull();
    for(var binding:bindings.values()) {
      assertThat(priceStorage.preparationLink("COMMERCIAL",binding.financialItemId()).monthlyPriceId()).isNull();
      assertThat(priceStorage.preparationLink("COMMERCIAL",binding.customerItemId()).monthlyPriceId()).isNull();
    }
    var saved=save(expanded);
    assertThat(saved.parts()).hasSize(132);
    assertThat(saved.parts().stream().filter(part->part.getPartCode().equals(first.materialCode())).toList())
        .hasSize(131).allSatisfy(part->{
          assertThat(part.getQty()).isEqualByComparingTo(BigDecimal.ONE);
          assertThat(part.getUnitPrice()).isEqualByComparingTo("100");
          assertThat(part.getAmount()).isEqualByComparingTo("100");
        });
    assertThat(saved.parts()).allSatisfy(part->{
      assertThat(part.getOaNo()).isEqualTo(original.version().getOaNo());
      assertThat(part.getOaFormItemId()).isEqualTo(original.version().getOaFormItemId());
      assertThat(part.getCostRunVersionId()).isEqualTo(id);
      assertThat(priceStorage.costPartLink("COMMERCIAL",part.getId()).monthlyPriceId()).isNotNull();
    });
    assertThat(saved.parts().stream().map(com.sanhua.marketingcost.entity.CostRunPartItem::getPricePrepareItemId).distinct().count()).isEqualTo(132);
    assertThat(saved.snapshot().calculation().cuMaterialAdjustment()).isEqualByComparingTo("-1048");
  }
  @Test void legacyHistoryReadsItsOriginalAmountsAndHasNoSyntheticMonthlySnapshot(){
    var v=versionService.createRunning("OLD",1L,"TOP","2025-01","2025-01",null,"COMMERCIAL");v.setAlgorithmVersion("LEGACY");v.setStatus("SUCCESS");v.setTotalCost(d("12.345678"));versionMapper.updateById(v);
    var read=costing.read("COMMERCIAL",v.getId());assertThat(read.snapshot()).isNull();assertThat(read.version().getTotalCost()).isEqualByComparingTo("12.345678");
  }
  @Test void snapshotTamperingIsRejectedInsteadOfRepricingFromCurrentSources() throws Exception {
    var f=fixture("1","100000",false);save(f);jdbc.update("UPDATE lp_quote_cost_run_version SET final_quote_amount=1 WHERE id=?",f.version().getId());
    assertThatThrownBy(()->costing.read("COMMERCIAL",f.version().getId())).hasMessageContaining("Stored total2");
  }
  @Test void finalQuoteDepartmentDetailAndItsSourceSurviveStorageWithoutAnotherUplift() throws Exception {
    var f=fixture("1","100000",false);var o=f.ordinary();
    var detail=new OrdinaryDetail("WATER_POWER","水电费",d("12"),d(".166666"),d("2"),"lp_department_fund_rate",7L,"FINAL_QUOTE; controlled source amount already includes uplift","EXPENSE");
    var ordinary=new Ordinary(o.materials(),d("2"),o.directLabor(),o.indirectLabor(),o.lossRate(),o.manufactureRate(),o.coefficient(),o.managementRate(),o.salesRate(),o.financeRate(),d(".3"),List.of(detail));
    var stored=costing.calculateAndSave("COMMERCIAL",f.version().getId(),f.plan(),ordinary,null,f.copper(),f.bindings());
    assertThat(stored.snapshot().calculation().financeMaterialCost()).isEqualByComparingTo("102");
    assertThat(stored.snapshot().calculation().ordinaryInputs().otherIndependentFees()).isEqualByComparingTo(".3");
    assertThat(stored.costs().stream().filter(item->item.getCostCode().equals("WATER_POWER")).findFirst().orElseThrow())
      .satisfies(row->{assertThat(row.getAmount()).isEqualByComparingTo("2");assertThat(row.getSourceId()).isEqualTo(7L);assertThat(row.getRemark()).contains("FINAL_QUOTE");});
    assertThat(costing.read("COMMERCIAL",f.version().getId()).snapshot().calculation().ordinaryInputs().details()).containsExactly(detail);
  }

  @Test void sharedNumbersBindBothCustomersOwnPreparationsProfileAndHistory() throws Exception {
    var first = fixture("2", "92000", true);
    var second = anotherCustomer(first, 82L, 8L);
    var third = anotherCustomer(first, 83L, 9L);
    var numbers = MonthlyProductCost.calculate(MonthlyProductCost.resolve(first.plan(), first.ordinary(), first.technical()).inputs());
    var a = costing.saveCalculatedMaterials("COMMERCIAL", first.version().getId(), first.plan(), first.ordinary(), first.technical(), first.customerPrices(), first.bindings(), numbers);
    var savedA = codec.encode(a.snapshot());
    var b = costing.saveCalculatedMaterials("COMMERCIAL", second.version().getId(), second.plan(), second.ordinary(), second.technical(), second.customerPrices(), second.bindings(), numbers);
    var independent = save(third);
    assertThat(b.snapshot().calculation().totalCost()).isEqualByComparingTo(independent.snapshot().calculation().totalCost());
    assertThat(b.snapshot().calculation().ordinary()).isEqualTo(independent.snapshot().calculation().ordinary());
    assertThat(b.snapshot().calculation().household()).isEqualTo(independent.snapshot().calculation().household());
    assertThat(b.snapshot().calculation().cuMaterialAdjustment()).isEqualByComparingTo("-8");
    assertThat(b.snapshot().calculation().finalQuoteAmount()).isEqualByComparingTo("259.627207");
    assertThat(b.snapshot().oaFormItemId()).isEqualTo(82L);
    assertThat(b.snapshot().preparations()).isEqualTo(second.bindings());
    assertThat(b.snapshot().calculation().technicalInput().versionId()).isEqualTo(8L);
    assertThat(b.snapshot().calculation().fees()).allSatisfy(fee -> assertThat(fee.profileVersionId()).isEqualTo(8L));
    assertThat(b.parts()).allSatisfy(part -> {
      assertThat(part.getOaNo()).isEqualTo(second.version().getOaNo());
      assertThat(part.getCostRunVersionId()).isEqualTo(second.version().getId());
    });
    assertThat(b.costs().stream().filter(row -> row.getCostCode().startsWith("OTHER_EXP_TECH_")))
        .allSatisfy(row -> assertThat(row.getSourceId()).isEqualTo(8L));
    assertThat(codec.encode(costing.read("COMMERCIAL", first.version().getId()).snapshot())).isEqualTo(savedA);
    assertThat(codec.encode(costing.read("COMMERCIAL", second.version().getId()).snapshot())).isEqualTo(codec.encode(b.snapshot()));
  }

  @Test void sharedBindingRejectsAnotherOasPreparationAndChangedInputsWithoutPartialOutput() throws Exception {
    var first = fixture("1", "100000", true); var second = anotherCustomer(first, 84L, 10L);
    var numbers = MonthlyProductCost.calculate(MonthlyProductCost.resolve(first.plan(), first.ordinary(), first.technical()).inputs());
    assertThatThrownBy(() -> costing.saveCalculatedMaterials("COMMERCIAL", second.version().getId(), second.plan(),
        second.ordinary(), second.technical(), second.customerPrices(), first.bindings(), numbers)).hasMessageContaining("Preparation does not belong");
    var o = second.ordinary();
    var changed = new Ordinary(o.materials(), d("1"), o.directLabor(), o.indirectLabor(), o.lossRate(),
        o.manufactureRate(), o.coefficient(), o.managementRate(), o.salesRate(), o.financeRate(), o.otherIndependentFees());
    assertThatThrownBy(() -> costing.saveCalculatedMaterials("COMMERCIAL", second.version().getId(), second.plan(),
        changed, second.technical(), second.customerPrices(), second.bindings(), numbers)).hasMessageContaining("inputs differ");
    assertThat(priceStorage.versionSnapshot("COMMERCIAL", second.version().getId()).jianjiSnapshotJson()).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE cost_run_version_id=?", Long.class, second.version().getId())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_cost_item WHERE cost_run_version_id=?", Long.class, second.version().getId())).isZero();
    for (var binding : second.bindings().values())
      assertThat(priceStorage.preparationLink("COMMERCIAL", binding.financialItemId()).monthlyPriceId()).isNull();
    assertThat(costing.saveCalculatedMaterials("COMMERCIAL", second.version().getId(), second.plan(), second.ordinary(),
        second.technical(), second.customerPrices(), second.bindings(), numbers).snapshot()).isNotNull();
  }

  static Fixture anotherCustomer(Fixture first, long item, long profile) {
    jdbc.update("INSERT INTO lp_quote_tech_data_version(id) VALUES(?)", profile);
    int n = fixtureNumber.incrementAndGet(); String month = first.version().getPricingMonth();
    var version = versionService.createRunning("OWN-OA-" + item, item, "TOP", month, month, "FIN-OWN-" + n, "COMMERCIAL");
    version.setFinancePricePrepareNo("FIN-OWN-" + n); version.setOaPricePrepareNo("OA-OWN-" + n); versionMapper.updateById(version);
    var bindings = new HashMap<String, PreparationBinding>();
    var pairs = MonthlyScenarioTestSupport.create(null).calculate(first.plan().selections(), first.copper());
    for (var use : first.plan().uses()) {
      var pair = pairs.get(first.plan().selections().get(use.request()).monthlyPriceId());
      bindings.put(use.path(), new PreparationBinding(
          prepare(version, use.request(), version.getFinancePricePrepareNo(), use.path(), use.cumulativeQuantity(), pair.financialPrice()),
          prepare(version, use.request(), version.getOaPricePrepareNo(), use.path(), use.cumulativeQuantity(), pair.customerPrice())));
    }
    var t = first.technical();
    var technical = new EffectiveTechnicalDataInput(item, profile, 1, month, t.sourceType(), "own-" + item, t.retrievedAt(),
        false, false, false, null, null, null, List.of(), List.of(), List.of(), t.productFees(), null);
    return new Fixture(version, first.plan(), first.ordinary(), technical, first.copper(), bindings, first.linkedRuleId());
  }

}
