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
class MonthlyRepriceSelectedRetryIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired MonthlyCostVersionService savedCosts;
  @Autowired MonthlyRepriceUncalculatedQuery uncalculated;
  record Source(String oa,long form,long item,long version,String material) {}

  @Test void selectedRepairReusesNumbersAndKeepsEveryCustomerSourceAndPreviousSuccessfulVersion() throws Exception {
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
          if(i==4)for(int line=1;line<Integer.getInteger("reprice.test.failure-materials",301);line++) {
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
        assertThat(row.materials()).hasSize(Integer.getInteger("reprice.test.failure-materials",301));
      });
      // The failed preparation transaction rolled back; its complete material evidence remains in task diagnostics.
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_gap WHERE gap_material_code=? AND period_month=?",Integer.class,sources.get(4).material(),target.toString())).isZero();
      var bTask=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",b.getId());
      var bResult=jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE reprice_no=? AND oa_form_item_id=?",no,b.getOaFormItemId());
      var oldARow=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",oldA.version().getId());
      var bRow=jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",savedB.version().getId());
      var group=jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_compute_group WHERE batch_id=?",batch.getId());
      boolean browser=System.getProperty("reprice.test.browser.state")!=null;
      if(browser) {
        MonthlyRepriceUncalculatedBrowserSession.await(jdbc,Map.of("repriceNo",no,"month",target.toString(),"goLive",old.toString(),
            "failedTaskId",failure.getId(),"missingMaterial",sources.get(4).material(),"sources",sources,
            "oldCostVersionId",oldA.version().getId(),"protectedCostVersionId",savedB.version().getId()));
      } else {
      jdbc.update("UPDATE oa_form SET customer='A' WHERE id=?",sources.get(2).form());
      long sourceId=jdbc.queryForObject("SELECT id FROM lp_monthly_reprice_source WHERE batch_id=? AND source_cost_version_id=?",Long.class,batch.getId(),sources.get(2).version());
      contracts.retry(no,new RetrySelection(List.of(),List.of(sourceId)));
      var newA=claimTask(no,a.getId());repricing.execute(MonthlyRepriceCostingService.Lease.from(newA));
      assertThat(repriceMapper.readTask(no,failure.getId()).getStatus()).isEqualTo("FAILED");
      jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=2 WHERE material_code LIKE ?",sources.get(4).material()+"%");
      contracts.retry(no,new RetrySelection(List.of(failure.getId()),List.of()));
      var repaired=claimTask(no,failure.getId());repricing.execute(MonthlyRepriceCostingService.Lease.from(repaired));
      }
      var replacement=repriceMapper.results(no).stream().filter(r->"A".equals(r.getCustomerIdentityValue())).findFirst().orElseThrow();
      assertThat(replacement.getOaFormItemId()).isEqualTo(sources.get(2).item());
      assertThat(replacement.getCostRunVersionId()).isNotEqualTo(oldA.version().getId());
      assertThat(savedCosts.read(unit,replacement.getCostRunVersionId()).snapshot().calculation())
          .usingRecursiveComparison().isEqualTo(savedCosts.read(unit,oldA.version().getId()).snapshot().calculation());
      assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_compute_group WHERE id=?",group.get("id"))).isEqualTo(group);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",oldA.version().getId())).isEqualTo(oldARow);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",savedB.version().getId())).isEqualTo(bRow);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",b.getId())).usingRecursiveComparison().isEqualTo(bTask);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE reprice_no=? AND oa_form_item_id=?",no,b.getOaFormItemId())).usingRecursiveComparison().isEqualTo(bResult);
      var result=repriceMapper.readBatch(no);
      assertThat(result.getSuccessCount()).isEqualTo(3);assertThat(result.getFailedCount()).isZero();
      assertThat(result.getUnresolvedSourceCount()).isEqualTo(1);assertThat(result.getSourceCount()).isEqualTo(5);
      var report=checks.verify(result);assertThat(report.passed()).as(report.problems().toString()).isTrue();
      assertThat(jdbc.queryForObject("SELECT SUM(attempt_count) FROM lp_monthly_reprice_compute_group WHERE batch_id=?",Integer.class,batch.getId())).isEqualTo(2);
      for(var s:sources)assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",s.version())).isEqualTo(originals.get(s.version()));
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }
}
