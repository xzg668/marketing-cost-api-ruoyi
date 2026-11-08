package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection;
import com.sanhua.marketingcost.service.reprice.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Real costing, real missing material, then explicit customer/source retries with preserved history. */
@Tag("integration")
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class MonthlyRepricePublicationIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired MonthlyCostVersionService savedCosts;
  @Autowired MonthlyRepriceUncalculatedQuery uncalculated;
  @Autowired com.sanhua.marketingcost.service.ingest.QuoteCostResultHistoryService history;
  @Autowired com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper results;
  @Autowired com.sanhua.marketingcost.mapper.MonthlyCostComparisonMapper comparisons;
  @Autowired com.sanhua.marketingcost.service.MonthlyRepriceQueryService queries;
  @Autowired MonthlyCostComparisonService comparisonService;
  record Source(String oa,long form,long item,long version,String material) {}

  @Test void partialPublicationAndRepairPreserveApprovedCostsAndFirstRelease() throws Exception {
    unit="COMMERCIAL";priceOrg="210";materialOrg="COMMERCIAL";
    var target=YearMonth.parse(com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth());
    var old=target.minusMonths(1);var date=old.atDay(15).atTime(12,0);
    repriceProperties.setGoLiveMonths(Map.of(unit,old.toString()));
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("selected-retry-acceptance",null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    auth.setDetails(Map.of("businessUnitType",unit,"userId",1L));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES(?,?,'标准品',0)",unit,date.getYear());
      String prefix="SEL-"+UUID.randomUUID().toString().substring(0,8);
      var sources=new ArrayList<Source>();long template=0;
      for(int i=0;i<5;i++) {
        String product=prefix+(i==4?"-M":"-S"),material=product+"-PART",oa=prefix+"-OA-"+i;
        String customer=List.of("A","B","A","D","E").get(i);
        if(i==0 || i==4)jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer) VALUES(?,?,80000,?)",oa,unit,customer);
        else jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category) "
            +"SELECT ?,business_unit_type,copper_price,?,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category FROM oa_form WHERE id=?",oa,customer,template);
        long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oa);
        jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,?)",form,product,product,unit);
        long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
        if(i==0 || i==4) {
          if(i==0)template=form;ordinarySources(product,material,form,date);comparisonFixedPrice(material,"2",date);
          if(i==4)for(int line=1;line<1;line++) {
            comparisonAdditionalPart(product,material,material+"-"+line,"1",date);
            comparisonFixedPrice(material+"-"+line,"2",date);
          }
        }
        buildOriginalFixture(form,item,product,old.toString(),date,material,Map.of());
        var cost=pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(oa,item,null,"selected-retry-acceptance",true));
        assertThat(cost.getPipelineStatus()).as(cost.getMessage()).isEqualTo("SUCCESS");
        sources.add(new Source(oa,form,item,cost.getCostVersionId(),material));
      }
      var originals=new LinkedHashMap<Long,Map<String,Object>>();
      for(var s:sources)originals.put(s.version(),jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",s.version()));
      for(int i:List.of(2,3))jdbc.update("UPDATE oa_form SET customer=NULL WHERE id=?",sources.get(i).form());
      // Existing policy may reuse the latest historical period; an expired date alone is not missing input.
      jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=NULL WHERE material_code LIKE ?",sources.get(4).material()+"%");
      var batch=contracts.enqueue(unit,target);String no=batch.getRepriceNo();
      assertThat(batch.getSourceCount()).isEqualTo(5);assertThat(batch.getTotalCount()).isEqualTo(3);
      assertThat(batch.getUnresolvedSourceCount()).isEqualTo(2);
      var tasks=repriceMapper.tasks(no);var a=claimTask(no,tasks.get(0).getId());
      var oldA=repricing.execute(MonthlyRepriceCostingService.Lease.from(a));
      var b=claimTask(no,tasks.get(1).getId());var savedB=repricing.execute(MonthlyRepriceCostingService.Lease.from(b));
      var failure=claimTask(no,tasks.get(2).getId());
      Throwable missing=catchThrowable(()->repricing.execute(MonthlyRepriceCostingService.Lease.from(failure)));
      assertThat(missing).isInstanceOf(MonthlyRepriceInputNotReady.class);
      dispatch.fail(failure,failure.getWorkerId(),(Exception)missing);
      var page=uncalculated.page(target.toString(),null,null,"FAILED",1,20);
      assertThat(page.list()).singleElement().satisfies(row->{
        assertThat(row.id()).isEqualTo(failure.getId());assertThat(row.reason()).isNotBlank();
        assertThat(row.materials()).extracting(issue->issue.materialCode()).contains(sources.get(4).material());
        assertThat(row.materials()).hasSize(1);
      });
      // The failed preparation transaction rolled back; its complete material evidence remains in task diagnostics.
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_gap WHERE gap_material_code=? AND period_month=?",Integer.class,sources.get(4).material(),target.toString())).isZero();
      for(var source:sources)assertThat(history.listHistory(source.oa(),source.item()).getResults())
          .allSatisfy(row->{assertThat(row.getResultType()).isEqualTo("QUOTE_COST");assertThat(row.getVersionId()).isEqualTo(source.version());});
      var checking=contracts.recheck(no);
      var report=checks.verify(checking);assertThat(report.passed()).as(report.problems().toString()).isTrue();
      var checked=contracts.recordCheck(report);
      var confirmed=workflow.confirm(no,checked.getResultRevision()).batch();
      assertThat(confirmed.getPublishedRevision()).isEqualTo(checked.getResultRevision());
      assertThat(contracts.blocksQuotation(unit)).isFalse();
      assertThat(pricingPeriods.currentMonth(unit)).isEqualTo(target.toString());
      var oldARow=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",oldA.version().getId());
      var bRow=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",savedB.version().getId());
      var bTask=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",b.getId());
      var bResult=jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE reprice_no=? AND oa_form_item_id=?",no,b.getOaFormItemId());
      assertThat(oldARow.get("confirmed_at")).isNotNull();
      long aResult=repriceMapper.results(no).stream().filter(r->"A".equals(r.getCustomerIdentityValue())).findFirst().orElseThrow().getId();
      assertPublished(no,aResult,oldA.version().getId(),sources.getFirst(),old.toString(),target.toString());
      assertThat(uncalculated.page(target.toString(),null,null,"FAILED",1,20).list()).singleElement()
          .satisfies(row->assertThat(row.canRetry()).isTrue());
      jdbc.update("UPDATE oa_form SET customer='A' WHERE id=?",sources.get(2).form());
      long sourceId=jdbc.queryForObject("SELECT id FROM lp_monthly_reprice_source WHERE batch_id=? AND source_cost_version_id=?",Long.class,batch.getId(),sources.get(2).version());
      contracts.retry(no,new RetrySelection(List.of(),List.of(sourceId)));
      assertThat(contracts.blocksQuotation(unit)).isFalse();
      assertThat(pricingPeriods.currentMonth(unit)).isEqualTo(target.toString());
      var pending=repriceMapper.results(no).stream().filter(r->"A".equals(r.getCustomerIdentityValue())).findFirst().orElseThrow();
      assertThat(pending.getCostRunVersionId()).isNull();
      assertThat(pending.getPublishedCostVersionId()).isEqualTo(oldA.version().getId());
      assertThat(pending.getPublishedSourceCostVersionId()).isEqualTo(sources.getFirst().version());
      assertPublished(no,aResult,oldA.version().getId(),sources.getFirst(),old.toString(),target.toString());
      var newA=claimTask(no,a.getId());var replacement=repricing.execute(MonthlyRepriceCostingService.Lease.from(newA));
      assertThat(replacement.version().getId()).isNotEqualTo(oldA.version().getId());
      assertThat(jdbc.queryForObject("SELECT published_cost_version_id FROM lp_monthly_reprice_result WHERE id=?",Long.class,pending.getId())).isEqualTo(oldA.version().getId());
      assertThat(jdbc.queryForObject("SELECT confirmed_at FROM lp_quote_cost_run_version WHERE id=?",Object.class,replacement.version().getId())).isNull();
      assertPublished(no,aResult,oldA.version().getId(),sources.getFirst(),old.toString(),target.toString());
      jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=2 WHERE material_code LIKE ?",sources.get(4).material()+"%");
      contracts.retry(no,new RetrySelection(List.of(failure.getId()),List.of()));
      var repaired=claimTask(no,failure.getId());repricing.execute(MonthlyRepriceCostingService.Lease.from(repaired));
      var pendingE=results.publishedResult(no,repriceMapper.results(no).stream().filter(r->"E".equals(r.getCustomerIdentityValue())).findFirst().orElseThrow().getId());
      assertThat(pendingE.getCostRunVersionId()).isNull();assertThat(pendingE.getTotalCost()).isNull();
      assertThat(pendingE.getCalcStatus()).isEqualTo("WAIT_CONFIRM");
      assertThat(history.listHistory(sources.get(4).oa(),sources.get(4).item()).getResults()).hasSize(1);
      dispatch.checkCompleted();var current=repriceMapper.readBatch(no);
      assertThat(current.getStatus()).isEqualTo("WAIT_CONFIRM");
      var beforeConfirm=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",replacement.version().getId());
      jdbc.update("UPDATE lp_quote_cost_run_version SET final_quote_amount=final_quote_amount+1 WHERE id=?",replacement.version().getId());
      try {assertThatThrownBy(()->workflow.confirm(no,current.getResultRevision())).hasMessageContaining("Stored total2");}
      finally {jdbc.update("UPDATE lp_quote_cost_run_version SET final_quote_amount=?,updated_at=? WHERE id=?",beforeConfirm.get("final_quote_amount"),beforeConfirm.get("updated_at"),replacement.version().getId());}
      jdbc.execute("CREATE TRIGGER reject_publication_audit BEFORE INSERT ON lp_monthly_reprice_audit_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PUBLICATION_ROLLBACK'");
      try {assertThatThrownBy(()->workflow.confirm(no,current.getResultRevision())).hasStackTraceContaining("PUBLICATION_ROLLBACK");}
      finally {jdbc.execute("DROP TRIGGER reject_publication_audit");}
      assertThat(jdbc.queryForObject("SELECT published_cost_version_id FROM lp_monthly_reprice_result WHERE id=?",Long.class,pending.getId())).isEqualTo(oldA.version().getId());
      assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",replacement.version().getId())).isEqualTo(beforeConfirm);
      var released=workflow.confirm(no,current.getResultRevision()).batch();
      assertPublished(no,aResult,replacement.version().getId(),sources.get(2),old.toString(),target.toString());
      assertThat(released.getConfirmedAt()).isEqualTo(confirmed.getConfirmedAt());
      assertThat(released.getConfirmedBy()).isEqualTo(confirmed.getConfirmedBy());
      assertThat(released.getPublishedRevision()).isGreaterThan(confirmed.getPublishedRevision());
      assertThat(jdbc.queryForObject("SELECT published_cost_version_id FROM lp_monthly_reprice_result WHERE id=?",Long.class,pending.getId())).isEqualTo(replacement.version().getId());
      assertThat(contracts.blocksQuotation(unit)).isFalse();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_audit_log WHERE reprice_no=? AND operation_type='CONFIRM'",Integer.class,no)).isEqualTo(2);
      workflow.confirm(no,current.getResultRevision());
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_audit_log WHERE reprice_no=? AND operation_type='CONFIRM'",Integer.class,no)).isEqualTo(2);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",oldA.version().getId())).isEqualTo(oldARow);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",savedB.version().getId())).isEqualTo(bRow);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",b.getId())).usingRecursiveComparison().isEqualTo(bTask);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE reprice_no=? AND oa_form_item_id=?",no,b.getOaFormItemId())).usingRecursiveComparison().isEqualTo(bResult);
      for(var s:sources)assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",s.version())).isEqualTo(originals.get(s.version()));
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }
  private void assertPublished(String no,long resultId,long versionId,Source original,String old,String current) {
    var projected=results.publishedResult(no,resultId);
    assertThat(projected.getCostRunVersionId()).isEqualTo(versionId);
    assertThat(projected.getOaNo()).isEqualTo(original.oa());
    assertThat(projected.getSourceCostVersionId()).isEqualTo(original.version());
    var historyRows=history.listHistory(original.oa(),original.item()).getResults();
    assertThat(historyRows).filteredOn(row->"QUOTE_COST".equals(row.getResultType())).singleElement()
        .satisfies(row->assertThat(row.getVersionId()).isEqualTo(original.version()));
    assertThat(historyRows).filteredOn(row->"MONTHLY_REPRICE".equals(row.getResultType())).singleElement()
        .satisfies(row->assertThat(row.getVersionId()).isEqualTo(versionId));
    // Quotation history always exposes the approved cost, including for a reviewer account.
    var detail=history.getMonthlyResult(original.oa(),original.item(),resultId);
    assertThat(detail.getResult().getVersionId()).isEqualTo(versionId);
    assertThat(detail.getPartItems()).allSatisfy(row->assertThat(row.getOaNo()).isEqualTo(original.oa()));
    var previous=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    var reader=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("publication-reader",null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BU_STAFF")));
    reader.setDetails(Map.of("businessUnitType",unit,"userId",2L));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(reader);
    try {
      var page=queries.pageResults(no,null);
      assertThat(page.getList()).filteredOn(row->row.getId()==resultId).singleElement()
          .satisfies(row->{assertThat(row.getSourceCostVersionId()).isEqualTo(original.version());assertThat(row.getOaNo()).isEqualTo(original.oa());});
      assertThat(queries.listPartItems(no,resultId)).allSatisfy(row->assertThat(row.getOaNo()).isEqualTo(original.oa()));
      assertThat(queries.pageSources(no,resultId,1,20).getList()).filteredOn(row->row.selected()).singleElement()
          .satisfies(row->assertThat(row.sourceCostVersionId()).isEqualTo(original.version()));
      var scope=new com.sanhua.marketingcost.mapper.MonthlyCostComparisonMapper.Scope(unit,old,current,null,null,null,false,0,100);
      assertThat(comparisons.pageSelections(scope)).filteredOn(row->Objects.equals(row.resultId(),resultId)).singleElement()
          .satisfies(row->{assertThat(row.versionId()).isEqualTo(versionId);assertThat(row.oaNo()).isEqualTo(original.oa());});
      assertThat(comparisonService.compare(new MonthlyCostComparisonService.Request(old,current,null,null,null,null,1,100)).comparison().rows()).isNotEmpty();
    } finally {org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(previous);}
  }

}
