package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyBomPricingScope.Node;
import com.sanhua.marketingcost.service.pricing.household.*;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.*;
import com.sanhua.marketingcost.service.settlement.*;
import com.sanhua.marketingcost.service.rule.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.*;

/** Real production mapper/transactions. Source changes and lock interleavings are explicitly controlled. */
@Tag("integration")
class MonthlyBomPricePreparationIntegrationTest {
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
      System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("mpp06_scope_test").withUsername("root").withPassword("mpp06-test-only")
      .withConfigurationOverride("monthly-pricing/mysql");
  static JdbcTemplate jdbc;
  static DataSourceTransactionManager transactions;
  static MonthlyPriceDocumentCodec codec;
  static MonthlyPriceStorageMapper mapper;
  static MonthlyPriceLinkMapper links;
  static MonthlyPriceStorage storage;
  static HouseholdMonthlyPriceSource household;
  static HouseholdInquiryService inquiries;
  static AtomicInteger sqlCalls = new AtomicInteger();
  static BomSettlementRowBuildEngine engine;

  @BeforeAll static void database() throws Exception {
    MYSQL.start();
    var raw = new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
    var ds = new DelegatingDataSource(raw) {
      @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
        var connection = super.getConnection();
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{java.sql.Connection.class},(proxy,method,args) -> {
              if (method.getName().equals("prepareStatement")) sqlCalls.incrementAndGet();
              try { return method.invoke(connection,args); }
              catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
            });
      }
    };
    jdbc = new JdbcTemplate(ds);
    assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));
    jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for (String file : List.of("V342__monthly_material_price_storage.sql", "V344__monthly_material_price_single_table.sql")) {
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+file),"/tmp/patch.sql");
      var applied = MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp06_scope_test < /tmp/patch.sql");
      assertThat(applied.getExitCode()).withFailMessage(applied.getStderr()).isZero();
    }
    var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true);
    config.addMapper(MonthlyPriceStorageMapper.class); config.addMapper(MonthlyPriceLinkMapper.class);
    config.addMapper(HouseholdInquiryStorageMapper.class); config.addMapper(com.sanhua.marketingcost.mapper.household.HouseholdInquiryQueryMapper.class);
    var factory = new SqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
    var sql = new SqlSessionTemplate(factory.getObject());
    codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
    mapper = sql.getMapper(MonthlyPriceStorageMapper.class); links = sql.getMapper(MonthlyPriceLinkMapper.class);
    storage = new MonthlyPriceStorage(mapper,codec,links); transactions = new DataSourceTransactionManager(ds);
    var sourceStorage = new HouseholdInquiryStorage(sql.getMapper(HouseholdInquiryStorageMapper.class),codec);
    household = new HouseholdMonthlyPriceSource(sourceStorage,codec, org.mockito.Mockito.mock(com.sanhua.marketingcost.service.MaterialPricePriorityService.class));
    inquiries = new HouseholdInquiryService(sql.getMapper(com.sanhua.marketingcost.mapper.household.HouseholdInquiryQueryMapper.class),
        sourceStorage,new HouseholdInquiryWorkbookParser(),new HouseholdInquiryFiles(java.nio.file.Files.createTempDirectory("mpp06-household-").toString()),
        codec,transactions,new ObjectMapper());
    engine = new BomSettlementRowBuildEngine(new BomSettlementRuleMatcher(new BomSettlementRuleConditionEvaluator(new ObjectMapper())),
        new BomByproductCostRuleMatcher(new BomByproductCostRuleConditionEvaluator(new ObjectMapper())));
  }
  @AfterAll static void stop() { MYSQL.stop(); }

  static Context context() { return new Context("COMMERCIAL","MPP06",1L,"TOP",YearMonth.of(2026,10),LocalDateTime.of(2026,10,23,0,0),"test","210"); }
  static MonthlyPriceRequest request(String code) { return request(code,"210"); }
  static MonthlyPriceRequest request(String code,String org) { return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",code,null,"件","CNY",TaxBasis.EXCLUDED,null),org,NonCuConditions.empty(),BigDecimal.ONE); }
  static MonthlyPriceApplicability coverage(Set<String> orgs) { return MonthlyPriceApplicability.of(new MonthlyPriceCoverage(orgs,QuantityInterval.all(),ConditionDomain.any())); }
  static Selection normal(String price, String source, Set<String> orgs, Set<MonthlyPriceRequest> deps) {
    return new Selection(null,coverage(orgs),PriceFact.normal(new BigDecimal(price),SourceEvidence.ofJson("{\"sourceKind\":\""+source+"\",\"price\":"+price+"}"),deps));
  }
  static Selection normal(String price) { return normal(price,"FIXED",Set.of("210","220"),Set.of()); }
  static String code() { return "M06-"+UUID.randomUUID().toString().substring(0,12); }
  static MonthlyAdoptionPlan single(MonthlyPriceRequest request,Selection value) { return new MonthlyAdoptionPlan(Map.of(request,value),List.of(new Use("TOP/A",request,BigDecimal.ONE)),Set.of()); }
  static long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class); }
  static long materialCount(String code) { return jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Long.class,code); }

  static void publish(String material,String price) throws Exception {
    try (var stream = MonthlyBomPricePreparationIntegrationTest.class.getResourceAsStream("/household-inquiry/real-20261023.xls");
         var book = org.apache.poi.ss.usermodel.WorkbookFactory.create(stream);
         var bytes = new java.io.ByteArrayOutputStream()) {
      var sheet = book.getSheet("10月线圈询价");
      for (int row=sheet.getLastRowNum();row>2;row--) if (sheet.getRow(row)!=null) sheet.removeRow(sheet.getRow(row));
      sheet.getRow(2).getCell(0).setCellValue(material); sheet.getRow(2).getCell(4).setCellValue(price);
      book.write(bytes);
      var preview = inquiries.preview("COMMERCIAL","test",bytes.toByteArray(),"controlled-single-row.xls","2026-10");
      inquiries.publish("COMMERCIAL","test",new PublishRequest(preview.previewId(),preview.publishRequestId(),preview.previewFingerprint()));
    }
  }

  static MonthlyMaterialPriceService module(Map<MonthlyPriceRequest,Selection> sources, Set<MonthlyPriceRequest> observed) {
    return module(storage,sources,observed);
  }
  static MonthlyMaterialPriceService module(MonthlyPriceStorage store, Map<MonthlyPriceRequest,Selection> sources, Set<MonthlyPriceRequest> observed) {
    return new MonthlyMaterialPriceService(store,transactions,codec,(context,requests,dependencies) -> {
      observed.addAll(requests);
      var source = household.read(context,requests);
      var values = new LinkedHashMap<>(source.values()); var problems = new LinkedHashMap<>(source.problems());
      for (var request : requests) if (!values.containsKey(request) && !problems.containsKey(request)) {
        var selected = sources.get(request);
        if (selected == null) { problems.put(request,new Problem(MonthlyAdoptionRetryPolicy.Failure.MISSING,"controlled missing source")); continue; }
        if (!selected.fact().dependencies().isEmpty()) {
          var children = dependencies.apply(selected.fact().dependencies()); values.putAll(children.values()); problems.putAll(children.problems());
          if (!children.problems().isEmpty()) { problems.put(request,new Problem(MonthlyAdoptionRetryPolicy.Failure.MISSING,"controlled missing formula dependency")); continue; }
          var amount = children.values().values().stream().map(v -> v.selection().fact().price()).reduce(BigDecimal.ZERO,BigDecimal::add).multiply(new BigDecimal("3"));
          selected = new Selection(null,selected.applicability(),PriceFact.normal(amount,SourceEvidence.ofJson("{\"sourceKind\":\"LINKED_LEGACY\",\"factor\":3}"),selected.fact().dependencies()));
        }
        values.put(request,new Candidate(selected,null));
      }
      return new Candidates(values,problems);
    });
  }

  static List<Node> pricedLeafTree(MonthlyPriceRequest a) {
    return List.of(new Node("TOP","TOP",null,request("TOP"),BigDecimal.ONE),
        new Node("TOP/A",a.family().materialCode(),"TOP",a,BigDecimal.ONE));
  }
  static MonthlyAdoptionPlan plan(MonthlyMaterialPrices module,List<Node> nodes,MonthlyPriceRequest a) {
    // This is an actual purchased leaf. Its scrap formula dependency is not a child BOM path.
    // Structural parents are covered separately through the real settlement engine below.
    var resolved = module.resolveCandidates(context(),Set.of(a));
    var scope = MonthlyBomPricingScope.select(nodes,resolved);
    var ordinary = scope.household().isEmpty() ? List.of(new Use("TOP/A",a,BigDecimal.ONE)) : List.<Use>of();
    return MonthlyBomPricingScope.adoption(scope,ordinary,resolved);
  }

  @ParameterizedTest @ValueSource(booleans={true,false})
  void normalAndHouseholdOnActualPricedLeafReplanFormulaDependenciesBeforeCommit(boolean householdWins) throws Exception {
    var a=request(code()); var b=request(code());
    var sources=Map.of(a,normal("30","LINKED_LEGACY",Set.of("210","220"),Set.of(b)),b,normal("10"));
    var original=pricedLeafTree(a);var module=module(sources,ConcurrentHashMap.newKeySet());
    var normalPlan=plan(module,original,a); publish(a.family().materialCode(),"20.9853"); var householdPlan=plan(module,original,a);
    var winning=householdWins?householdPlan:normalPlan; var losing=householdWins?normalPlan:householdPlan;
    var race = race(sources,winning,losing,a,service -> plan(service,original,a));
    var left=race.get(0);var right=race.get(1);
    assertThat(left.plan()).isEqualTo(right.plan());
    assertThat(left.plan().selections().get(a).fact().type()).isEqualTo(householdWins?PricingType.HOUSEHOLD_INQUIRY:PricingType.NORMAL);
    assertThat(materialCount(a.family().materialCode())).isEqualTo(1);
    assertThat(materialCount(b.family().materialCode())).isEqualTo(householdWins?0:1);
    assertThat(left.plan().suppressedPaths()).isEmpty();
    assertThat(firstUse(left.plan().selections().get(a).monthlyPriceId()).dependencies()).hasSize(householdWins?0:1);
    assertReferences(left.plan());
  }

  @Test void changedFormulaDependencyRollsBackStaleLinkedPriceThenRecalculatesFromSavedDependency() {
    var a=request(code());var b=request(code());
    var sources=Map.of(a,normal("30","LINKED_LEGACY",Set.of("210","220"),Set.of(b)),b,normal("10"));
    var module=module(sources,new HashSet<>());var nodes=pricedLeafTree(a);var stale=plan(module,nodes,a);
    module.adopt(context(),single(b,normal("7")));
    assertThatThrownBy(() -> module.adopt(context(),stale)).isInstanceOfSatisfying(MonthlyPriceProblem.class,
        error -> assertThat(error.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED));
    assertThat(materialCount(a.family().materialCode())).isZero();
    var saved=new MonthlyAdoptionExecutor(module).execute(context(),budget -> plan(module,nodes,a)).plan();
    assertThat(saved.selections().get(a).fact().price()).isEqualByComparingTo("21");
    assertThat(saved.selections().get(b).fact().price()).isEqualByComparingTo("7");
    assertThat(firstUse(saved.selections().get(a).monthlyPriceId()).dependencies()).singleElement()
        .satisfies(dependency -> assertThat(dependency.monthlyPriceId()).isEqualTo(saved.selections().get(b).monthlyPriceId()));
    assertReferences(saved);
  }

  @Test void failureInOnePlanLeavesNoNewPricesOrReferences() {
    var a=request(code());var b=request(code());var module=module(Map.of(),new HashSet<>());
    var invalid=new MonthlyAdoptionPlan(Map.of(a,normal("10"),b,normal("20")),List.of(new Use("TOP/A",a,BigDecimal.ONE)),Set.of());
    long before=count();long refs=jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_item",Long.class);
    assertThatThrownBy(() -> module.adopt(context(),invalid)).isInstanceOf(MonthlyPriceProblem.class);
    assertThat(count()).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_item",Long.class)).isEqualTo(refs);
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> sharingOrders() {
    return java.util.stream.Stream.of("SCRAP","SETTLE_FIXED").flatMap(kind -> java.util.stream.Stream.of(true,false)
        .map(sharedWins -> org.junit.jupiter.params.provider.Arguments.of(kind,sharedWins)));
  }
  @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("sharingOrders")
  void sharedAndExclusiveSourcesCompeteAcrossConnectionsInBothOrders(String kind,boolean sharedWins) throws Exception {
    String material=code();var left=request(material,"210");var right=request(material,"220");
    var shared=normal("10",kind,Set.of("210","220"),Set.of());var exclusive=normal("7","FIXED",Set.of("210"),Set.of());
    var sources=Map.of(left,shared,right,shared);
    var sharedPlan=single(left,shared);var exclusivePlan=single(left,exclusive);
    var won=sharedWins?sharedPlan:exclusivePlan;var lost=sharedWins?exclusivePlan:sharedPlan;
    var results=race(sources,won,lost,left,service -> single(left,service.resolveCandidates(context(),Set.of(left)).values().get(left).selection()));
    assertThat(results.get(0)).isEqualTo(results.get(1));
    var module=module(sources,new HashSet<>());
    var reused=new MonthlyAdoptionExecutor(module).execute(context(),budget -> single(right,module.resolveCandidates(context(),Set.of(right)).values().get(right).selection())).plan();
    var leftSaved=results.get(0).plan().selections().get(left);var rightSaved=reused.selections().get(right);
    assertThat(leftSaved.fact().price()).isEqualByComparingTo(sharedWins?"10":"7");
    assertThat(rightSaved.fact().price()).isEqualByComparingTo("10");
    assertThat(rightSaved.applicability().matches(left)).isEqualTo(sharedWins);
    assertThat(materialCount(material)).isEqualTo(sharedWins?1:2);
    if(sharedWins)assertThat(rightSaved.monthlyPriceId()).isEqualTo(leftSaved.monthlyPriceId());
    assertReferences(results.get(0).plan());assertReferences(reused);
  }

  @Test void householdWinnerOnAnUnpricedStructureAncestorRollsBackChildAndRebuildsTheActualEngineScope() throws Exception {
    String a=code(),b=code();var sources=Map.of(request(b),normal("10"));
    var module=module(sources,new HashSet<>());var app=new MonthlyBomPricePreparation(module,household,engine);
    var bom=bom(List.of(bomNode("TOP",null,"/TOP/",0,"1",false),bomNode(a,"TOP","/TOP/A/",1,"1",false),
        bomNode(b,a,"/TOP/A/B/",2,"2.5",true)));
    var inputs=inputs(bom);var callback=ordinaryUses(inputs);var stale=app.preview(context(),bom,inputs,callback).plan();
    assertThat(stale.selections()).containsOnlyKeys(request(b));
    assertThat(stale.ordinaryScopeRequests()).contains(request(a));
    publish(a,"20.9853");var winning=app.preview(context(),bom,inputs,callback).plan();
    var result=race(sources,winning,stale,request(a),service -> new MonthlyBomPricePreparation(service,household,engine).preview(context(),bom,inputs,callback).plan());
    assertThat(result.get(0)).isEqualTo(result.get(1));
    assertThat(result.get(0).plan().suppressedPaths()).containsExactly("/TOP/A/B/");
    assertThat(materialCount(b)).isZero();assertThat(materialCount(a)).isEqualTo(1);assertReferences(result.get(0).plan());
  }

  @Test void scopeOnlyPlanStillChecksHouseholdWinnersAndRejectsForeignMonthGuards() throws Exception {
    String a=code();publish(a,"20.9853");var module=module(Map.of(),new HashSet<>());
    module.adopt(context(),single(request(a),module.resolveCandidates(context(),Set.of(request(a))).values().get(request(a)).selection()));
    long before=count();
    assertThatThrownBy(() -> module.adopt(context(),new MonthlyAdoptionPlan(Map.of(),List.of(),Set.of(),Set.of(request(a)))))
        .isInstanceOfSatisfying(MonthlyPriceProblem.class,error -> assertThat(error.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.REPLAN_REQUIRED));
    var family=new MonthlyPriceFamilyKey(YearMonth.of(2026,11),"COMMERCIAL",a,null,"件","CNY",TaxBasis.EXCLUDED,null);
    var foreign=new MonthlyPriceRequest(family,"210",NonCuConditions.empty(),BigDecimal.ONE);
    assertThatThrownBy(() -> module.adopt(context(),new MonthlyAdoptionPlan(Map.of(),List.of(),Set.of(),Set.of(foreign))))
        .isInstanceOfSatisfying(MonthlyPriceProblem.class,error -> assertThat(error.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT));
    assertThat(count()).isEqualTo(before);
  }

  @Test void contradictoryHouseholdCandidateAndOrdinaryGuardRollBackEveryInsert() throws Exception {
    String a=code();publish(a,"20.9853");var module=module(Map.of(),new HashSet<>());
    var candidate=module.resolveCandidates(context(),Set.of(request(a))).values().get(request(a)).selection();
    var invalid=new MonthlyAdoptionPlan(Map.of(request(a),candidate),List.of(new Use("TOP/A",request(a),BigDecimal.ONE)),
        Set.of(),Set.of(request(a,"220")));
    long before=count();
    assertThatThrownBy(() -> module.adopt(context(),invalid)).isInstanceOfSatisfying(MonthlyPriceProblem.class,
        error -> assertThat(error.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_PLAN));
    assertThat(count()).isEqualTo(before);
  }

  @Test void householdBoundaryPrecedesExistingSettlementFilteringAndSuppressesMissingChildren() throws Exception {
    String a=code(),b=code();publish(a,"20.9853");
    var observed=new HashSet<MonthlyPriceRequest>();var module=module(Map.of(request(b),normal("10")),observed);
    var app=new MonthlyBomPricePreparation(module,household,engine);
    var bom=bom(List.of(bomNode("TOP",null,"/TOP/",0,"1",false),bomNode(a,"TOP","/TOP/A/",1,"0.5",false),
        bomNode("MISSING",a,"/TOP/A/B/",2,"1.25",true),bomNode(b,"TOP","/TOP/B/",1,"3",true)));
    var inputs=inputs(bom);
    inputs.put("/TOP/A/B/",new MonthlyBomPricePreparation.PriceInput(request("MISSING"),BigDecimal.ONE,MonthlyBomPricePreparation.PricePurpose.SCRAP));
    var callback=ordinaryUses(inputs);
    long before=count();var preview=app.preview(context(),bom,inputs,callback);
    assertThat(count()).isEqualTo(before);
    assertThat(observed).doesNotContain(request("MISSING"),request("TOP"));
    assertThat(preview.scope().suppressedPaths()).containsExactly("/TOP/A/B/");
    assertThat(preview.ordinaryRows().costingRows()).extracting(row -> row.getMaterialCode()).containsExactly(b);
    var prepared=app.prepare(context(),bom,inputs,callback);
    assertThat(prepared.adoption().plan().selections()).containsOnlyKeys(request(a),request(b));
    assertThat(prepared.adoption().plan().uses()).extracting(Use::cumulativeQuantity).containsExactly(new BigDecimal("0.5"),new BigDecimal("3"));
    assertThat(materialCount("MISSING")).isZero();assertReferences(prepared.adoption().plan());
    long adopted=prepared.adoption().plan().selections().get(request(a)).monthlyPriceId();
    publish(a,"30"); observed.clear();var reused=app.prepare(context(),bom,inputs,callback);
    assertThat(reused.adoption().plan().selections().get(request(a)).monthlyPriceId()).isEqualTo(adopted);
    assertThat(reused.adoption().plan().selections().get(request(a)).fact().price()).isEqualByComparingTo("20.9853");
    assertThat(observed).isEmpty();
  }

  @Test void explicitUnitConversionUsesCurrentCumulativeQuantityAndKeepsPriceIdentity() throws Exception {
    String a=code();publish(a,"20.9853");var module=module(Map.of(),new HashSet<>());var app=new MonthlyBomPricePreparation(module,household,engine);
    var bom=bom(List.of(bomNode("TOP",null,"/TOP/",0,"1",false),bomNode(a,"TOP","/TOP/A/",1,"0.0025",true)));
    var inputs=inputs(bom);inputs.put("/TOP/A/",new MonthlyBomPricePreparation.PriceInput(request(a),new BigDecimal("1000"),MonthlyBomPricePreparation.PricePurpose.MATERIAL));
    var first=app.prepare(context(),bom,inputs,ordinaryUses(inputs));
    assertThat(first.adoption().plan().uses().getFirst().cumulativeQuantity()).isEqualByComparingTo("2.5");
    assertThat(first.adoption().plan().selections().get(request(a)).fact().price()).isEqualByComparingTo("20.9853");
    var changed=bom(List.of(bomNode("TOP",null,"/TOP/",0,"1",false),bomNode(a,"TOP","/TOP/A/",1,"0.005",true)));
    var second=app.prepare(context(),changed,inputs,ordinaryUses(inputs));
    assertThat(second.adoption().plan().uses().getFirst().cumulativeQuantity()).isEqualByComparingTo("5");
    assertThat(second.adoption().plan().selections()).isEqualTo(first.adoption().plan().selections());
  }

  @Test void independentMissingPathStillBlocksAndDoesNotAdoptValidHousehold() throws Exception {
    String a=code(),b=code();publish(a,"20.9853");var app=new MonthlyBomPricePreparation(module(Map.of(),new HashSet<>()),household,engine);
    var bom=bom(List.of(bomNode("TOP",null,"/TOP/",0,"1",false),bomNode(a,"TOP","/TOP/A/",1,"1",true),bomNode(b,"TOP","/TOP/B/",1,"3",true)));
    var inputs=inputs(bom);long before=count();
    assertThatThrownBy(() -> app.prepare(context(),bom,inputs,ordinaryUses(inputs))).isInstanceOfSatisfying(MonthlyPriceProblem.class,
        error -> assertThat(error.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING));
    assertThat(count()).isEqualTo(before);
  }

  @Test void savedNormalSurvivesLaterHouseholdImport() throws Exception {
    String a=code();var module=module(Map.of(request(a),normal("10")),new HashSet<>());
    var app=new MonthlyBomPricePreparation(module,household,engine);
    var bom=bom(List.of(bomNode("TOP",null,"/TOP/",0,"1",false),bomNode(a,"TOP","/TOP/A/",1,"2",true)));
    var inputs=inputs(bom);var first=app.prepare(context(),bom,inputs,ordinaryUses(inputs));publish(a,"20.9853");
    var second=app.prepare(context(),bom,inputs,ordinaryUses(inputs));
    assertThat(second.scope().household()).isEmpty();assertThat(second.adoption()).isEqualTo(first.adoption());
    assertThat(second.ordinaryRows().costingRows()).hasSize(1);
  }

  @Test void structureParentUsesChildrenUntilHouseholdExistsAndNeverAdoptsItsOrdinarySource() throws Exception {
    String a=code(),b=code();var observed=new HashSet<MonthlyPriceRequest>();
    // Even an unrelated ordinary source for A must not turn the structural parent into a purchase.
    var sources=new HashMap<>(Map.of(request(a),normal("999"),request(b),normal("10")));
    var module=module(sources,observed);var app=new MonthlyBomPricePreparation(module,household,engine);
    var bom=bom(List.of(bomNode("TOP",null,"/TOP/",0,"1",false),bomNode(a,"TOP","/TOP/A/",1,"1",false),
        bomNode(b,a,"/TOP/A/B/",2,"2.5",true)));
    var inputs=inputs(bom);var callback=ordinaryUses(inputs);
    assertThatThrownBy(() -> app.prepare(context(),bom,inputs,(visible,rows) -> List.of(new Use("/TOP/A/",request(a),BigDecimal.ONE))))
        .isInstanceOfSatisfying(MonthlyPriceProblem.class,error -> assertThat(error.reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT));
    assertThat(observed).isEmpty();assertThat(materialCount(a)).isZero();
    var first=app.prepare(context(),bom,inputs,callback);
    assertThat(first.ordinaryRows().costingRows()).extracting(row -> row.getMaterialCode()).containsExactly(b);
    assertThat(first.adoption().plan().selections()).containsOnlyKeys(request(b));
    assertThat(observed).containsExactly(request(b));assertThat(materialCount(a)).isZero();
    long childId=first.adoption().plan().selections().get(request(b)).monthlyPriceId();
    publish(a,"20.9853");observed.clear();sources.put(request(b),normal("15"));
    var other=new Context("COMMERCIAL","MPP06",2L,"TOP",context().month(),context().priceAsOfTime(),"test","210");
    var second=app.prepare(other,bom,inputs,callback);
    assertThat(second.ordinaryRows().costingRows()).isEmpty();
    assertThat(second.scope().household()).extracting(Use::request).containsExactly(request(a));
    assertThat(second.adoption().plan().selections()).containsOnlyKeys(request(a));
    assertThat(second.scope().suppressedPaths()).containsExactly("/TOP/A/B/");
    assertThat(observed).doesNotContain(request(b));
    // B was legitimately used by the first quotation; the later cut must not delete its month price.
    assertThat(module.lookup(Set.of(request(b))).values().get(request(b)).monthlyPriceId()).isEqualTo(childId);
    assertThat(module.lookup(Set.of(request(b))).values().get(request(b)).fact().price()).isEqualByComparingTo("10");
    assertReferences(first.adoption().plan());assertReferences(second.adoption().plan());
  }

  @Test void lookupScalesWithBatchesAndUniqueRequestsNotBomNodes() throws Exception {
    String a=code();publish(a,"20.9853");var module=module(Map.of(),new HashSet<>());var app=new MonthlyBomPricePreparation(module,household,engine);
    var nodes=new ArrayList<BomSettlementNode>();nodes.add(bomNode("TOP",null,"/TOP/",0,"1",false));
    for(int i=0;i<1000;i++)nodes.add(bomNode(a,"TOP","/TOP/"+i+"/",1,"0.0025",true));
    var bom=bom(nodes);var inputs=inputs(bom);
    sqlCalls.set(0);var result=app.prepare(context(),bom,inputs,ordinaryUses(inputs));int statements=sqlCalls.get();
    assertThat(result.adoption().plan().selections()).hasSize(1);assertThat(result.adoption().plan().uses()).hasSize(1000);
    assertThat(MonthlyBomPricingScope.quantities(result.adoption().plan()).values().iterator().next()).isEqualByComparingTo("2.5");
    assertThat(statements).as("actual SQL calls for 1000 repeat-material paths").isLessThanOrEqualTo(30);
  }

  static BomSettlementBuildRequest bom(List<BomSettlementNode> nodes) {
    return new BomSettlementBuildRequest("MPP06","TOP",LocalDate.of(2026,10,23),"2026-10","MPP06",LocalDateTime.of(2026,10,23,0,0),"COMMERCIAL","主制造",nodes,List.of());
  }
  static BomSettlementNode bomNode(String code,String parent,String path,int level,String qty,boolean leaf) {
    return new BomSettlementNode(null,"TOP",parent,code,level,path,new BigDecimal(qty),new BigDecimal(qty),code,null,
        leaf?"采购件":"制造件",leaf?"采购件":"制造件",null,null,null,null,"主制造","v1",1,leaf?1:0,
        null,null,null,"210","COMMERCIAL","COMMERCIAL",null);
  }
  static Map<String,MonthlyBomPricePreparation.PriceInput> inputs(BomSettlementBuildRequest bom) {
    var result=new LinkedHashMap<String,MonthlyBomPricePreparation.PriceInput>();
    bom.nodes().forEach(node -> result.put(node.path(),new MonthlyBomPricePreparation.PriceInput(request(node.materialCode()),BigDecimal.ONE,MonthlyBomPricePreparation.PricePurpose.MATERIAL)));
    return result;
  }
  static MonthlyBomPricePreparation.OrdinaryUses ordinaryUses(Map<String,MonthlyBomPricePreparation.PriceInput> inputs) {
    return (visible,rows) -> rows.costingRows().stream().map(row -> new Use(row.getPath(),inputs.get(row.getPath()).request(),
        row.getQtyPerTop().multiply(inputs.get(row.getPath()).pricingUnitFactor()))).toList();
  }
  static MonthlyMaterialPriceService.FirstUse firstUse(long id) {
    return codec.decode(codec.wrap(storage.readByIds("COMMERCIAL",List.of(id)).getFirst().firstUseJson()),MonthlyMaterialPriceService.FirstUse.class);
  }
  static void assertReferences(MonthlyAdoptionPlan plan) {
    new TransactionTemplate(transactions).executeWithoutResult(status -> {
      for (var use:plan.uses()) {
        var selection=plan.selections().get(use.request());String batch="MPP06-"+UUID.randomUUID();
        jdbc.update("INSERT INTO lp_price_prepare_item(prepare_no,period_month,oa_no,oa_form_item_id,top_product_code,material_code,item_type,status,current_flag,business_unit_type,quantity,price_org_code) VALUES (?,'2026-10','MPP06',1,'TOP',?,'NORMAL','READY',1,'COMMERCIAL',?,?)",batch,use.request().family().materialCode(),use.cumulativeQuantity(),use.request().priceOrgCode());
        long id=jdbc.queryForObject("SELECT id FROM lp_price_prepare_item WHERE prepare_no=?",Long.class,batch);
        storage.attachPreparation("COMMERCIAL",id,selection.monthlyPriceId(),selection.fact().type());
        assertThat(storage.preparationLink("COMMERCIAL",id).monthlyPriceId()).isEqualTo(selection.monthlyPriceId());
        assertThat(storage.preparationLink("COMMERCIAL",id).monthlyPricingType()).isEqualTo(selection.fact().type().name());
      }
    });
  }
  static List<AdoptionResult> race(Map<MonthlyPriceRequest,Selection> sources, MonthlyAdoptionPlan winning,
      MonthlyAdoptionPlan losing, MonthlyPriceRequest latchRequest,
      java.util.function.Function<MonthlyMaterialPrices,MonthlyAdoptionPlan> replan) throws Exception {
    var locked=new CountDownLatch(1);var loserEntered=new CountDownLatch(1);
    var winningStore=new MonthlyPriceStorage(mapper,codec,links) {
      @Override public StoredPrice insertPrice(MonthlyPriceFamilyKey family, MonthlyPriceApplicability applicability,
          PriceFact fact, Long householdSourceId, String firstUseJson, String actor) {
        var row=super.insertPrice(family,applicability,fact,householdSourceId,firstUseJson,actor);
        if(family.equals(latchRequest.family())) {locked.countDown();await(loserEntered);}
        return row;
      }
    };
    var losingStore=new MonthlyPriceStorage(mapper,codec,links) {
      @Override public void lockFamilies(Collection<MonthlyPriceFamilyKey> families) {
        loserEntered.countDown();super.lockFamilies(families);
      }
    };
    var winner=module(winningStore,sources,ConcurrentHashMap.newKeySet());var loser=module(losingStore,sources,ConcurrentHashMap.newKeySet());
    var attempts=new AtomicInteger();
    try(var pool=Executors.newFixedThreadPool(2)) {
      var won=pool.submit(() -> winner.adopt(context(),winning));await(locked);
      var lost=pool.submit(() -> new MonthlyAdoptionExecutor(loser).execute(context(),budget -> attempts.getAndIncrement()==0?losing:replan.apply(loser)));
      var result=List.of(won.get(10,TimeUnit.SECONDS),lost.get(10,TimeUnit.SECONDS));
      assertThat(attempts).hasValueGreaterThanOrEqualTo(2);return result;
    }
  }
  static void await(CountDownLatch latch) {
    try { assertThat(latch.await(5,TimeUnit.SECONDS)).as("controlled transaction barrier").isTrue(); }
    catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
  }
}
