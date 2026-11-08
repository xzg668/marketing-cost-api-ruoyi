package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceCostingService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Compare every independent expected customer/product and every source, never only output counts. */
@Tag("integration")
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class MonthlyRepriceBatchCoverageIntegrationTest extends MonthlyRepriceCostingFixture {
  @org.springframework.boot.test.mock.mockito.SpyBean MonthlyCostVersionService savedCosts;
  @Autowired com.sanhua.marketingcost.service.costing.MonthlyCostSnapshotDocuments documents;
  @Autowired com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec codec;
  @Autowired com.sanhua.marketingcost.mapper.monthly.MonthlyPriceQueryMapper priceQuery;
  @Autowired com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonInputs comparisonInputs;
  @Autowired com.sanhua.marketingcost.service.jjb.JjbReadService sheets;
  @Autowired com.sanhua.marketingcost.service.jjb.JjbExcelRenderer sheetExcel;
  @Autowired com.sanhua.marketingcost.mapper.MonthlyRepriceReuseMapper groupMapper;
  @org.springframework.boot.test.mock.mockito.SpyBean
  com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices monthlyPrices;
  @org.springframework.boot.test.mock.mockito.SpyBean
  com.sanhua.marketingcost.service.CostRunEngine engine;
  record Source(String product, String customer, String oa, long item, long version) {
    String identity() { return product + "|" + customer; }
  }

  @Test void everyOriginalSourceAndEveryCustomerProductSurvivesDeduplicationAndBatchExecution() throws Exception {
    boolean partialCheck=Boolean.getBoolean("reprice.test.partial-check");
    int bomLines=Integer.getInteger("reprice.test.bom-lines",1);
    assertThat(bomLines).isBetween(1,100);
    var resolvedComparisons = new java.util.concurrent.atomic.AtomicInteger();
    org.mockito.Mockito.doAnswer(invocation -> {
      var original = (com.sanhua.marketingcost.dto.CostRunObjectResult) invocation.callRealMethod();
      var resolved = engine.resolve(invocation.getArgument(0));
      assertThat(resolved.inputs().rows().stream().filter(row -> "TOTAL".equals(row.code()))
          .findFirst().orElseThrow().amount()).as("resolution has not executed full cost calculation").isNull();
      var result = resolved.bind(com.sanhua.marketingcost.service.costing.ResolvedOrdinaryCost.calculate(resolved.inputs()));
      assertThat(result.getResult()).usingRecursiveComparison().isEqualTo(original.getResult());
      assertThat(result.getCostItems()).usingRecursiveComparison().isEqualTo(original.getCostItems());
      assertThat(result.getPartItems()).usingRecursiveComparison().isEqualTo(original.getPartItems());
      assertThat(result.getSourceCostVersionId()).isEqualTo(original.getSourceCostVersionId());
      resolvedComparisons.incrementAndGet();
      return original;
    }).when(engine).run(org.mockito.ArgumentMatchers.any());
    unit="COMMERCIAL"; priceOrg="210"; materialOrg="COMMERCIAL";
    String target=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    String old=YearMonth.parse(target).minusMonths(1).toString();
    var date=YearMonth.parse(old).atDay(15).atTime(12,0);
    repriceProperties.setGoLiveMonths(Map.of(unit,old));
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("coverage-acceptance",null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    auth.setDetails(Map.of("businessUnitType",unit,"userId",1L));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES(?,?,'标准品',0)",unit,date.getYear());
      var sources=new ArrayList<Source>();
      var expected=new LinkedHashMap<String,Source>();
      for(int productIndex=0;productIndex<2;productIndex++) {
        String product="COVER-"+UUID.randomUUID().toString().substring(0,8), material=product+"-PART";
        var customers=productIndex==0 ? List.of("C1","C1","C1","C2") : List.of("C1","C3");
        long template=0;
        for(int i=0;i<customers.size();i++) {
          String oa=product+"-OA-"+i, customer=customers.get(i);
          if(i==0) jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer) VALUES(?,?,80000,?)",oa,unit,customer);
          else jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category) "
              +"SELECT ?,business_unit_type,copper_price,?,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category FROM oa_form WHERE id=?",oa,customer,template);
          long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oa);
          jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,?)",form,product,product,unit);
          long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
          if(i==0) {
            template=form;ordinarySources(product,material,form,date);comparisonFixedPrice(material,"2",date);
            for(int line=1;line<bomLines;line++) {
              String additional=material+"-"+line;
              comparisonAdditionalPart(product,material,additional,"1",date);
              comparisonFixedPrice(additional,"2",date);
            }
          }
          buildOriginalFixture(form,item,product,old,date,material,Map.of());
          var result=pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(oa,item,null,"coverage-acceptance",true));
          assertThat(result.getPipelineStatus()).as(result.getMessage()).isEqualTo("SUCCESS");
          var source=new Source(product,customer,oa,item,result.getCostVersionId());
          sources.add(source);expected.put(source.identity(),source);
        }
      }
      assertThat(sources).hasSize(6);assertThat(expected).hasSize(4);
      var originalRows=new LinkedHashMap<Long,Map<String,Object>>();
      for(var source:sources) originalRows.put(source.version(),jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",source.version()));
      var batch=contracts.enqueue(unit,YearMonth.parse(target));
      assertThat(batch.getSourceCount()).isEqualTo(6);assertThat(batch.getTotalCount()).isEqualTo(4);
      var tasks=repriceMapper.tasks(batch.getRepriceNo());assertThat(tasks).hasSize(4);
      var leases=new ArrayList<>(tasks.stream().map(t->MonthlyRepriceCostingService.Lease.from(claimTask(batch.getRepriceNo(),t.getId()))).toList());
      if(partialCheck) {
      // R-02: two complete customers, one known failure and one live lease stay independently visible.
      repricing.executeBatch(leases.subList(0,2));
      var missing=repriceMapper.readTask(batch.getRepriceNo(),tasks.get(2).getId());
      dispatch.fail(missing,missing.getWorkerId(),new IllegalArgumentException("fixture missing material price"));
      var partial=checks.verify(repriceMapper.readBatch(batch.getRepriceNo()));
      assertThat(partial.passed()).as(partial.problems().toString()).isTrue();
      assertThat(partial.evidence()).containsEntry("objects",4).containsEntry("checkedSuccess",2).containsEntry("uncalculatedObjects",2);
      assertThat(repriceMapper.results(batch.getRepriceNo()).stream().filter(r->!"SUCCESS".equals(r.getCalcStatus())))
          .hasSize(2).allSatisfy(r->assertThat(r.getTotalCost()).isNull());
      assertThat(repriceMapper.readTask(batch.getRepriceNo(),tasks.get(3).getId()).getStatus()).isEqualTo("RUNNING");
      // A corrupt saved amount/source is a batch integrity failure, not another ordinary exclusion.
      long checkedVersion=repriceMapper.readTask(batch.getRepriceNo(),tasks.getFirst().getId()).getCostRunVersionId();
      var finalAmount=jdbc.queryForObject("SELECT final_quote_amount FROM lp_quote_cost_run_version WHERE id=?",java.math.BigDecimal.class,checkedVersion);
      jdbc.update("UPDATE lp_quote_cost_run_version SET final_quote_amount=final_quote_amount+1 WHERE id=?",checkedVersion);
      assertThat(checks.verify(repriceMapper.readBatch(batch.getRepriceNo())).passed()).isFalse();
      jdbc.update("UPDATE lp_quote_cost_run_version SET final_quote_amount=? WHERE id=?",finalAmount,checkedVersion);
      String originalSnapshot=jdbc.queryForObject("SELECT source_snapshot_json FROM lp_monthly_reprice_source WHERE source_cost_version_id=?",String.class,sources.getFirst().version());
      jdbc.update("UPDATE lp_monthly_reprice_source SET source_snapshot_json=NULL WHERE source_cost_version_id=?",sources.getFirst().version());
      assertThat(checks.verify(repriceMapper.readBatch(batch.getRepriceNo())).passed()).isFalse();
      jdbc.update("UPDATE lp_monthly_reprice_source SET source_snapshot_json=? WHERE source_cost_version_id=?",originalSnapshot,sources.getFirst().version());
      repricing.executeBatch(List.of(leases.get(3)));
      retryFailed(batch.getRepriceNo());
      leases.set(2,MonthlyRepriceCostingService.Lease.from(claimTask(batch.getRepriceNo(),tasks.get(2).getId())));
      }
      org.mockito.Mockito.clearInvocations(monthlyPrices);
      var beforeWrites=writeMetrics();long started=System.nanoTime();
      java.util.List<MonthlyCostVersionService.Stored> output;
      try { output=repricing.executeBatch(leases); }
      finally {
        var afterWrites=writeMetrics();var delta=new java.util.TreeMap<String,Long>();
        beforeWrites.forEach((key,value)->delta.put(key,afterWrites.get(key)-value));
        System.out.println("REPRICE_WRITES bom_lines="+bomLines+" execute_ms="+(System.nanoTime()-started)/1_000_000+" counters="+delta);
      }
      var expectedMaterials=sources.stream().filter(source->!partialCheck || source.product().equals(tasks.get(2).getProductCode()))
          .flatMap(source -> java.util.stream.IntStream.range(0,bomLines)
          .mapToObj(line -> source.product()+"-PART"+(line==0?"":"-"+line))).collect(java.util.stream.Collectors.toSet());
      var unionLookups=org.mockito.Mockito.mockingDetails(monthlyPrices).getInvocations().stream()
          .filter(call -> call.getMethod().getName().equals("lookup"))
          .map(call -> (Collection<com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceRequest>)call.getArgument(0))
          .filter(requests -> requests.stream().map(request -> request.family().materialCode())
              .collect(java.util.stream.Collectors.toSet()).equals(expectedMaterials)).count();
      assertThat(unionLookups).as("real batch entry must issue the union price lookup for all actual products").isPositive();
      assertThat(output).hasSize(4);
      var results=repriceMapper.results(batch.getRepriceNo());
      assertThat(results).extracting(r->r.getProductCode()+"|"+r.getCustomerIdentityValue()).containsExactlyInAnyOrderElementsOf(expected.keySet());
      var ids=new HashSet<Long>();
      long fullSnapshotBytes=0, storedSnapshotBytes=0, fullPreparationBytes=0, storedPreparationBytes=0;
      for(var result:results) {
        var source=expected.get(result.getProductCode()+"|"+result.getCustomerIdentityValue());
        assertThat(result.getCalcStatus()).isEqualTo("SUCCESS");
        assertThat(result.getSourceCostVersionId()).isEqualTo(source.version());
        assertThat(result.getOaNo()).isEqualTo(source.oa());assertThat(result.getOaFormItemId()).isEqualTo(source.item());
        assertThat(ids.add(result.getCostRunVersionId())).isTrue();
        var saved=savedCosts.read(unit,result.getCostRunVersionId());var original=savedCosts.read(unit,source.version());
        assertThat(saved.version().getPricingMonth()).isEqualTo(target);
        assertThat(saved.version().getOaNo()).isEqualTo(source.oa());assertThat(saved.version().getOaFormItemId()).isEqualTo(source.item());
        var actual=saved.snapshot().calculation();var baseline=original.snapshot().calculation();
        assertThat(actual.totalCost()).isEqualByComparingTo(baseline.totalCost());
        assertThat(actual.cuMaterialAdjustment()).isEqualByComparingTo(baseline.cuMaterialAdjustment());
        assertThat(actual.finalQuoteAmount()).isEqualByComparingTo(baseline.finalQuoteAmount());
        assertThat(actual.ordinary()).usingRecursiveComparison().isEqualTo(baseline.ordinary());
        assertThat(actual.ordinaryInputs().details()).usingRecursiveComparison().isEqualTo(baseline.ordinaryInputs().details());
        assertThat(saved.parts()).extracting(p->Arrays.asList(p.getPartCode(),p.getQty(),p.getUnitPrice(),p.getAmount()))
            .containsExactlyElementsOf(original.parts().stream().map(p->Arrays.asList(p.getPartCode(),p.getQty(),p.getUnitPrice(),p.getAmount())).toList());
        assertThat(saved.parts()).allSatisfy(p->assertThat(p.getOaNo()).isEqualTo(source.oa()));
        String raw=jdbc.queryForObject("SELECT jianji_snapshot_json FROM lp_quote_cost_run_version WHERE id=?",String.class,saved.version().getId());
        assertThat(codec.decode(raw,com.fasterxml.jackson.databind.node.ObjectNode.class).path("monthlyPriceReferences").intValue()).isEqualTo(1);
        storedSnapshotBytes+=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        fullSnapshotBytes+=codec.wrap(documents.write(saved.snapshot(),false)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for(var adopted:saved.snapshot().calculation().adoptedPrices()) {
          assertThat(priceQuery.references(unit,target,adopted.selection().monthlyPriceId(),100,0))
              .anySatisfy(reference->assertThat(reference.versionId()).isEqualTo(saved.version().getId()));
        }
        for(String input:jdbc.queryForList("SELECT input_snapshot_json FROM lp_price_prepare_item WHERE prepare_no IN (?,?) AND status='READY' AND quantity<>0",
            String.class,saved.version().getFinancePricePrepareNo(),saved.version().getOaPricePrepareNo())) {
          assertThat(codec.decode(input,com.fasterxml.jackson.databind.node.ObjectNode.class)).isNotNull();
          storedPreparationBytes+=input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
          fullPreparationBytes+=codec.wrap(codec.unwrap(input)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        var loaded=comparisonInputs.load(unit,List.of(original.version(),saved.version()));
        assertThat(loaded).hasSize(2);
        assertThat(loaded.get(0).saved().snapshot()).isEqualTo(original.snapshot());
        assertThat(loaded.get(1).saved().snapshot()).isEqualTo(saved.snapshot());
        var view=sheets.read(source.oa(),source.item(),saved.version().getId());
        assertThat(view.sheet().identity().versionId()).isEqualTo(saved.version().getId());
        assertThatThrownBy(()->sheets.read("WRONG-OA",source.item(),saved.version().getId()))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        var bytes=new java.io.ByteArrayOutputStream();sheetExcel.write(view.sheet(),bytes);
        try(var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
          assertThat(book.getNumberOfSheets()).isEqualTo(1);
          assertThat(book.getSheetAt(0).getRow(0).getCell(0).getCellComment().getString().getString())
              .contains("versionId="+saved.version().getId(),"月度价格依据");
        }
        String evidence=System.getProperty("reprice.test.evidence");
        if(evidence!=null) java.nio.file.Files.write(java.nio.file.Path.of(evidence,"saved-cost-"+saved.version().getId()+".xlsx"),bytes.toByteArray());
      }
      var links=jdbc.queryForList("SELECT s.source_cost_version_id,s.oa_no,s.oa_form_item_id,r.product_code,r.customer_identity_value,r.cost_run_version_id "
          +"FROM lp_monthly_reprice_source s JOIN lp_monthly_reprice_result r ON r.id=s.object_id AND r.reuse_batch_id=s.batch_id WHERE s.batch_id=?",batch.getId());
      assertThat(links).hasSize(6);
      for(var source:sources) {
        var matches=links.stream().filter(r->((Number)r.get("source_cost_version_id")).longValue()==source.version()).toList();
        assertThat(matches).hasSize(1);var link=matches.getFirst();
        assertThat(link.get("oa_no")).isEqualTo(source.oa());assertThat(((Number)link.get("oa_form_item_id")).longValue()).isEqualTo(source.item());
        assertThat(link.get("product_code")).isEqualTo(source.product());assertThat(link.get("customer_identity_value")).isEqualTo(source.customer());
        assertThat(link.get("cost_run_version_id")).isNotNull();
        assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",source.version())).isEqualTo(originalRows.get(source.version()));
      }
      org.mockito.Mockito.clearInvocations(savedCosts);
      var report=checks.verify(repriceMapper.readBatch(batch.getRepriceNo()));assertThat(report.passed()).as(report.problems().toString()).isTrue();
      org.mockito.Mockito.verify(savedCosts,org.mockito.Mockito.never()).read(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong());
      assertThat(repricing.executeBatch(leases)).extracting(s->s.version().getId()).containsExactlyElementsOf(output.stream().map(s->s.version().getId()).toList());
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE reprice_no=?",Integer.class,batch.getRepriceNo())).isEqualTo(4);
      assertThat(resolvedComparisons).as("reference ordinary runs only; repricing resolves then shares arithmetic").hasValue(6);
      var groups = jdbc.queryForList("SELECT id,status,attempt_count FROM lp_monthly_reprice_compute_group WHERE batch_id=?",batch.getId());
      assertThat(groups).hasSize(2).allSatisfy(group -> assertThat(group)
          .containsEntry("status","SUCCESS").containsEntry("attempt_count",1));
      long fullGroupBytes=0;
      for(var group:groups) fullGroupBytes+=groupMapper.readGroup(((Number)group.get("id")).longValue())
          .inputDocument().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      long storedGroupBytes=jdbc.queryForObject("SELECT SUM(OCTET_LENGTH(input_document)) FROM lp_monthly_reprice_compute_group WHERE batch_id=?",Long.class,batch.getId());
      assertThat(storedGroupBytes).isLessThan(fullGroupBytes);
      System.out.printf("REPRICE_GROUP_STORAGE full=%d stored=%d%n",fullGroupBytes,storedGroupBytes);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_result WHERE reuse_batch_id=? AND compute_group_id IS NOT NULL",Integer.class,batch.getId())).isEqualTo(4);
      System.out.println("REPRICE_COVERAGE M=6 U=4 G=2 source_links=6 unique_success_versions=4 missing=0 wrong_owner=0 amount_mismatch=0");
      assertThat(storedSnapshotBytes).isLessThan(fullSnapshotBytes);
      assertThat(storedPreparationBytes).isLessThan(fullPreparationBytes);
      System.out.printf("REPRICE_STORAGE customers=4 snapshot_full=%d snapshot_stored=%d preparation_full=%d preparation_stored=%d exports=4%n",
          fullSnapshotBytes,storedSnapshotBytes,fullPreparationBytes,storedPreparationBytes);
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }

  private Map<String,Long> writeMetrics() {
    var result=new TreeMap<String,Long>();
    for(var row:jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Com_insert','Com_update','Com_delete','Com_stmt_execute','Bytes_received','Bytes_sent')"))
      result.put(row.get("Variable_name").toString(),Long.valueOf(row.get("Value").toString()));
    result.put("binlog_bytes",jdbc.queryForList("SHOW BINARY LOGS").stream()
        .mapToLong(row->((Number)row.get("File_size")).longValue()).sum());
    return result;
  }
}
