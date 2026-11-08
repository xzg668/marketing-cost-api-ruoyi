package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.context.TestPropertySource;

/** Explicit benchmark only: synthetic prior history; every target cost is produced by packaged workers. */
@Tag("integration")
@EnabledIfSystemProperty(named="benchmark.enabled", matches="true")
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class MonthlyRepriceBenchmarkIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired MonthlyCostVersionService savedCosts;
  @Autowired com.sanhua.marketingcost.mapper.PriceLinkedItemMapper linkedRules;
  @Autowired com.sanhua.marketingcost.service.impl.OriginalLinkedRuleSnapshot linkedSnapshots;
  @Autowired com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService effectivePrices;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  private final Path out=Path.of(Objects.requireNonNull(System.getProperty("reprice.test.evidence")));
  private final int products=Integer.getInteger("benchmark.products",10);
  private final int customers=Integer.getInteger("benchmark.customers",1);
  private final int oas=Integer.getInteger("benchmark.oas",1);
  private final int workers=Integer.getInteger("benchmark.workers",1);
  private final List<Child> children=new ArrayList<>();
  private final Map<String,Object> proof=new LinkedHashMap<>();
  private static final String TEMPLATE="BENCH-TEMPLATE";
  private static final long FIRST_ID=1_000_000;
  private record Source(long id,String product,String customer,String oa,int sequence) {}
  private record Child(Process process,Path log,String owner) {}

  @Test void everyTargetIsActuallyCalculatedAndEverySourceAndAmountReconciles() throws Exception {
    assertThat(products).isBetween(1,10000);assertThat(customers).isBetween(1,10);
    assertThat(oas).isBetween(1,4);assertThat(workers).isBetween(1,4);
    int m=products*customers*oas,u=products*customers;
    assertThat(m).isLessThanOrEqualTo(10000);
    assertThat(MYSQL.getContainerInfo().getConfig().getLabels())
        .containsEntry("sales-cost.task","monthly-reprice-reuse-20261105-r02");
    unit="COMMERCIAL";priceOrg="210";materialOrg="COMMERCIAL";
    var month=YearMonth.parse(com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth());
    var old=month.minusMonths(1);var date=old.atDay(15).atTime(12,0);
    repriceProperties.setGoLiveMonths(Map.of(unit,old.toString()));
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("benchmark-acceptance",null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    auth.setDetails(Map.of("businessUnitType",unit,"userId",1L));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    proof.put("startedUtc",Instant.now().toString());proof.put("M",m);proof.put("U",u);proof.put("G",products);
    proof.put("workers",workers);proof.put("bomLines",100);
    proof.put("historyFixture","Legacy history expanded from an independent ordinary production calculation; target results are never seeded");
    try {
      write("database-ready.json",Map.of("container",MYSQL.getContainerId(),
          "uuid",jdbc.queryForObject("SELECT @@server_uuid",String.class)));
      await("runner applied isolated resource limits",Duration.ofMinutes(2),()->Files.exists(out.resolve("database-limits-ready.json")));
      jdbc.execute("SET GLOBAL innodb_buffer_pool_size=536870912");
      jdbc.execute("SET GLOBAL innodb_redo_log_capacity=268435456");
      long setupStarted=System.nanoTime();
      var oracle=ordinaryOracle(old,date);
      var sources=seedHistory(oracle,old,date);
      assertThat(sources).hasSize(m);
      write("oracle.json",Map.of("version",oracle.version(),"calculation",oracle.snapshot().calculation(),
          "parts",oracle.parts(),"costs",oracle.costs()));
      var expected=new LinkedHashMap<String,Source>();
      try(var writer=Files.newBufferedWriter(out.resolve("expected-sources.ndjson"))) {
        for(var source:sources) {
          expected.put(identity(source.product(),source.customer()),source);
          writer.write(json.writeValueAsString(source));writer.newLine();
        }
      }
      assertThat(expected).hasSize(u);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE reprice_no IS NOT NULL",Integer.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_bom_raw_hierarchy WHERE build_batch_id LIKE 'BENCH-P-%' AND level=1 AND qty_per_top<>0",Integer.class)).isEqualTo(products*100);
      var protection=historyDigests();write("history-before.json",protection);
      proof.put("setupMillis",elapsed(setupStarted));
      installMeasurements();
      long startup=System.nanoTime();
      for(int i=0;i<workers;i++)startWorker(i,old.toString());
      proof.put("workerStartupMillis",elapsed(startup));
      write("database-before.json",databaseMetrics());
      long started=System.nanoTime();
      var batch=contracts.enqueue(unit,month);
      proof.put("admissionMillis",elapsed(started));proof.put("repriceNo",batch.getRepriceNo());
      assertThat(batch.getSourceCount()).isEqualTo(m);assertThat(batch.getTotalCount()).isEqualTo(u);
      write("business-start.json",Map.of("repriceNo",batch.getRepriceNo(),"startedUtc",Instant.now().toString(),"M",m,"U",u));
      var beforeNo=batch.getRepriceNo();
      long deadline=System.nanoTime()+Duration.ofHours(4).toNanos();
      while(true) {
        var current=repriceMapper.readBatch(beforeNo);
        write("progress.json",Map.of("elapsedMillis",elapsed(started),"status",current.getStatus(),
            "success",current.getSuccessCount(),"failed",current.getFailedCount(),"total",u,
            "checkStatus",Objects.toString(current.getCheckStatus(),"")));
        if(current.getFailedCount()>0) {
          write("failed-tasks.json",jdbc.queryForList("SELECT id,oa_no,product_code,status,error_message,result_summary_json FROM lp_cost_run_task WHERE source_no=? AND status='FAILED'",beforeNo));
          write("innodb-on-failure.json",jdbc.queryForList("SHOW ENGINE INNODB STATUS"));
          fail("Healthy benchmark has failed objects; inspect failed-tasks.json");
        }
        if(current.getSuccessCount()==u && "PASSED".equals(current.getCheckStatus()))break;
        assertThat(Files.exists(out.resolve("capacity-stop.json"))).as("disk capacity guard").isFalse();
        assertThat(System.nanoTime()).as("four-hour guard, not a completion estimate").isLessThan(deadline);
        assertThat(children).allSatisfy(c->assertThat(c.process().isAlive()).as(c.log().toString()).isTrue());
        Thread.sleep(1000);
      }
      proof.put("businessThroughCheckMillis",elapsed(started));
      stopWorkers();write("database-after.json",databaseMetrics());
      // One read after business timing and SQL counters; retained before the owned DB stops.
      write("innodb-after.json",jdbc.queryForList("SHOW ENGINE INNODB STATUS"));
      long arithmeticCalls=0,savedCalls=0;var retriedCalls=new LinkedHashMap<String,Long>();
      for(var child:children) {
        var lines=Files.readAllLines(child.log().getParent().resolve("method-metrics.csv"));
        for(String line:lines.subList(1,lines.size())) {
          var cells=line.split(",");
          if(cells[0].endsWith("MonthlyProductCost.calculate"))arithmeticCalls+=Long.parseLong(cells[1]);
          if(cells[0].endsWith("MonthlyCostVersionService.saveCalculated"))savedCalls+=Long.parseLong(cells[1])-Long.parseLong(cells[2]);
          if(Long.parseLong(cells[2])>0)retriedCalls.merge(cells[0],Long.parseLong(cells[2]),Long::sum);
        }
      }
      assertThat(arithmeticCalls).isEqualTo(products);assertThat(savedCalls).isEqualTo(u);
      proof.put("measuredArithmeticCalls",arithmeticCalls);proof.put("measuredCustomerSaves",savedCalls);
      proof.put("rolledBackMethodAttempts",retriedCalls);
      long reconcile=System.nanoTime();
      var results=repriceMapper.results(beforeNo);
      assertThat(results).hasSize(u);
      var versions=new HashSet<Long>();var seen=new HashSet<String>();
      try(var writer=Files.newBufferedWriter(out.resolve("reconciled-results.ndjson"))) {
        for(int start=0;start<results.size();start+=64) {
          var page=results.subList(start,Math.min(start+64,results.size()));
          var saved=savedCosts.readBatch(unit,page.stream().map(MonthlyRepriceResult::getCostRunVersionId).toList());
          for(var result:page) {
            String key=identity(result.getProductCode(),result.getCustomerIdentityValue());
            assertThat(seen.add(key)).isTrue();var source=Objects.requireNonNull(expected.get(key));
            assertThat(result.getCalcStatus()).isEqualTo("SUCCESS");
            assertThat(result.getSourceCostVersionId()).isEqualTo(source.id());
            assertThat(result.getOaNo()).isEqualTo(source.oa());assertThat(result.getOaFormItemId()).isEqualTo(source.id());
            assertThat(result.getMergedSourceCount()).isEqualTo(oas);
            assertThat(versions.add(result.getCostRunVersionId())).isTrue();
            var actual=saved.get(result.getCostRunVersionId());
            reconcile(oracle,actual,source,month.toString());
            writer.write(json.writeValueAsString(Map.of("product",source.product(),"customer",source.customer(),
                "selectedOa",source.oa(),"selectedSourceVersion",source.id(),"resultVersion",actual.version().getId(),
                "parts",actual.parts().size(),"costItems",actual.costs().size(),"allAmountsMatch",true)));writer.newLine();
          }
        }
      }
      assertThat(seen).containsExactlyInAnyOrderElementsOf(expected.keySet());
      var links=jdbc.queryForList("SELECT s.source_cost_version_id,s.oa_no,s.oa_form_item_id,r.product_code,r.customer_identity_value "
          +"FROM lp_monthly_reprice_source s JOIN lp_monthly_reprice_result r ON r.id=s.object_id AND r.reuse_batch_id=s.batch_id WHERE s.batch_id=?",batch.getId());
      assertThat(links).hasSize(m);var sourceIndex=new HashMap<Long,Source>();sources.forEach(s->sourceIndex.put(s.id(),s));
      for(var row:links) {
        var source=sourceIndex.remove(((Number)row.get("source_cost_version_id")).longValue());assertThat(source).isNotNull();
        assertThat(row).containsEntry("oa_no",source.oa()).containsEntry("oa_form_item_id",source.id())
            .containsEntry("product_code",source.product()).containsEntry("customer_identity_value",source.customer());
      }
      assertThat(sourceIndex).isEmpty();
      var groups=jdbc.queryForList("SELECT id,status,attempt_count FROM lp_monthly_reprice_compute_group WHERE batch_id=?",batch.getId());
      assertThat(groups).hasSize(products).allSatisfy(g->assertThat(g).containsEntry("status","SUCCESS").containsEntry("attempt_count",1));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_result WHERE reuse_batch_id=? AND compute_group_id IS NOT NULL",Integer.class,batch.getId())).isEqualTo(u);
      assertThat(historyDigests()).isEqualTo(protection);
      proof.put("reconciliationMillis",elapsed(reconcile));proof.put("A",products);
      proof.put("missing",0);proof.put("wrongOwner",0);proof.put("amountMismatch",0);
      proof.put("fullCheck",repriceMapper.readBatch(beforeNo).getCheckResultJson());
      proof.put("allSourcesPreserved",true);proof.put("status","PASSED_CALCULATION");
      write("events.json",jdbc.queryForList("SELECT * FROM benchmark_events ORDER BY id"));
      write("calculation-proof.json",proof);
    } finally {
      stopWorkers();proof.put("finishedUtc",Instant.now().toString());
      proof.put("processes",children.stream().map(c->Map.of("pid",c.process().pid(),"owner",c.owner(),"log",c.log().toString(),"exit",c.process().exitValue())).toList());
      write("result.json",proof);org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  private MonthlyCostVersionService.Stored ordinaryOracle(YearMonth old,LocalDateTime date) {
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES(?,?,'标准品',0)",unit,date.getYear());
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer) VALUES('BENCH-REFERENCE',?,80000,'REFERENCE')",unit);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no='BENCH-REFERENCE'",Long.class);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,?)",form,TEMPLATE,TEMPLATE,unit);
    long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    String part="BENCH-PART-000";ordinarySources(TEMPLATE,part,form,date);
    MonthlyQuotationSourceFixture.copperLinked(jdbc,linkedRules,linkedSnapshots,effectivePrices,transactions,part,old.toString(),"件");
    for(int i=1;i<100;i++) {
      String material=String.format("BENCH-PART-%03d",i);
      comparisonAdditionalPart(TEMPLATE,part,material,BigDecimal.ONE.add(BigDecimal.valueOf(i%3,1)).toPlainString(),date);
      comparisonFixedPrice(material,BigDecimal.valueOf(200+i,2).toPlainString(),date);
    }
    buildOriginalFixture(form,item,TEMPLATE,old.toString(),date,part,Map.of());
    var cost=pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest("BENCH-REFERENCE",item,null,"benchmark-oracle",true));
    assertThat(cost.getPipelineStatus()).as(cost.getMessage()).isEqualTo("SUCCESS");
    // The reference is not one of the synthetic source quotations in the go-live window.
    jdbc.update("UPDATE lp_quote_cost_run_version SET trial_finished_at=? WHERE id=?",date.minusMonths(1),cost.getCostVersionId());
    var stored=savedCosts.read(unit,cost.getCostVersionId());
    assertThat(stored.snapshot().calculation().cuMaterialAdjustment()).isNotZero();
    return stored;
  }

  private List<Source> seedHistory(MonthlyCostVersionService.Stored oracle,YearMonth old,LocalDateTime date) {
    jdbc.execute("CREATE TABLE benchmark_products(id INT PRIMARY KEY,product VARCHAR(64) NOT NULL UNIQUE)");
    jdbc.execute("CREATE TABLE benchmark_sources(id BIGINT PRIMARY KEY,product VARCHAR(64),customer VARCHAR(64),oa VARCHAR(64),sequence_no INT,completed DATETIME(6))");
    var sourceRows=new ArrayList<Object[]>();var productRows=new ArrayList<Object[]>();var sources=new ArrayList<Source>();
    for(int p=0;p<products;p++) {
      String product=String.format("BENCH-P-%05d",p);productRows.add(new Object[]{p,product});
      for(int c=0;c<customers;c++)for(int o=0;o<oas;o++) {
        long id=FIRST_ID+sources.size();String customer=String.format("CUSTOMER-%03d",c),oa=String.format("BENCH-OA-%05d",sources.size());
        sources.add(new Source(id,product,customer,oa,o));sourceRows.add(new Object[]{id,product,customer,oa,o,date.plusSeconds(o)});
      }
    }
    jdbc.batchUpdate("INSERT INTO benchmark_products VALUES(?,?)",productRows);
    jdbc.batchUpdate("INSERT INTO benchmark_sources VALUES(?,?,?,?,?,?)",sourceRows);
    String bp=" t JOIN benchmark_products b ON b.id BETWEEN %d AND %d ";
    for(int start=0;start<products;start+=100) {
      String join=bp.formatted(start,Math.min(products-1,start+99));
      cloneRows("lp_material_master_raw",Map.of("material_code","b.product","material_name","b.product","import_batch_id","b.product"),join+"WHERE t.material_code='"+TEMPLATE+"'");
      cloneRows("lp_bom_raw_hierarchy",Map.of("top_product_code","b.product","parent_code","IF(t.parent_code='"+TEMPLATE+"',b.product,t.parent_code)",
          "material_code","IF(t.material_code='"+TEMPLATE+"',b.product,t.material_code)","material_name","IF(t.material_code='"+TEMPLATE+"',b.product,t.material_name)",
          "path","REPLACE(t.path,'/"+TEMPLATE+"/',CONCAT('/',b.product,'/'))","build_batch_id","b.product","source_import_batch_id","b.product",
          "source_line_key","CONCAT(b.product,'-',t.id)"),join+"WHERE t.build_batch_id='"+TEMPLATE+"'");
      cloneRows("cms_cost_source_effective",Map.of("parent_code","b.product"),join+"WHERE t.parent_code='"+TEMPLATE+"'");
      cloneRows("lp_quality_loss_rate",Map.of("bare_product_code","b.product"),join+"WHERE t.bare_product_code='"+TEMPLATE+"'");
      cloneRows("lp_manufacture_rate",Map.of("product_code","b.product","match_key","b.product","company","b.product"),join+"WHERE t.product_code='"+TEMPLATE+"'");
    }
    long templateForm=jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?",Long.class,oracle.version().getOaFormItemId());
    for(int start=0;start<sources.size();start+=100) {
      String join=" t JOIN benchmark_sources b ON b.id BETWEEN "+(FIRST_ID+start)+" AND "+(FIRST_ID+Math.min(sources.size()-1,start+99))+" ";
      cloneRows("oa_form",Map.of("id","b.id","oa_no","b.oa","customer","b.customer"),join+"WHERE t.id="+templateForm);
      cloneRows("oa_form_item",Map.of("id","b.id","oa_form_id","b.id","material_no","b.product","product_name","b.product"),join+"WHERE t.id="+oracle.version().getOaFormItemId());
      var header=new HashMap<String,String>();header.put("id","b.id");header.put("oa_no","b.oa");header.put("oa_form_item_id","b.id");
      header.put("product_code","b.product");header.put("cost_run_no","CONCAT('BENCH-HISTORY-',b.id)");header.put("version_no","CONCAT('BENCH-HISTORY-',b.id)");
      header.put("trial_started_at","b.completed");header.put("trial_finished_at","b.completed");header.put("algorithm_version","'BENCH_LEGACY_HISTORY'");
      for(String field:List.of("jianji_snapshot_json","jianji_snapshot_version","fee_policy_version","price_prepare_no","oa_price_prepare_no","finance_price_prepare_no","input_fingerprint","source_revision"))header.put(field,"NULL");
      cloneRows("lp_quote_cost_run_version",header,join+"WHERE t.id="+oracle.version().getId());
      var owned=new HashMap<String,String>();owned.put("oa_no","b.oa");owned.put("oa_form_item_id","b.id");owned.put("cost_run_version_id","b.id");
      owned.put("cost_run_no","CONCAT('BENCH-HISTORY-',b.id)");owned.put("product_code","b.product");
      var part=new HashMap<>(owned);part.put("bom_row_id","NULL");part.put("price_prepare_item_id","NULL");
      // Legacy source fixtures have no first-adoption receipts. Never attach another quotation's receipt.
      part.put("monthly_price_id","NULL");part.put("monthly_pricing_type","NULL");
      cloneRows("lp_cost_run_part_item",part,join+"WHERE t.cost_run_version_id="+oracle.version().getId());
      cloneRows("lp_cost_run_cost_item",owned,join+"WHERE t.cost_run_version_id="+oracle.version().getId());
    }
    return sources;
  }

  /** All tables and expressions are hard-coded test fixtures, never user-supplied SQL. */
  private void cloneRows(String table,Map<String,String> changes,String join) {
    var columns=jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND extra NOT LIKE '%GENERATED%' ORDER BY ordinal_position",String.class,table);
    assertThat(columns).containsAll(changes.keySet());
    columns=columns.stream().filter(c->!c.equals("id") || changes.containsKey("id")).toList();
    jdbc.update("INSERT INTO "+table+" ("+String.join(",",columns.stream().map(c->"`"+c+"`").toList())+") SELECT "
        +String.join(",",columns.stream().map(c->changes.getOrDefault(c,"t.`"+c+"`")).toList())+" FROM "+table+join);
  }

  private void reconcile(MonthlyCostVersionService.Stored oracle,MonthlyCostVersionService.Stored actual,Source source,String month) {
    var v=actual.version();assertThat(v.getOaNo()).isEqualTo(source.oa());assertThat(v.getOaFormItemId()).isEqualTo(source.id());
    assertThat(v.getProductCode()).isEqualTo(source.product());assertThat(v.getPricingMonth()).isEqualTo(month);
    var a=actual.snapshot().calculation();var e=oracle.snapshot().calculation();
    assertThat(a.ordinary()).usingRecursiveComparison().isEqualTo(e.ordinary());
    assertThat(a.totalCost()).isEqualByComparingTo(e.totalCost());
    assertThat(a.financeMaterialCost()).isEqualByComparingTo(e.financeMaterialCost());
    assertThat(a.cuMaterialAdjustment()).isEqualByComparingTo(e.cuMaterialAdjustment());
    assertThat(a.finalQuoteAmount()).isEqualByComparingTo(e.finalQuoteAmount());
    // First adoption IDs/order differ between months; compare every material's full applicable conditions.
    assertThat(scenarioAmounts(actual)).usingRecursiveComparison().isEqualTo(scenarioAmounts(oracle));
    assertThat(actual.parts()).hasSize(oracle.parts().size());
    var expectedParts=new HashMap<String,List<BigDecimal>>();
    oracle.parts().forEach(p->expectedParts.put(p.getPartCode(),Arrays.asList(p.getQty(),p.getUnitPrice(),p.getAmount())));
    for(var p:actual.parts()) {
      assertThat(p.getOaNo()).isEqualTo(source.oa());assertThat(p.getOaFormItemId()).isEqualTo(source.id());
      assertThat(p.getProductCode()).isEqualTo(source.product());
      assertThat(Arrays.asList(p.getQty(),p.getUnitPrice(),p.getAmount())).usingRecursiveComparison().isEqualTo(expectedParts.remove(p.getPartCode()));
    }
    assertThat(expectedParts).isEmpty();
    var expectedCosts=new HashMap<String,List<BigDecimal>>();
    oracle.costs().forEach(c->expectedCosts.put(c.getCostCode(),Arrays.asList(c.getBaseAmount(),c.getRate(),c.getAmount())));
    assertThat(actual.costs()).hasSize(oracle.costs().size());
    for(var c:actual.costs()) {
      assertThat(c.getOaNo()).isEqualTo(source.oa());assertThat(c.getOaFormItemId()).isEqualTo(source.id());
      assertThat(c.getProductCode()).isEqualTo(source.product());
      assertThat(Arrays.asList(c.getBaseAmount(),c.getRate(),c.getAmount())).usingRecursiveComparison().isEqualTo(expectedCosts.remove(c.getCostCode()));
    }
    assertThat(expectedCosts).isEmpty();
  }

  private Map<List<Object>,List<BigDecimal>> scenarioAmounts(MonthlyCostVersionService.Stored stored) {
    var pairs=new HashMap<Long,com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceScenarios.Pair>();
    stored.snapshot().priceScenarios().forEach(p->assertThat(pairs.put(p.monthlyPriceId(),p)).isNull());
    var values=new HashMap<List<Object>,List<BigDecimal>>();
    for(var adopted:stored.snapshot().calculation().adoptedPrices()) {
      var request=adopted.request();var pair=Objects.requireNonNull(pairs.get(adopted.selection().monthlyPriceId()));
      var key=Arrays.<Object>asList(request.family().businessUnitType(),request.family().materialCode(),
          request.family().specification(),request.family().unit(),request.family().currency(),request.family().taxBasis(),
          request.family().requestedSupplierCode(),request.priceOrgCode(),request.conditions(),request.priceBasisQuantity(),request.annualUsagePieces());
      assertThat(values.put(key,Arrays.asList(pair.financialPrice(),pair.customerPrice()))).isNull();
    }
    assertThat(values).hasSize(100);return values;
  }

  private Map<String,String> historyDigests() throws Exception {
    var values=new LinkedHashMap<String,String>();
    for(String table:List.of("lp_quote_cost_run_version","lp_cost_run_part_item","lp_cost_run_cost_item")) {
      var columns=jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",String.class,table);
      String where=table.equals("lp_quote_cost_run_version")?"id >= "+FIRST_ID+" AND reprice_no IS NULL":"cost_run_version_id BETWEEN "+FIRST_ID+" AND "+(FIRST_ID+products*customers*oas-1);
      var digest=MessageDigest.getInstance("SHA-256");
      jdbc.query("SELECT SHA2(CAST(JSON_ARRAY("+String.join(",",columns.stream().map(c->"`"+c+"`").toList())+") AS CHAR),256) FROM "+table+" WHERE "+where+" ORDER BY id",
          (RowCallbackHandler)rs->digest.update(rs.getString(1).getBytes(StandardCharsets.US_ASCII)));
      values.put(table,HexFormat.of().formatHex(digest.digest()));
    }
    return values;
  }

  private void installMeasurements() {
    jdbc.execute("CREATE TABLE benchmark_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,kind VARCHAR(32),object_id BIGINT,worker VARCHAR(128),at_time DATETIME(6))");
    jdbc.execute("CREATE TRIGGER benchmark_task AFTER UPDATE ON lp_cost_run_task FOR EACH ROW BEGIN "
        +"IF NEW.scene='MONTHLY_REPRICE' THEN "
        +"IF NEW.status='RUNNING' AND OLD.status<>'RUNNING' THEN INSERT INTO benchmark_events(kind,object_id,worker,at_time) VALUES('CLAIM',NEW.id,NEW.worker_id,NOW(6)); END IF; "
        +"IF JSON_EXTRACT(OLD.request_snapshot_json,'$.bomReady') IS NULL AND JSON_EXTRACT(NEW.request_snapshot_json,'$.bomReady') IS NOT NULL THEN INSERT INTO benchmark_events(kind,object_id,worker,at_time) VALUES('BOM_READY',NEW.id,NEW.worker_id,NOW(6)); END IF; "
        +"IF NEW.status<>OLD.status AND OLD.status='RUNNING' THEN INSERT INTO benchmark_events(kind,object_id,worker,at_time) VALUES(NEW.status,NEW.id,OLD.worker_id,NOW(6)); END IF; END IF; END");
    jdbc.execute("CREATE TRIGGER benchmark_group AFTER UPDATE ON lp_monthly_reprice_compute_group FOR EACH ROW BEGIN "
        +"IF NEW.status<>OLD.status THEN INSERT INTO benchmark_events(kind,object_id,worker,at_time) VALUES(CONCAT('GROUP_',NEW.status),NEW.id,NEW.worker_id,NOW(6)); END IF; END");
  }
  private Map<String,Object> databaseMetrics() {
    var result=new LinkedHashMap<String,Object>();
    result.put("at",Instant.now().toString());
    result.put("status",jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Queries','Com_select','Com_insert','Com_update','Com_delete','Bytes_sent','Bytes_received','Innodb_os_log_written','Innodb_data_written','Innodb_row_lock_time','Innodb_row_lock_waits','Threads_connected','Max_used_connections')"));
    result.put("tables",jdbc.queryForList("SELECT table_name,table_rows,data_length,index_length FROM information_schema.tables WHERE table_schema=DATABASE() AND data_length>0"));
    result.put("tablespaces",jdbc.queryForList("SELECT name,file_size,allocated_size FROM information_schema.innodb_tablespaces WHERE name LIKE CONCAT(DATABASE(),'/%')"));
    result.put("binaryLogs",jdbc.queryForList("SHOW BINARY LOGS"));return result;
  }
  private void startWorker(int index,String goLive) throws Exception {
    Path jar=Path.of(Objects.requireNonNull(System.getProperty("reprice.test.worker-jar")));
    Path directory=out.resolve("worker-"+index);Files.createDirectory(directory);Path log=directory.resolve("process.log");
    var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
        "-Xmx640m","-XX:ActiveProcessorCount=2","-Xlog:gc*:file=gc.log:time,uptime,level,tags",
        "-javaagent:"+Objects.requireNonNull(System.getProperty("benchmark.agent"))+"="+directory.resolve("method-metrics.csv"),
        "-XX:StartFlightRecording=filename=profile.jfr,settings=profile,maxsize=64m,dumponexit=true",
        "-jar",jar.toString(),"--monthly.reprice.worker-enabled=true","--monthly.reprice.concurrency=1",
        "--monthly.reprice.claim-batch-size=8","--monthly.reprice.capacity.max-active=4",
        "--monthly.reprice.go-live-months.COMMERCIAL="+goLive,"--srm.fixed-price.enabled=false",
        "--oa.person-directory.enabled=false","--oa.person-directory.run-at-startup=false","--cms.sync.publish.enabled=false",
        "--quote.drawing-verification.enabled=false","--spring.data.redis.repositories.enabled=false");
    var env=builder.environment();env.clear();env.put("PATH",System.getenv("PATH"));
    env.put("SPRING_DATASOURCE_URL",MYSQL.getJdbcUrl()+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false");
    env.put("SPRING_DATASOURCE_USERNAME",MYSQL.getUsername());env.put("SPRING_DATASOURCE_PASSWORD",MYSQL.getPassword());
    env.put("COST_RUN_WORKER_ID","benchmark");env.put("WORKER_DATASOURCE_MAX_POOL_SIZE","24");env.put("WORKER_DATASOURCE_MIN_IDLE","1");
    var process=builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    children.add(new Child(process,log,"starting"));
    await("packaged worker startup",Duration.ofSeconds(90),()->{
      assertThat(process.isAlive()).as(log.toString()).isTrue();
      try{return Files.exists(log)&&Files.readString(log).contains("调价实例容量 instance=");}
      catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    });
    var matcher=java.util.regex.Pattern.compile("调价worker实例 (\\S+) enabled=true").matcher(Files.readString(log));
    assertThat(matcher.find()).isTrue();children.set(children.size()-1,new Child(process,log,matcher.group(1)));
    write("workers.json",children.stream().map(c->Map.of("pid",c.process().pid(),"owner",c.owner(),"maxHeapMiB",640,"concurrency",1)).toList());
  }
  private void stopWorkers() throws Exception {
    for(var child:children)if(child.process().isAlive()) {
      child.process().destroy();if(!child.process().waitFor(40,TimeUnit.SECONDS))child.process().destroyForcibly().waitFor();
    }
  }
  private void await(String condition,Duration timeout,BooleanSupplier ready) throws Exception {
    long end=System.nanoTime()+timeout.toNanos();
    while(System.nanoTime()<end){if(ready.getAsBoolean())return;Thread.sleep(200);}
    fail("Timed out: "+condition);
  }
  private void write(String name,Object value) throws java.io.IOException {json.writerWithDefaultPrettyPrinter().writeValue(out.resolve(name).toFile(),value);}
  private static String identity(String product,String customer){return product+"|"+customer;}
  private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
}
