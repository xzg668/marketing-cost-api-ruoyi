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
class MonthlyRepricePublicationBrowserIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired MonthlyCostVersionService savedCosts;
  @Autowired MonthlyRepriceUncalculatedQuery uncalculated;
  @Autowired com.sanhua.marketingcost.service.ingest.QuoteCostResultHistoryService history;
  @Autowired com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper results;
  @Autowired com.sanhua.marketingcost.mapper.MonthlyCostComparisonMapper comparisons;
  @Autowired com.sanhua.marketingcost.service.MonthlyRepriceQueryService queries;
  @Autowired MonthlyCostComparisonService comparisonService;
  record Source(String oa,long form,long item,long version,String material) {}

  @Test void browserPublishesSuccessfulSubsetThenRepairsOnlySelectedCustomers() throws Exception {
    unit="COMMERCIAL";priceOrg="210";materialOrg="COMMERCIAL";
    var target=YearMonth.parse(com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth());
    var old=target.minusMonths(1);var original=old.minusMonths(1);var date=original.atDay(15).atTime(12,0);
    repriceProperties.setGoLiveMonths(Map.of(unit,original.toString()));
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
        buildOriginalFixture(form,item,product,original.toString(),date,material,Map.of());
        var cost=pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(oa,item,null,"selected-retry-acceptance",true));
        assertThat(cost.getPipelineStatus()).as(cost.getMessage()).isEqualTo("SUCCESS");
        sources.add(new Source(oa,form,item,cost.getCostVersionId(),material));
      }
      // Explicit isolated chronology: only A, B and E existed before the baseline month closed.
      for(int i:List.of(0,1,4))jdbc.update("UPDATE lp_quote_cost_run_version SET trial_finished_at=? WHERE id=?",date,sources.get(i).version());
      var baseline=contracts.enqueue(unit,old);
      for(var t:repriceMapper.tasks(baseline.getRepriceNo())) {
        var lease=claimTask(baseline.getRepriceNo(),t.getId());repricing.execute(MonthlyRepriceCostingService.Lease.from(lease));
      }
      dispatch.checkCompleted();baseline=repriceMapper.readBatch(baseline.getRepriceNo());
      workflow.confirm(baseline.getRepriceNo(),baseline.getResultRevision());
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
      long aResult=repriceMapper.results(no).stream().filter(r->"A".equals(r.getCustomerIdentityValue())).findFirst().orElseThrow().getId();
      long missingCustomer=jdbc.queryForObject("SELECT id FROM lp_monthly_reprice_source WHERE batch_id=? AND source_cost_version_id=?",Long.class,batch.getId(),sources.get(2).version());
      var protectedBaseline=jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version WHERE reprice_no=? ORDER BY id",baseline.getRepriceNo());
      var sample=new LinkedHashMap<String,Object>();sample.put("repriceNo",no);sample.put("month",target.toString());
      sample.put("baselineMonth",old.toString());sample.put("goLive",original.toString());sample.put("sources",sources);
      sample.put("aResultId",aResult);sample.put("missingCustomerSourceId",missingCustomer);sample.put("failedTaskId",failure.getId());
      sample.put("missingMaterial",sources.get(4).material());sample.put("oldCostVersionId",oldA.version().getId());
      sample.put("protectedCostVersionId",savedB.version().getId());sample.put("protectedTaskId",b.getId());sample.put("aTaskId",a.getId());
      // These owned role fixtures also need the existing quotation-history read permission.
      // A hidden function grant adds no production permission or business navigation.
      jdbc.update("INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,status,visible,perms) VALUES(9847001,'OPT047 history read',40197,99,'#','F','0','1','ingest:quote:list')");
      jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(10,9847001),(11,9847001)");
      MonthlyRepriceUncalculatedBrowserSession.await(jdbc,sample);
      var finished=repriceMapper.readBatch(no);
      assertThat(finished.getStatus()).isEqualTo("CONFIRMED");assertThat(finished.getPublishedRevision()).isEqualTo(finished.getResultRevision());
      assertThat(finished.getSourceCount()).isEqualTo(5);assertThat(finished.getTotalCount()).isEqualTo(3);
      assertThat(finished.getSuccessCount()).isEqualTo(3);assertThat(finished.getUnresolvedSourceCount()).isEqualTo(1);
      assertThat(contracts.blocksQuotation(unit)).isFalse();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_audit_log WHERE reprice_no=? AND operation_type='CONFIRM'",Integer.class,no)).isEqualTo(2);
      for(var source:sources)assertThat(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",source.version())).isEqualTo(originals.get(source.version()));
      assertThat(jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version WHERE reprice_no=? ORDER BY id",baseline.getRepriceNo())).isEqualTo(protectedBaseline);
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }
}
