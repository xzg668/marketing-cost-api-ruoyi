package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceCostingService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceSharedNumbers;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Fail real customer writes on both sides of binding, then recover through the production entry. */
@Tag("integration")
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class MonthlyRepriceCustomerRecoveryIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired MonthlyCostVersionService savedCosts;
  @Autowired MonthlyRepriceSharedNumbers shared;
  record Source(String customer, String oa, long item, long version) {}

  @Test void independentCustomerRetriesKeepThePublishedGroupAndEverySuccessfulCustomerUnchanged() {
    unit="COMMERCIAL"; priceOrg="210"; materialOrg="COMMERCIAL";
    String target=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    String old=YearMonth.parse(target).minusMonths(1).toString();
    var date=YearMonth.parse(old).atDay(15).atTime(12,0);
    repriceProperties.setGoLiveMonths(Map.of(unit,old));
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("recovery-acceptance",null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    auth.setDetails(Map.of("businessUnitType",unit,"userId",1L));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES(?,?,'标准品',0)",unit,date.getYear());
      String product="RECOVER-"+UUID.randomUUID().toString().substring(0,8), material=product+"-PART";
      var sources=new ArrayList<Source>();
      long template=0;
      int bomLines=Integer.getInteger("reprice.test.bom-lines",1);
      assertThat(bomLines).isBetween(1,100);
      for(int i=0;i<3;i++) {
        String oa=product+"-OA-"+i, customer="CUSTOMER-"+i;
        if(i==0) jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer) VALUES(?,?,80000,?)",oa,unit,customer);
        else jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category) "
            +"SELECT ?,business_unit_type,copper_price,?,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category FROM oa_form WHERE id=?",oa,customer,template);
        long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oa);
        jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,?)",form,product,product,unit);
        long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
        if(i==0) {
          template=form; ordinarySources(product,material,form,date); comparisonFixedPrice(material,"2",date);
          for(int line=1;line<bomLines;line++) {
            String part=material+"-"+line;
            comparisonAdditionalPart(product,material,part,"1",date); comparisonFixedPrice(part,"2",date);
          }
        }
        buildOriginalFixture(form,item,product,old,date,material,Map.of());
        var original=pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(oa,item,null,"recovery-acceptance",true));
        assertThat(original.getPipelineStatus()).as(original.getMessage()).isEqualTo("SUCCESS");
        sources.add(new Source(customer,oa,item,original.getCostVersionId()));
      }
      var originalRows=new LinkedHashMap<Long,Map<String,Object>>();
      for(var source:sources) originalRows.put(source.version(),versionRow(source.version()));
      var batch=contracts.enqueue(unit,YearMonth.parse(target));
      String no=batch.getRepriceNo();
      assertThat(batch.getSourceCount()).isEqualTo(3); assertThat(batch.getTotalCount()).isEqualTo(3);
      var tasks=repriceMapper.tasks(no).stream().map(task->claimTask(no,task.getId())).toList();
      var first=tasks.get(0); var second=tasks.get(1); var third=tasks.get(2);
      assertThatThrownBy(()->repricing.execute(new MonthlyRepriceCostingService.Lease(no,first.getId(),first.getExecutionNo(),"not-owner")))
          .hasMessageContaining("持有者");

      // The shared calculation is committed, but this customer's entire save must roll back.
      jdbc.execute("CREATE TRIGGER reprice_recovery_before_bind BEFORE UPDATE ON lp_monthly_reprice_result FOR EACH ROW BEGIN "
          +"IF NEW.oa_form_item_id="+first.getOaFormItemId()+" AND NEW.compute_group_id IS NOT NULL THEN "
          +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RECOVERY_BEFORE_BIND'; END IF; END");
      try { assertThatThrownBy(()->repricing.execute(MonthlyRepriceCostingService.Lease.from(first))).hasStackTraceContaining("RECOVERY_BEFORE_BIND"); }
      finally { jdbc.execute("DROP TRIGGER reprice_recovery_before_bind"); }
      assertThat(targetCount(no)).isZero();
      var publication=jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_compute_group WHERE batch_id=?",batch.getId());
      assertThat(publication).containsEntry("status","SUCCESS").containsEntry("attempt_count",1);
      long group=((Number)publication.get("id")).longValue();
      contracts.fail(no,first.getId(),first.getExecutionNo(),first.getWorkerId(),"controlled failure before customer binding");

      var successful=repricing.execute(MonthlyRepriceCostingService.Lease.from(second));
      var successfulRow=versionRow(successful.version().getId());
      var successfulTask=jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",second.getId());
      var successfulResult=jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE reprice_no=? AND calc_object_key=?",no,second.getCalcObjectKey());
      assertThat(successfulResult).containsEntry("compute_group_id",group);

      // A failure after binding and result linking must not leave a half-saved customer version.
      jdbc.execute("CREATE TRIGGER reprice_recovery_after_bind BEFORE UPDATE ON lp_cost_run_task FOR EACH ROW BEGIN "
          +"IF NEW.id="+third.getId()+" AND NEW.status='SUCCESS' THEN "
          +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RECOVERY_AFTER_BIND'; END IF; END");
      try { assertThatThrownBy(()->repricing.execute(MonthlyRepriceCostingService.Lease.from(third))).hasStackTraceContaining("RECOVERY_AFTER_BIND"); }
      finally { jdbc.execute("DROP TRIGGER reprice_recovery_after_bind"); }
      contracts.fail(no,third.getId(),third.getExecutionNo(),third.getWorkerId(),"controlled failure after customer binding");
      assertThat(targetCount(no)).isEqualTo(1);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_compute_group WHERE id=?",group)).isEqualTo(publication);
      assertThat(versionRow(successful.version().getId())).isEqualTo(successfulRow);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE reprice_no=? AND calc_object_key=?",no,second.getCalcObjectKey()))
          .usingRecursiveComparison().isEqualTo(successfulResult);
      for(var task:List.of(first,third)) {
        assertThat(repriceMapper.readTask(no,task.getId()).getStatus()).isEqualTo("FAILED");
        assertThat(jdbc.queryForMap("SELECT cost_run_version_id,compute_group_id FROM lp_monthly_reprice_result WHERE reprice_no=? AND calc_object_key=?",no,task.getCalcObjectKey()))
            .containsEntry("cost_run_version_id",null).containsEntry("compute_group_id",null);
      }

      assertThat(retryFailed(no).getId()).isEqualTo(batch.getId());
      var retryLeases=new ArrayList<MonthlyRepriceCostingService.Lease>();
      for(var task:repriceMapper.tasks(no)) {
        if(!"SUCCESS".equals(task.getStatus())) {
          assertThat(task.getExecutionNo()).isEqualTo(first.getExecutionNo()+1);
          task=claimTask(no,task.getId());
        }
        retryLeases.add(MonthlyRepriceCostingService.Lease.from(task));
      }
      var output=repricing.executeBatch(retryLeases);
      assertThat(output).hasSize(3); assertThat(targetCount(no)).isEqualTo(3);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_compute_group WHERE id=?",group)).isEqualTo(publication);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_compute_group WHERE batch_id=?",Integer.class,batch.getId())).isEqualTo(1);
      assertThat(versionRow(successful.version().getId())).isEqualTo(successfulRow);
      var results=repriceMapper.results(no);
      assertThat(results).extracting(r->r.getCustomerIdentityValue()).containsExactlyInAnyOrderElementsOf(sources.stream().map(Source::customer).toList());
      for(var result:results) {
        var source=sources.stream().filter(s->s.customer().equals(result.getCustomerIdentityValue())).findFirst().orElseThrow();
        assertThat(result.getCalcStatus()).isEqualTo("SUCCESS");
        assertThat(result.getOaNo()).isEqualTo(source.oa()); assertThat(result.getOaFormItemId()).isEqualTo(source.item());
        assertThat(result.getSourceCostVersionId()).isEqualTo(source.version()); assertThat(result.getComputeGroupId()).isEqualTo(group);
        var actual=savedCosts.read(unit,result.getCostRunVersionId()); var expected=savedCosts.read(unit,source.version());
        assertThat(actual.snapshot().calculation().ordinary()).usingRecursiveComparison().isEqualTo(expected.snapshot().calculation().ordinary());
        assertThat(actual.snapshot().calculation().ordinaryInputs().details()).usingRecursiveComparison().isEqualTo(expected.snapshot().calculation().ordinaryInputs().details());
        assertThat(actual.snapshot().calculation().totalCost()).isEqualByComparingTo(expected.snapshot().calculation().totalCost());
        assertThat(actual.snapshot().calculation().cuMaterialAdjustment()).isEqualByComparingTo(expected.snapshot().calculation().cuMaterialAdjustment());
        assertThat(actual.snapshot().calculation().finalQuoteAmount()).isEqualByComparingTo(expected.snapshot().calculation().finalQuoteAmount());
        assertThat(actual.parts()).extracting(p->Arrays.asList(p.getPartCode(),p.getQty(),p.getUnitPrice(),p.getAmount()))
            .containsExactlyElementsOf(expected.parts().stream().map(p->Arrays.asList(p.getPartCode(),p.getQty(),p.getUnitPrice(),p.getAmount())).toList());
        assertThat(actual.parts()).allSatisfy(p->assertThat(p.getOaNo()).isEqualTo(source.oa()));
        assertThat(versionRow(source.version())).isEqualTo(originalRows.get(source.version()));
        shared.bind(no,result.getCalcObjectKey(),group);
      }
      var links=jdbc.queryForList("SELECT s.source_cost_version_id,s.oa_no,s.oa_form_item_id,r.customer_identity_value,r.cost_run_version_id FROM lp_monthly_reprice_source s "
          +"JOIN lp_monthly_reprice_result r ON r.id=s.object_id AND r.reuse_batch_id=s.batch_id WHERE s.batch_id=?",batch.getId());
      assertThat(links).hasSize(3);
      for(var source:sources) assertThat(links).filteredOn(row->Objects.equals(row.get("source_cost_version_id"),source.version()))
          .singleElement().satisfies(row->assertThat(row).containsEntry("oa_no",source.oa()).containsEntry("oa_form_item_id",source.item()).containsEntry("customer_identity_value",source.customer()));
      var report=checks.verify(repriceMapper.readBatch(no)); assertThat(report.passed()).as(report.problems().toString()).isTrue();
      assertThat(repricing.executeBatch(retryLeases)).extracting(row->row.version().getId()).containsExactlyElementsOf(output.stream().map(row->row.version().getId()).toList());
      assertThatThrownBy(()->repricing.execute(MonthlyRepriceCostingService.Lease.from(first))).hasMessageContaining("执行轮次");
      assertThat(targetCount(no)).isEqualTo(3);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_cost_run_task WHERE id=?",second.getId()))
          .usingRecursiveComparison().isEqualTo(successfulTask);
      assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_result WHERE reprice_no=? AND calc_object_key=?",no,second.getCalcObjectKey()))
          .usingRecursiveComparison().isEqualTo(successfulResult);
      System.out.println("REPRICE_RECOVERY M=3 U=3 G=1 A=1 failures_injected=2 own_success_versions=3 missing=0 amount_mismatch=0");
    } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
  }

  private int targetCount(String no) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE reprice_no=?",Integer.class,no);
  }
  private Map<String,Object> versionRow(long id) {
    return jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",id);
  }
}
