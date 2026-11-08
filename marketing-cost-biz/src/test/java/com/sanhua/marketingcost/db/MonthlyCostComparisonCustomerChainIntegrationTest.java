package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection;
import com.sanhua.marketingcost.service.reprice.*;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparison.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Actual saved costs in two repricing months, different representative OA, failure and selected repair. */
@Tag("integration")
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class MonthlyCostComparisonCustomerChainIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired MonthlyCostComparisonService comparison;
  @Autowired MonthlyCostComparisonExportService exports;
  record Source(String oa,long form,long item,long version,String product,String material) {}
  @Test void twoSavedMonthsPairCustomersKeepUncalculatedObjectsAndUpdateOnlyRepairedCosts() throws Exception {
    unit="COMMERCIAL";priceOrg="210";materialOrg="COMMERCIAL";
    var current=YearMonth.parse(com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth());
    var baseline=current.minusMonths(1);var original=baseline.minusMonths(1);var date=original.atDay(15).atTime(12,0);
    repriceProperties.setGoLiveMonths(Map.of(unit,original.toString()));
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("comparison-customer-chain",null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    auth.setDetails(Map.of("businessUnitType",unit,"userId",1L));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES(?,?,'标准品',0)",unit,date.getYear());
      String prefix="CMP-"+UUID.randomUUID().toString().substring(0,8);var sources=new ArrayList<Source>();long template=0;
      for(int i=0;i<5;i++) {
        String product=prefix+(i==2?"-E":"-S"),material=product+"-PART",oa=prefix+"-OA-"+i,customer=List.of("A","B","E","A","D").get(i);
        if(i==0 || i==2)jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer) VALUES(?,?,80000,?)",oa,unit,customer);
        else jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category) "
            +"SELECT ?,business_unit_type,copper_price,?,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category FROM oa_form WHERE id=?",oa,customer,template);
        long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oa);
        jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,?)",form,product,product,unit);
        long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
        if(i==0 || i==2){if(i==0)template=form;ordinarySources(product,material,form,date);comparisonFixedPrice(material,"2",date);}
        buildOriginalFixture(form,item,product,original.toString(),date,material,Map.of());
        var cost=pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(oa,item,null,"comparison-customer-chain",true));
        assertThat(cost.getPipelineStatus()).as(cost.getMessage()).isEqualTo("SUCCESS");
        // Establish owned fixture chronology before either batch is accepted; no amount or saved input is changed.
        if(i<3)jdbc.update("UPDATE lp_quote_cost_run_version SET trial_finished_at=? WHERE id=?",date.plusSeconds(i),cost.getCostVersionId());
        sources.add(new Source(oa,form,item,cost.getCostVersionId(),product,material));
      }
      var old=contracts.enqueue(unit,baseline);assertThat(old.getSourceCount()).isEqualTo(3);
      for(var task:repriceMapper.tasks(old.getRepriceNo()))repricing.execute(MonthlyRepriceCostingService.Lease.from(claimTask(old.getRepriceNo(),task.getId())));
      var report=checks.verify(repriceMapper.readBatch(old.getRepriceNo()));assertThat(report.passed()).as(report.problems().toString()).isTrue();
      old=contracts.recordCheck(report);workflow.confirm(old.getRepriceNo(),old.getResultRevision());
      var protectedVersions=jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version ORDER BY id");
      jdbc.update("UPDATE oa_form SET customer=NULL WHERE id=?",sources.get(4).form());
      jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=NULL WHERE material_code=?",sources.get(2).material());
      var next=contracts.enqueue(unit,current);assertThat(next.getSourceCount()).isEqualTo(5);assertThat(next.getTotalCount()).isEqualTo(3);
      Long failed=null;
      for(var task:repriceMapper.tasks(next.getRepriceNo())) {
        var claimed=claimTask(next.getRepriceNo(),task.getId());
        if(task.getProductCode().equals(sources.get(2).product())) {
          var error=catchThrowable(()->repricing.execute(MonthlyRepriceCostingService.Lease.from(claimed)));
          assertThat(error).isInstanceOf(MonthlyRepriceInputNotReady.class);dispatch.fail(claimed,claimed.getWorkerId(),(Exception)error);failed=claimed.getId();
        } else repricing.execute(MonthlyRepriceCostingService.Lease.from(claimed));
      }
      var request=new MonthlyCostComparisonService.Request(baseline.toString(),current.toString(),List.of(Metric.MATERIAL),null,null,null,1,20);
      var page=comparison.compare(request);assertThat(page.totalProducts()).isEqualTo(4);
      var a=page.comparison().products().stream().filter(p->"A".equals(p.identity().customerValue())).findFirst().orElseThrow();
      assertThat(a.baselineState().oaNo()).isEqualTo(sources.get(0).oa());assertThat(a.comparisonState().oaNo()).isEqualTo(sources.get(3).oa());
      assertThat(a.baseline().amounts()).isEqualTo(a.comparison().amounts());assertThat(a.change()).isEqualTo(Change.UNCHANGED);
      var e=page.comparison().products().stream().filter(p->"E".equals(p.identity().customerValue())).findFirst().orElseThrow();
      assertThat(e.baseline()).isNotNull();assertThat(e.comparison()).isNull();assertThat(e.comparisonState().status()).isEqualTo("FAILED");
      assertThat(page.comparison().products().stream().filter(p->!p.identity().matched()).count()).isEqualTo(1);
      var evidence=java.nio.file.Path.of(System.getProperty("reprice.test.evidence"));
      try(var export=exports.export(request)){java.nio.file.Files.copy(export.path(),evidence.resolve("before-repair.xlsx"));}
      assertThat(failed).isNotNull();
      if(System.getProperty("reprice.test.browser.state")!=null) {
        MonthlyRepriceUncalculatedBrowserSession.await(jdbc,Map.of("month",current.toString(),"baselineMonth",baseline.toString(),"goLive",original.toString(),
            "repriceNo",next.getRepriceNo(),"baselineRepriceNo",old.getRepriceNo(),"sources",sources,
            "failedTaskId",failed,"missingMaterial",sources.get(2).material()));
      } else {
        jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=2 WHERE material_code=?",sources.get(2).material());
        contracts.retry(next.getRepriceNo(),new RetrySelection(List.of(failed),List.of()));
        repricing.execute(MonthlyRepriceCostingService.Lease.from(claimTask(next.getRepriceNo(),failed)));
      }
      var repaired=comparison.compare(request);assertThat(repaired.totalProducts()).isEqualTo(4);
      assertThat(repaired.comparison().products().stream().filter(p->"E".equals(p.identity().customerValue())).findFirst().orElseThrow().comparison()).isNotNull();
      try(var export=exports.export(request)){java.nio.file.Files.copy(export.path(),evidence.resolve("after-repair.xlsx"));}
      for(var row:protectedVersions)assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",row.get("id"))).isEqualTo(row);
      new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValue(evidence.resolve("comparison-proof.json").toFile(),
          Map.of("before",page,"after",repaired,"protectedVersions",protectedVersions.size(),"originalSources",sources));
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }
}
