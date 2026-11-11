package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.costing.QuoteCostingPreparationService;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real SQL and ordinary calculation; background volume and all business rows are disposable. */
@Tag("integration")
@org.springframework.context.annotation.Import(CostingPerformanceIntegrationTest.TimingConfiguration.class)
@SpringBootTest(properties={"integration.oa-person-directory.enabled=false",
    "integration.oa-person-directory.run-at-startup=false","cms.sync-publish.enabled=false",
    "srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class CostingPerformanceIntegrationTest extends OriginalPricingIntegrationTestBase {
  @org.springframework.boot.test.context.TestConfiguration
  static class TimingConfiguration {
    @org.springframework.context.annotation.Bean
    static org.springframework.beans.factory.config.BeanPostProcessor timingDriver() {
      return new org.springframework.beans.factory.config.BeanPostProcessor() {
        @Override public Object postProcessBeforeInitialization(Object bean,String name) {
          if(bean instanceof com.zaxxer.hikari.HikariDataSource dataSource)
            dataSource.setDriverClassName(CostingTimingSqlDriver.class.getName());
          return bean;
        }
      };
    }
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper rawHierarchy;
  @Autowired QuoteCostingPreparationService preparation;
  @Autowired ProductCostingPipeline pipeline;
  @Autowired com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService preparedPrices;
  @Autowired com.sanhua.marketingcost.mapper.MaterialMasterRawMapper rawMaterials;
  @Autowired com.sanhua.marketingcost.service.MaterialMasterSyncService materialSync;
  private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  private final List<Map<String,Object>> observations=new ArrayList<>();
  private final String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
  private String actor;

  @org.springframework.test.context.DynamicPropertySource
  static void properties(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void scopedLookupsPreserveOrganizationActiveFlagCategoryAndLatestBatch() {
    String prefix="LOOKUP-"+UUID.randomUUID().toString().substring(0,8);
    for(var row:List.of(
        List.of("PACK","COMMERCIAL","包装组件",1,"2026-10-01","LOOKUP"),
        List.of("NORMAL","COMMERCIAL","普通物料",1,"2026-10-02","LOOKUP"),
        List.of("INACTIVE","COMMERCIAL","包装组件",0,"2099-01-01","LOOKUP"),
        List.of("PLATEONLY","PLATE","包装组件",1,"2098-01-01","LOOKUP"),
        List.of("OTHER","COMMERCIAL","包装组件",1,"2097-01-01","OTHER"))) {
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,organization_code,main_category_name,active_flag,import_batch_id,source_type) VALUES(?,?,?,?,?,?)",
          prefix+row.get(0),row.get(1),row.get(2),row.get(3),row.get(4),row.get(5));
    }
    var codes=List.of(prefix+"PACK",prefix+"NORMAL",prefix+"INACTIVE",prefix+"PLATEONLY");
    assertThat(rawMaterials.selectPackageComponentParentsByCodes("包装组件",codes,"COMMERCIAL"))
        .extracting(com.sanhua.marketingcost.entity.MaterialMasterRaw::getMaterialCode).containsExactly(prefix+"PACK");
    assertThat(rawMaterials.selectPackageComponentParentsByCodes("包装组件",codes,"PLATE"))
        .extracting(com.sanhua.marketingcost.entity.MaterialMasterRaw::getMaterialCode).containsExactly(prefix+"PLATEONLY");
    assertThat(rawMaterials.selectPackageComponentParentsByCodes("包装组件",List.of(),"COMMERCIAL")).isEmpty();
    assertThat(rawMaterials.selectLatestActiveBatchId("LOOKUP","COMMERCIAL")).isEqualTo("2026-10-02");
    assertThat(rawMaterials.selectLatestActiveBatchId("ABSENT","COMMERCIAL")).isNull();
    assertThat(rawMaterials.selectLatestActiveBatchId(null,"COMMERCIAL")).isEqualTo(jdbc.queryForObject(
        "SELECT MAX(import_batch_id) FROM lp_material_master_raw WHERE active_flag=1 AND organization_code='COMMERCIAL'",String.class));
  }

  @Test void singleAndWholeQuoteBaseline() throws Exception {
    actor="perf_"+UUID.randomUUID().toString().substring(0,8);
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'性能验收','unused','COMMERCIAL','0','0','PERF',NOW(),'PERF',NOW())",actor);
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,actor);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var authentication=new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("*:*:*")));
    authentication.setDetails(Map.of("businessUnitType","COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      seedBackgroundMaterials();
      var single=seedQuote("PERF-SINGLE",1);
      var batch=seedQuote("PERF-WHOLE",3);
      var confirmed=seedQuote("PERF-CONFIRMED",1);
      measure("single-first",() -> requireSuccess(preparation.prepareAndCost(single.oa(),new QuoteCostingPreparationService.Request(single.items().getFirst(),month,false),actor).product()));
      jdbc.update("UPDATE lp_quality_loss_rate SET loss_rate=0.02 WHERE bare_product_code=?",single.products().getFirst()+"-BARE");
      measure("single-recalculate",() -> requireSuccess(preparation.prepareAndCost(single.oa(),new QuoteCostingPreparationService.Request(single.items().getFirst(),month,false),actor).product()));
      pipeline.prepare(new ProductCostingRequest(confirmed.oa(),confirmed.items().getFirst(),month,actor,false));
      generatePrices(confirmed,confirmed.items().getFirst());
      measure("single-confirmed-prices",() -> requireSuccess(preparation.prepareAndCost(confirmed.oa(),new QuoteCostingPreparationService.Request(confirmed.items().getFirst(),month,false),actor).product()));
      measure("whole-prepare",() -> {
        var result=preparation.prepareAndCost(batch.oa(),new QuoteCostingPreparationService.Request(null,month,false),actor);
        assertThat(result.checks()).allSatisfy(check -> assertThat(check.getPipelineStatus()).isEqualTo("READY"));
        assertThat(result.batch()).isNotNull();return result;
      });
      for(long item:batch.items()) {
        generatePrices(batch,item);
        measure("whole-product-"+item,() -> requireSuccess(pipeline.execute(new ProductCostingRequest(batch.oa(),item,month,actor,false))));
      }
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id IN ("+String.join(",",batch.items().stream().map(Object::toString).toList())+") AND status='SUCCESS'",Integer.class)).isEqualTo(3);
      Path output=Path.of(System.getProperty("cost.performance.output","target/costing-performance.json"));
      Files.createDirectories(output.toAbsolutePath().getParent());
      var financial=Map.of(
          "versions",jdbc.queryForList("SELECT oa_form_item_id,version_no,status,pricing_month,total_cost,cu_material_adjustment,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id IN (SELECT id FROM oa_form_item WHERE material_no LIKE 'PERF-%') ORDER BY id"),
          "parts",jdbc.queryForList("SELECT cost_run_version_id,product_code,part_code,part_name,qty,material,shape_attr,price_source,unit_price,amount,technical_module_type,price_org_code,material_organization_code FROM lp_cost_run_part_item WHERE product_code LIKE 'PERF-%' ORDER BY id"),
          "fees",jdbc.queryForList("SELECT cost_run_version_id,product_code,line_no,cost_code,cost_name,base_amount,rate,amount,category,business_unit_type FROM lp_cost_run_cost_item WHERE product_code LIKE 'PERF-%' ORDER BY id"));
      Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("measurements",observations,"financial",financial,"rawRows",jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_master_raw",Long.class))));
      String otherMaterial=batch.products().get(1)+"-PART";
      var otherMaster=jdbc.queryForMap("SELECT * FROM lp_material_master WHERE material_code=?",otherMaterial);
      jdbc.update("UPDATE lp_material_master_raw SET material_name='Unselected product source changed' WHERE material_code=?",otherMaterial);
      var scopedSync=materialSync.syncProduct(batch.oa(),batch.items().getFirst(),month);
      assertThat(scopedSync.distinctCodes()).isEqualTo(Integer.getInteger("cost.performance.components",1));
      assertThat(jdbc.queryForMap("SELECT * FROM lp_material_master WHERE material_code=?",otherMaterial)).isEqualTo(otherMaster);
      assertThat(observations).allSatisfy(observation -> {
        @SuppressWarnings("unchecked") var statements=(List<Map<String,Object>>)observation.get("statements");
        assertThat(statements).as("Costing must not fetch every packaging candidate in the organization")
            .noneMatch(row -> row.get("sql").toString().contains("main_category_name = ?")
                && !row.get("sql").toString().contains("material_code IN"));
      });
    } finally { SecurityContextHolder.clearContext();CostingTimingSqlDriver.finish(); }
  }
  private Object requireSuccess(com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult result) {
    assertThat(result).isNotNull();assertThat(result.getPipelineStatus()).withFailMessage("%s: %s",result.getErrorCode(),result.getMessage()).isEqualTo("SUCCESS");return result;
  }
  private void generatePrices(Quote quote,long item) {
    var request=new com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest();
    request.setPeriodMonth(month);
    assertThat(preparedPrices.generate(quote.oa(),item,request).getReadiness().getStatus()).isEqualTo("READY");
  }
  private void measure(String label,java.util.function.Supplier<Object> action) {
    CostingTimingSqlDriver.start();long started=System.nanoTime();Object result=action.get();
    double ms=(System.nanoTime()-started)/1_000_000.0;var statements=CostingTimingSqlDriver.finish();
    assertThat(statements).as("SQL profiling driver must actually observe the application").isNotEmpty();
    observations.add(Map.of("label",label,"ms",ms,"result",result,"statements",statements));
    System.out.println("COSTING-PERFORMANCE "+label+" ms="+ms+" sqlCalls="+statements.stream().mapToLong(r->((Number)r.get("calls")).longValue()).sum());
  }
  private record Quote(String oa,List<Long> items,List<String> products) {}
  private Quote seedQuote(String prefix,int count) {
    String oa=prefix;
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",oa);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oa);
    var sources=new OrdinaryCostingTestSources(jdbc,rawHierarchy);var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    var items=new ArrayList<Long>();var products=new ArrayList<String>();
    for(int i=0;i<count;i++) {
      String product=oa+"-"+i, material=product+"-PART";
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,product,product);
      items.add(jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=? AND material_no=?",Long.class,form,product));products.add(product);
      sources.ordinarySources(product,material,form,date);sources.comparisonFixedPrice(material,"2",date);
      addComponents(sources,product,material,date);
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",product);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
    }
    return new Quote(oa,items,products);
  }
  private void addComponents(OrdinaryCostingTestSources sources,String product,String first,LocalDateTime date) {
    int count=Integer.getInteger("cost.performance.components",1);
    var original=rawHierarchy.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.sanhua.marketingcost.entity.BomRawHierarchy>()
        .eq("top_product_code",product).eq("material_code",first));
    for(int i=1;i<count;i++) {
      String material=product+"-P"+i;
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'15','采购件','件')",material,material,product);
      var child=new com.sanhua.marketingcost.entity.BomRawHierarchy();
      org.springframework.beans.BeanUtils.copyProperties(original,child);
      child.setId(null);child.setSourceLineKey(null);child.setMaterialCode(material);child.setMaterialName(material);
      child.setPath("/"+product+"/"+material+"/");child.setSortSeq(10+i);
      rawHierarchy.insert(child);sources.comparisonFixedPrice(material,"2",date);
    }
  }
  private void seedBackgroundMaterials() {
    // This existing lookup index is already on 240; omit no production index from the baseline.
    if(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lp_material_master_raw' AND index_name='idx_raw_material_active'",Integer.class)==0)
      jdbc.execute("CREATE INDEX idx_raw_material_active ON lp_material_master_raw(material_code,active_flag)");
    // Mirrors the broad-query cardinality without copying any formal source or business data.
    jdbc.execute("CREATE TABLE perf_digits(n INT PRIMARY KEY)");
    jdbc.update("INSERT INTO perf_digits VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9)");
    jdbc.update("""
      INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_name,material_model)
      SELECT CONCAT('PERF-BG-',n),REPEAT('x',100),'COMMERCIAL','2026-11-01',1,
        IF(n<1728,'包装组件','普通物料'),CONCAT('MODEL-',n)
      FROM (SELECT a.n+b.n*10+c.n*100+d.n*1000+e.n*10000+f.n*100000 AS n
        FROM perf_digits a CROSS JOIN perf_digits b CROSS JOIN perf_digits c CROSS JOIN perf_digits d
        CROSS JOIN perf_digits e CROSS JOIN perf_digits f WHERE f.n<2) numbers
      """);
    jdbc.queryForList("ANALYZE TABLE lp_material_master_raw");
  }
}
