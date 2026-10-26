package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.registry.PreparedFormulaInputs;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.*;

/** Production bridge/module/scrap-source composition; parent dispatch is an explicitly owned test carrier. */
@Tag("integration")
class MonthlyFormulaScrapDependenciesIntegrationTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql")).withDatabaseName("mpp_formula_scrap_deps").withUsername("root").withPassword("mpp-formula-scrap-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static JdbcTemplate jdbc;static MonthlyMaterialPriceService module;static MonthlyScrapPriceSource scraps;static MonthlyPriceDocumentCodec codec;static MonthlyFormulaScrapDependencies bridge;static AtomicInteger sourceCalls=new AtomicInteger(),parentCalls=new AtomicInteger();static Map<String,List<MaterialScrapRef>> programs=new HashMap<>();
  @BeforeAll static void database() throws Exception {
    MYSQL.start();var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/scrap-source-schema.sql")).execute(ds);jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(ds);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql")){MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/formula-scrap.sql");var run=MYSQL.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 mpp_formula_scrap_deps < /tmp/formula-scrap.sql");assertThat(run.getExitCode()).withFailMessage(run.getStderr()).isZero();}
    var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);for(var mapper:List.of(MonthlyScrapSourceMapper.class,MonthlySupplierInputMapper.class,MonthlyPriceStorageMapper.class,MonthlyPriceLinkMapper.class,FactorIdentityMapper.class,FactorUploadBatchMapper.class,FactorMonthlyPriceMapper.class,FactorRowRefMapper.class))config.addMapper(mapper);var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);var sql=new SqlSessionTemplate(factory.getObject());codec=new MonthlyPriceDocumentCodec(new ObjectMapper());bridge=new MonthlyFormulaScrapDependencies(codec);
    var finance=new FinanceFactorPriceReader(sql.getMapper(FactorUploadBatchMapper.class),sql.getMapper(FactorMonthlyPriceMapper.class),sql.getMapper(FactorIdentityMapper.class),sql.getMapper(FactorRowRefMapper.class));scraps=new MonthlyScrapPriceSource(sql.getMapper(MonthlyScrapSourceMapper.class),sql.getMapper(MonthlySupplierInputMapper.class),new SupplierPriceSelectionService(null,null),finance,new com.sanhua.marketingcost.service.scrap.ScrapPriceCalculator(new com.sanhua.marketingcost.service.scrap.ScrapPriceRuleCodec(new ObjectMapper().findAndRegisterModules())),codec);
    module=new MonthlyMaterialPriceService(new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class)),new DataSourceTransactionManager(ds),codec,MonthlyFormulaScrapDependenciesIntegrationTest::dispatch);
  }
  @AfterAll static void stop(){MYSQL.stop();}
  static Context context(){return new Context("COMMERCIAL","MPP05-SCRAP-DEPS",1L,"TOP",YearMonth.of(2026,10),LocalDateTime.of(2026,10,23,9,0),"fixture","210");}
  static MonthlyPriceRequest request(String code,String org,String unit,NonCuConditions terms){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",code,null,unit,"CNY",TaxBasis.EXCLUDED,null),org,terms,BigDecimal.ONE);}
  static String code(String prefix){return prefix+UUID.randomUUID();}
  static MonthlyPriceApplicability shared(){return MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any()));}
  static MaterialScrapRef program(String parent,String child){var ref=new MaterialScrapRef();ref.setId(31L);ref.setMaterialCode(parent);ref.setBusinessUnitType("COMMERCIAL");ref.setScrapCode(child);ref.setRatio(new BigDecimal("0.5"));ref.setSourceDocNo("TEST_PARENT_MAPPING");programs.put(parent,List.of(ref));return ref;}
  static void scrap(String code,String supplier,String amount){jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,supplier_code,supplier_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,source_file_name,source_file_sha256,source_sheet,source_row,source_json,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL','2026-10',?,'test scrap',?,?,'FIXED',?,'{}','IMPORT','2026-10',?,'scrap-original.xlsx',?,'废料表',8,?,'fixture',NOW(),'fixture',NOW())",code,supplier,supplier,new BigDecimal(amount),"c".repeat(64),"d".repeat(64),"{\"fixture\":\"original scrap rule\"}");}
  static Candidates dispatch(Context context,Set<MonthlyPriceRequest> requests,java.util.function.Function<Set<MonthlyPriceRequest>,Candidates> dependencies){
    var parents=new LinkedHashMap<MonthlyPriceRequest,List<MaterialScrapRef>>();var children=new LinkedHashSet<MonthlyPriceRequest>();for(var request:requests){if(programs.containsKey(request.family().materialCode()))parents.put(request,programs.get(request.family().materialCode()));else children.add(request);}
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    if(!children.isEmpty()){sourceCalls.incrementAndGet();var read=scraps.read(context,children);values.putAll(read.values());problems.putAll(read.problems());for(var child:children)if(!values.containsKey(child)&&!problems.containsKey(child))problems.put(child,new Problem(MonthlyAdoptionRetryPolicy.Failure.MISSING,"No current scrap source"));}
    if(!parents.isEmpty()){
      parentCalls.incrementAndGet();var inputs=bridge.resolve(context,parents,dependencies);problems.putAll(inputs.problems());
      for(var entry:inputs.values().entrySet()){
        var parent=entry.getKey();var input=entry.getValue();var definition=PreparedFormulaInputsTest.variable("scrap_price","废料价","DERIVED","{\"strategy\":\"SCRAP_REF\"}");var vat=PreparedFormulaInputsTest.variable("vat_rate","税率","CONST","{\"value\":\"0.13\"}");var prepared=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(List.of(definition,vat),List.of(),List.of()),new PreparedFormulaInputs.Financial(null,Map.of(),Set.of()),input.scrap());var rule=PreparedFormulaInputsTest.rule();rule.setMaterialCode(parent.family().materialCode());rule.setOrgCode(parent.priceOrgCode());rule.setFormulaExpr("废料价");var calculation=new PreparedFormulaInputsTest().service().previewPrepared(rule,"2026-10",context.priceAsOfTime().toLocalDate(),prepared);assertThat(calculation.getError()).isNull();
        var own=new MonthlyPriceCoverage(Set.of(parent.priceOrgCode()),QuantityInterval.all(),ConditionDomain.any());var fragments=input.availability().fragments().stream().map(own::intersection).flatMap(Optional::stream).toList();var fact=PriceFact.normal(calculation.getResult().setScale(6,RoundingMode.HALF_UP),SourceEvidence.ofJson(codec.unwrap(codec.encode(Map.of("snapshotSchema",1,"sourceKind","TEST_PARENT_FORMULA_DISPATCH","calculation",calculation.getInputSnapshotJson())))),input.dependencies());values.put(parent,new Candidate(new Selection(null,new MonthlyPriceApplicability(fragments),fact),null));
        values.putAll(input.resolvedDependencies());
      }
    }
    return new Candidates(values,problems);
  }
  static MonthlyAdoptionPlan plan(Candidates candidates,Set<MonthlyPriceRequest> roots){var selections=new LinkedHashMap<MonthlyPriceRequest,Selection>();candidates.values().forEach((request,value)->selections.put(request,value.selection()));return new MonthlyAdoptionPlan(selections,roots.stream().map(root->new Use("root/"+root.family().materialCode()+"/"+root.priceOrgCode(),root,BigDecimal.ONE)).toList(),Set.of());}
  static AdoptionResult adopt(Candidates candidates,Set<MonthlyPriceRequest> roots){return module.adopt(context(),plan(candidates,roots));}
  @Test void twoConsumersAdoptOneSharedChildAndPersistRealParentReferences(){
    String child=code("SCRAP-"),a=code("ROOT-"),b=code("ROOT-");scrap(child,"S1","10");program(a,child);program(b,child);var r1=request(a,"210","件",NonCuConditions.empty());var r2=request(b,"220","件",new NonCuConditions(new BoardTerms(new BigDecimal("3000"),null,true)));long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class);var candidates=module.resolveCandidates(context(),Set.of(r1,r2));assertThat(candidates.problems()).isEmpty();var receipt=adopt(candidates,Set.of(r1,r2));var c1=request(child,"210","KG",NonCuConditions.empty());var c2=request(child,"220","KG",r2.conditions());long childId=receipt.plan().selections().get(c1).monthlyPriceId();assertThat(receipt.plan().selections().get(c2).monthlyPriceId()).isEqualTo(childId);long p1=receipt.plan().selections().get(r1).monthlyPriceId(),p2=receipt.plan().selections().get(r2).monthlyPriceId();assertThat(module.readByIds("COMMERCIAL",Set.of(p1,p2))).containsOnlyKeys(p1,p2,childId);assertThat(receipt.plan().selections().get(r2).fact().price()).isEqualByComparingTo("5");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class)).isEqualTo(before+3);
  }
  @Test void savedNormalChildDependencyClosureIsReusedWithoutCurrentScrapReads(){
    String child=code("SCRAP-"),grand=code("GRAND-"),parent=code("ROOT-");var c=request(child,"210","KG",NonCuConditions.empty());var g=request(grand,"210","KG",NonCuConditions.empty());var seeded=new MonthlyAdoptionPlan(Map.of(c,new Selection(null,shared(),PriceFact.normal(new BigDecimal("10"),SourceEvidence.ofJson("{\"sourceKind\":\"TEST_SAVED_NORMAL_GRAPH\"}"),Set.of(g))),g,new Selection(null,shared(),PriceFact.normal(new BigDecimal("3"),SourceEvidence.ofJson("{\"sourceKind\":\"TEST_SAVED_LEAF\"}"),Set.of()))),List.of(new Use("seed/child",c,BigDecimal.ONE)),Set.of());var old=module.adopt(context(),seeded);program(parent,child);var p=request(parent,"210","件",NonCuConditions.empty());int calls=sourceCalls.get();var candidates=module.resolveCandidates(context(),Set.of(p));assertThat(candidates.problems()).isEmpty();var receipt=adopt(candidates,Set.of(p));long parentId=receipt.plan().selections().get(p).monthlyPriceId();assertThat(module.readByIds("COMMERCIAL",Set.of(parentId))).containsOnlyKeys(parentId,old.plan().selections().get(c).monthlyPriceId(),old.plan().selections().get(g).monthlyPriceId());assertThat(sourceCalls.get()).isEqualTo(calls);
  }
  @Test void missingChildAndSupplierWaitingDoNotWriteAnyMonthlyCandidate(){
    for(boolean supplierWaiting:List.of(false,true)){String child=code("SCRAP-"),parent=code("ROOT-");if(supplierWaiting){scrap(child,"S1","10");scrap(child,"S2","20");}program(parent,child);var p=request(parent,"210","件",NonCuConditions.empty());long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class);var candidates=module.resolveCandidates(context(),Set.of(p));assertThat(candidates.values()).doesNotContainKey(p);assertThat(candidates.problems().get(p).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class)).isEqualTo(before);assertThat(module.lookup(Set.of(p)).missing()).contains(p);}
  }
  @Test void adoptedParentRetainsOriginalChildAndMappingAfterSourceRemoval(){
    String child=code("SCRAP-"),parent=code("ROOT-");scrap(child,"S1","10");var mapping=program(parent,child);var p=request(parent,"220","件",new NonCuConditions(new BoardTerms(new BigDecimal("3000"),null,true)));var first=adopt(module.resolveCandidates(context(),Set.of(p)),Set.of(p));long id=first.plan().selections().get(p).monthlyPriceId();jdbc.update("DELETE FROM lp_scrap_price WHERE material_code=?",child);mapping.setRatio(new BigDecimal("0.9"));mapping.setScrapCode("NEW-SCRAP");int before=parentCalls.get()+sourceCalls.get();var other=request(parent,"220","件",NonCuConditions.empty());var result=module.resolveCandidates(context(),Set.of(other));assertThat(result.problems()).isEmpty();assertThat(result.values().get(other).selection().monthlyPriceId()).isEqualTo(id);assertThat(result.values().get(other).selection().fact().price()).isEqualByComparingTo("5");assertThat(parentCalls.get()+sourceCalls.get()).isEqualTo(before);assertThat(module.readByIds("COMMERCIAL",Set.of(id))).hasSize(2);
  }
}
