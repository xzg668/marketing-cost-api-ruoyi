package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceCostingService;
import java.time.*;
import java.math.BigDecimal;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.service.impl.OriginalLinkedRuleSnapshot;
import com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Change real applicable sources after publication, then retry only unfinished customers. */
@Tag("integration")
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class MonthlyRepriceChangedInputIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired MonthlyCostVersionService savedCosts;
  @Autowired com.sanhua.marketingcost.mapper.PriceLinkedItemMapper rules;
  @Autowired OriginalLinkedRuleSnapshot snapshots;
  @Autowired com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService effective;
  @Autowired com.sanhua.marketingcost.service.financefactor.FinanceMonthlyPriceRefreshService refresh;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired com.sanhua.marketingcost.service.technicaldata.QuoteTechnicalDataPersistenceService technicalPersistence;
  @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalDataNetLossApplicationService netLoss;
  @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalDataAdminDirectSave directSave;
  @Autowired com.sanhua.marketingcost.mapper.QuoteTechProductMapper technicalProducts;
  @Autowired com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService technicalInputs;
  @org.springframework.boot.test.mock.mockito.SpyBean com.sanhua.marketingcost.service.reprice.MonthlyRepriceSharedNumbers sharedNumbers;
  record Source(String product,String customer,String oa,long form,long item,long version) {}

  @Test void changedApplicableInputsCreateNewGroupsWithoutOverwritingPublishedResults() {
    String change=System.getProperty("reprice.test.change","fee");assertThat(change).isIn("fee","price","technical","bom","price-race");
    boolean financial=change.startsWith("price");
    int products=financial?1:2,customers=financial?3:2,objects=products*customers;
    int extraGroups=change.equals("bom")?0:1;
    unit="COMMERCIAL";priceOrg="210";materialOrg="COMMERCIAL";
    var target=YearMonth.parse(com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth());
    var old=target.minusMonths(1);var date=old.atDay(15).atTime(12,0);
    repriceProperties.setGoLiveMonths(Map.of(unit,old.toString()));
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("change-acceptance",null,
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    auth.setDetails(Map.of("businessUnitType",unit,"userId",1L));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES(?,?,'标准品',0)",unit,date.getYear());
      var sources=new ArrayList<Source>();
      String prefix="CHANGE-"+UUID.randomUUID().toString().substring(0,8);
      int bomLines=Integer.getInteger("reprice.test.bom-lines",1);assertThat(bomLines).isBetween(1,100);
      if(financial) {publication(unit,old.toString(),priceOrg,1);publication(unit,target.toString(),priceOrg,1);}
      for(int productIndex=0;productIndex<products;productIndex++) {
        String product=prefix+"-P"+productIndex,material=product+"-PART";long template=0;
        for(int customerIndex=0;customerIndex<customers;customerIndex++) {
          String oa=product+"-OA-"+customerIndex,customer="CUSTOMER-"+customerIndex;
          if(customerIndex==0) jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer) VALUES(?,?,80000,?)",oa,unit,customer);
          else jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,customer,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category) "
              +"SELECT ?,business_unit_type,copper_price,?,process_code,accounting_period_month,source_business_division,applicant_dept,expense_product_category FROM oa_form WHERE id=?",oa,customer,template);
          long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oa);
          jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,?)",form,product,product,unit);
          long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
          if(customerIndex==0) {
            template=form;ordinarySources(product,material,form,date);
            if(financial) {
              linkedRule(material,old.toString());
              new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->effective.generateScope(unit,priceOrg));
            } else comparisonFixedPrice(material,"2",date);
            // Use a finished product's own U9 packaging relation in both quotation months.
            jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
            jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,?,?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",materialOrg,product);
            jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
            for(int line=1;line<bomLines;line++) {
              String part=material+"-"+line;comparisonAdditionalPart(product,material,part,"1",date);comparisonFixedPrice(part,"2",date);
            }
          }
          buildOriginalFixture(form,item,product,old.toString(),date,material,Map.of());
          var cost=pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(oa,item,null,"change-acceptance",true));
          assertThat(cost.getPipelineStatus()).as(cost.getMessage()).isEqualTo("SUCCESS");
          sources.add(new Source(product,customer,oa,form,item,cost.getCostVersionId()));
        }
      }
      var historical=new LinkedHashMap<Long,Map<String,Object>>();
      for(var source:sources) historical.put(source.version(),versionRow(source.version()));
      if(change.equals("bom")) {
        // U9 changed after the old month's adoption, before the target month's first use.
        assertThat(jdbc.update("UPDATE lp_bom_raw_hierarchy SET qty_per_parent=4,qty_per_top=4 WHERE top_product_code=? AND material_code=?",prefix+"-P0",prefix+"-P0-PART")).isEqualTo(1);
      }
      var batch=contracts.enqueue(unit,target);String no=batch.getRepriceNo();
      assertThat(batch.getSourceCount()).isEqualTo(objects);assertThat(batch.getTotalCount()).isEqualTo(objects);
      var leases=repriceMapper.tasks(no).stream().map(t->claimTask(no,t.getId())).toList();
      jdbc.execute("CREATE TRIGGER reprice_change_bind_failure BEFORE UPDATE ON lp_monthly_reprice_result FOR EACH ROW BEGIN "
          +"IF NEW.customer_identity_value<>'CUSTOMER-0' AND NEW.compute_group_id IS NOT NULL THEN "
          +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CHANGE_BIND_FAILURE'; END IF; END");
      try {
        for(var task:leases) {
          var source=source(sources,task.getOaFormItemId());
          if(source.customer().equals("CUSTOMER-0")) repricing.execute(MonthlyRepriceCostingService.Lease.from(task));
          else {
            assertThatThrownBy(()->repricing.execute(MonthlyRepriceCostingService.Lease.from(task))).hasStackTraceContaining("CHANGE_BIND_FAILURE");
            contracts.fail(no,task.getId(),task.getExecutionNo(),task.getWorkerId(),"controlled binding failure");
          }
        }
      } finally {jdbc.execute("DROP TRIGGER reprice_change_bind_failure");}
      var groups=jdbc.queryForList("SELECT * FROM lp_monthly_reprice_compute_group WHERE batch_id=? ORDER BY id",batch.getId());
      assertThat(groups).hasSize(products).allSatisfy(row->assertThat(row).containsEntry("status","SUCCESS").containsEntry("attempt_count",1));
      var oldResults=new LinkedHashMap<Long,Map<String,Object>>();
      for(var result:repriceMapper.results(no)) if("SUCCESS".equals(result.getCalcStatus()))
        oldResults.put(result.getCostRunVersionId(),versionRow(result.getCostRunVersionId()));
      assertThat(oldResults).hasSize(products);

      String changed=prefix+"-P0";
      if(change.equals("fee")) {
      assertThat(jdbc.update("UPDATE lp_quality_loss_rate SET loss_rate=0.02 WHERE bare_product_code=? AND rate_year=? AND business_unit_type=?",changed+"-BARE",date.getYear(),unit)).isEqualTo(1);
      } else if(change.equals("technical")) {
        assertThat(jdbc.update("DELETE FROM lp_quality_loss_rate WHERE bare_product_code=? AND rate_year=? AND business_unit_type=?",changed+"-BARE",date.getYear(),unit)).isEqualTo(1);
        publishNetLoss(sources.stream().filter(row->row.product().equals(changed) && row.customer().equals("CUSTOMER-1")).findFirst().orElseThrow(),target.toString());
      } else if(change.equals("bom")) {
        // A later raw import cannot replace the already adopted target-month BOM during retry.
        assertThat(jdbc.update("UPDATE lp_bom_raw_hierarchy SET qty_per_parent=6,qty_per_top=6 WHERE top_product_code=? AND material_code=?",changed,changed+"-PART")).isEqualTo(1);
      } else if(change.equals("price-race")) {
        retryFailed(no);
        var racing=repriceMapper.tasks(no).stream().filter(task->task.getOaNo().endsWith("-OA-1")).findFirst().orElseThrow();
        racing=claimTask(no,racing.getId());
        raceCorrectionAndBinding(MonthlyRepriceCostingService.Lease.from(racing),target.toString(),auth);
        var saved=repriceMapper.results(no).stream().filter(row->row.getCustomerIdentityValue().equals("CUSTOMER-1")).findFirst().orElseThrow();
        oldResults.put(saved.getCostRunVersionId(),versionRow(saved.getCostRunVersionId()));
        var before=repriceMapper.results(no).stream().filter(row->row.getCustomerIdentityValue().equals("CUSTOMER-0")).findFirst().orElseThrow();
        var original=savedCosts.read(unit,before.getCostRunVersionId());
        var raced=savedCosts.read(unit,saved.getCostRunVersionId());
        assertThat(raced.snapshot().calculation().ordinary()).usingRecursiveComparison().isEqualTo(original.snapshot().calculation().ordinary());
        assertThat(raced.snapshot().calculation().ordinaryInputs().details()).usingRecursiveComparison().isEqualTo(original.snapshot().calculation().ordinaryInputs().details());
        assertThat(raced.snapshot().calculation().totalCost()).isEqualByComparingTo(original.snapshot().calculation().totalCost());
        assertThat(raced.snapshot().calculation().cuMaterialAdjustment()).isEqualByComparingTo(original.snapshot().calculation().cuMaterialAdjustment());
        assertThat(raced.snapshot().calculation().finalQuoteAmount()).isEqualByComparingTo(original.snapshot().calculation().finalQuoteAmount());
        assertThat(raced.parts()).extracting(part->Arrays.asList(part.getPartCode(),part.getQty(),part.getUnitPrice(),part.getAmount()))
            .containsExactlyInAnyOrderElementsOf(original.parts().stream().map(part->Arrays.asList(part.getPartCode(),part.getQty(),part.getUnitPrice(),part.getAmount())).toList());
      } else correctFinance(target.toString());
      if(!change.equals("price-race")) retryFailed(no);
      var current=new ArrayList<MonthlyRepriceCostingService.Lease>();
      for(var task:repriceMapper.tasks(no)) {
        if(!"SUCCESS".equals(task.getStatus())) task=claimTask(no,task.getId());
        current.add(MonthlyRepriceCostingService.Lease.from(task));
      }
      repricing.executeBatch(current);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE reprice_no=?",Integer.class,no)).isEqualTo(objects);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_compute_group WHERE batch_id=?",Integer.class,batch.getId())).isEqualTo(products+extraGroups);
      for(var group:groups) assertThat(jdbc.queryForMap("SELECT * FROM lp_monthly_reprice_compute_group WHERE id=?",group.get("id")))
          .usingRecursiveComparison().isEqualTo(group);
      oldResults.forEach((id,row)->assertThat(versionRow(id)).usingRecursiveComparison().isEqualTo(row));
      historical.forEach((id,row)->assertThat(versionRow(id)).usingRecursiveComparison().isEqualTo(row));
      var results=repriceMapper.results(no);
      assertThat(results).hasSize(objects).allSatisfy(result->assertThat(result.getCalcStatus()).isEqualTo("SUCCESS"));
      assertThat(results).extracting(r->List.of(r.getProductCode(),r.getCustomerIdentityValue()))
          .containsExactlyInAnyOrderElementsOf(sources.stream().map(s->List.of(s.product(),s.customer())).toList());
      for(var result:results) {
        var source=source(sources,result.getOaFormItemId());
        assertThat(result.getSourceCostVersionId()).isEqualTo(source.version());assertThat(result.getOaNo()).isEqualTo(source.oa());
        var oldGroup=groups.stream().filter(row->row.get("product_code").equals(source.product())).findFirst().orElseThrow();
        if(!change.equals("bom") && source.product().equals(changed) && afterChange(change,source)) {
          assertThat(result.getComputeGroupId()).isNotEqualTo(oldGroup.get("id"));
          assertThat(result.getTotalCost()).isNotEqualByComparingTo(savedCosts.read(unit,source.version()).version().getTotalCost());
        } else {
          assertThat(result.getComputeGroupId()).isEqualTo(oldGroup.get("id"));
          if(change.equals("bom") && source.product().equals(changed)) {
            assertThat(result.getTotalCost()).isNotEqualByComparingTo(savedCosts.read(unit,source.version()).version().getTotalCost());
            assertThat(savedCosts.read(unit,result.getCostRunVersionId()).parts()).anySatisfy(part->{
              assertThat(part.getPartCode()).isEqualTo(changed+"-PART");assertThat(part.getQty()).isEqualByComparingTo("4");
            });
            assertThat(savedCosts.read(unit,source.version()).parts()).anySatisfy(part->{
              assertThat(part.getPartCode()).isEqualTo(changed+"-PART");assertThat(part.getQty()).isEqualByComparingTo("2.5");
            });
          } else assertThat(result.getTotalCost()).isEqualByComparingTo(savedCosts.read(unit,source.version()).version().getTotalCost());
        }
      }
      var links=jdbc.queryForList("SELECT source_cost_version_id,oa_no,oa_form_item_id FROM lp_monthly_reprice_source WHERE batch_id=?",batch.getId());
      assertThat(links).hasSize(objects);
      for(var source:sources) assertThat(links).anySatisfy(row->assertThat(row).containsEntry("source_cost_version_id",source.version()).containsEntry("oa_no",source.oa()).containsEntry("oa_form_item_id",source.item()));
      var report=checks.verify(repriceMapper.readBatch(no));assertThat(report.passed()).as(report.problems().toString()).isTrue();
      var checked=contracts.recordCheck(report);workflow.confirm(no,checked.getResultRevision());
      // The ordinary quotation path now uses the target month and never consults shared numbers.
      for(var source:sources) if(afterChange(change,source)) {
        var ordinary=pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(source.oa(),source.item(),null,"change-oracle",true));
        assertThat(ordinary.getPipelineStatus()).as(ordinary.getMessage()).isEqualTo("SUCCESS");
        var expected=savedCosts.read(unit,ordinary.getCostVersionId());
        var result=results.stream().filter(row->row.getOaFormItemId().equals(source.item())).findFirst().orElseThrow();
        var actual=savedCosts.read(unit,result.getCostRunVersionId());
        assertThat(expected.version().getPricingMonth()).isEqualTo(target.toString());
        assertThat(actual.snapshot().calculation().ordinary()).usingRecursiveComparison().isEqualTo(expected.snapshot().calculation().ordinary());
        assertThat(actual.snapshot().calculation().ordinaryInputs().details()).usingRecursiveComparison().isEqualTo(expected.snapshot().calculation().ordinaryInputs().details());
        assertThat(actual.snapshot().calculation().totalCost()).isEqualByComparingTo(expected.snapshot().calculation().totalCost());
        assertThat(actual.snapshot().calculation().cuMaterialAdjustment()).isEqualByComparingTo(expected.snapshot().calculation().cuMaterialAdjustment());
        assertThat(actual.snapshot().calculation().finalQuoteAmount()).isEqualByComparingTo(expected.snapshot().calculation().finalQuoteAmount());
        assertThat(actual.parts()).extracting(p->Arrays.asList(p.getPartCode(),p.getQty(),p.getUnitPrice(),p.getAmount()))
            .containsExactlyInAnyOrderElementsOf(expected.parts().stream().map(p->Arrays.asList(p.getPartCode(),p.getQty(),p.getUnitPrice(),p.getAmount())).toList());
      }
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_monthly_reprice_compute_group WHERE batch_id=?",Integer.class,batch.getId())).isEqualTo(products+extraGroups);
      System.out.println("REPRICE_CHANGE type="+change+" M="+objects+" U="+objects+" old_groups="+products+" new_groups="+extraGroups+" missing=0 amount_mismatch=0");
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }

  private static boolean afterChange(String change,Source source) {
    return !source.customer().equals("CUSTOMER-0") && !(change.equals("price-race") && source.customer().equals("CUSTOMER-1"));
  }

  private void correctFinance(String month) {
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
      long revised=publication(unit,month,priceOrg,2);
      jdbc.update("UPDATE lp_factor_monthly_price SET revision_no=2,price=110 WHERE source_upload_batch_id=?",revised);
      effective.generateScope(unit,priceOrg);
      assertThat(refresh.refresh(new com.sanhua.marketingcost.dto.FinanceFactorImportContext(unit,priceOrg,month,null,null),revised)).isPositive();
    });
  }

  /** Real transactions and SQL locks, paused only at the test's final customer binding boundary. */
  private void raceCorrectionAndBinding(MonthlyRepriceCostingService.Lease lease,String month,
      org.springframework.security.core.Authentication auth) {
    var binding=new java.util.concurrent.CountDownLatch(1);
    var release=new java.util.concurrent.CountDownLatch(1);
    var consumerConnection=new java.util.concurrent.atomic.AtomicLong();
    var correctionConnection=new java.util.concurrent.atomic.AtomicLong();
    org.mockito.Mockito.doAnswer(call->{
      consumerConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
      binding.countDown();
      if(!release.await(20,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("binding latch timed out");
      return call.callRealMethod();
    }).when(sharedNumbers).bind(org.mockito.ArgumentMatchers.eq(lease.repriceNo()),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong());
    try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var consumer=workers.submit(()->{
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {return repricing.execute(lease);}
        finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
      });
      try {
        assertThat(binding.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var correction=workers.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
          correctionConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()",Long.class));
          correctFinance(month);
        }));
        boolean blocked=false;
        long limit=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(!correction.isDone() && System.nanoTime()<limit) {
          blocked=Boolean.TRUE.equals(jdbc.queryForObject("""
              SELECT EXISTS(SELECT 1 FROM performance_schema.data_lock_waits w
                JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID
                JOIN performance_schema.threads b ON b.THREAD_ID=w.BLOCKING_THREAD_ID
                WHERE r.PROCESSLIST_ID=? AND b.PROCESSLIST_ID=?)
              """,Boolean.class,correctionConnection.get(),consumerConnection.get()));
          if(blocked)break;
          Thread.sleep(25);
        }
        assertThat(blocked || correction.isDone()).as("observe real SQL blocking or completed correction before releasing binding").isTrue();
        if(correction.isDone())correction.get(1,java.util.concurrent.TimeUnit.SECONDS);
        System.out.println("REPRICE_CHANGE_RACE correctionBlockedByConsumer="+blocked+" consumerConnection="+consumerConnection.get()+" correctionConnection="+correctionConnection.get());
        release.countDown();
        consumer.get(20,java.util.concurrent.TimeUnit.SECONDS);
        correction.get(20,java.util.concurrent.TimeUnit.SECONDS);
      } finally {release.countDown();}
    } catch(Exception failure) {throw new AssertionError("correction/customer-binding race",failure);}
    finally {release.countDown();org.mockito.Mockito.reset(sharedNumbers);}
  }

  private void publishNetLoss(Source source,String month) {
    var task=new com.sanhua.marketingcost.entity.QuoteTechTask();
    task.setTaskNo("CHANGE-TECH-"+UUID.randomUUID());task.setOaFormId(source.form());task.setOaFormItemId(source.item());
    task.setOaNo(source.oa());task.setAccountingMonth(month);task.setBusinessUnitType(unit);task.setApplicableOrgCode(priceOrg);task.setTaskStatus("UNASSIGNED");
    // The persistence creation API accepts assigned tasks; establish the same unassigned
    // local-admin fixture used by the technical-data workflow integration tests.
    task.setAssigneeUserId(1L);task.setAssigneeName("change-fixture");
    long taskId=technicalPersistence.createTask(task).getId();
    jdbc.update("UPDATE lp_quote_tech_task SET assignee_user_id=NULL,assignee_name=NULL WHERE id=?",taskId);
    var product=new com.sanhua.marketingcost.entity.QuoteTechProduct();product.setTaskId(taskId);product.setOaFormItemId(source.item());
    product.setQuoteNo(source.oa());product.setAccountingMonth(month);product.setContentSchemaVersion(2);product.setMaterialNo(source.product());
    product.setSourceFingerprint("a".repeat(64));product.setSourceSnapshotJson("{}");
    long productId=technicalPersistence.createProduct(product).getId();
    for(String type:com.sanhua.marketingcost.service.technicaldata.TechnicalDataModuleType.orderedCodes()) {
      boolean required=type.equals("NET_LOSS");
      var module=new com.sanhua.marketingcost.entity.QuoteTechModule();module.setProductId(productId);module.setModuleType(type);
      module.setRequiredFlag(required?1:0);module.setModuleStatus(required?"PENDING":"NOT_REQUIRED");
      module.setSourceAvailability(required?"MISSING":"AVAILABLE");module.setSourceCheckedAt(LocalDateTime.now());
      module.setRequirementReasonCode("CONTROLLED_CHANGE");module.setRequirementReason("isolated source correction");module.setEntryMode(required?"MANUAL":"NONE");
      technicalPersistence.createModule(module);
    }
    var actor=new com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor(1L,"change-fixture",Set.of("*:*:*"));
    var request=new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataNetLossSaveRequest();
    request.setExpectedVersion(technicalProducts.selectById(productId).getRowVersion());request.setEntryMode("MANUAL");request.setPercent("2");
    var draft=netLoss.save(productId,request,actor);assertThat(draft.issues()).isEmpty();
    assertThatThrownBy(()->technicalInputs.resolve(source.item(),month)).isInstanceOf(com.sanhua.marketingcost.service.EffectiveTechnicalDataException.class);
    var published=directSave.save(taskId,new com.sanhua.marketingcost.service.technicaldata.TechnicalDataAdminDirectSave.Request(technicalProducts.selectById(productId).getRowVersion()),actor);
    assertThat(published.effectiveVersionId()).as(String.valueOf(published.validation())).isNotNull();
    var actual=technicalInputs.resolve(source.item(),month);assertThat(actual.netLossRate()).isEqualByComparingTo("0.02");
    assertThat(actual.sourceVersionId("NET_LOSS")).isEqualTo(published.effectiveVersionId());
  }
  private void linkedRule(String material,String month) {
    var key=new MaterialPriceRuleKey(unit,priceOrg,material,"CHANGE-S1","CHANGE-S1",null,"件");
    var factors=new LinkedHashMap<String,OriginalLinkedWorkbookParser.FactorInput>();
    factors.put("cu",new OriginalLinkedWorkbookParser.FactorInput(PriceFactorContext.COPPER,new Address("财务基价","D3"),FactorPriceUnit.CNY_KG,"CNY/KG",PriceFactorContext.COPPER,"SAME_CANONICAL_FACTOR"));
    String expression="[cu]+5";
    var original=new OriginalLinkedWorkbookParser.Rule(key,"change linked material","原稿",8,"H8",expression,expression,"0.000000",false,new CompiledFormula(expression,List.of(),Map.of()),factors,BigDecimal.ZERO);
    var row=new PriceLinkedItem();row.setRuleIdentityKey(key.identityKey());row.setRuleRevision(1);row.setRuleStatus("PUBLISHED");
    row.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);row.setSourceKind("PUBLIC");row.setBusinessUnitType(unit);row.setOrgCode(priceOrg);
    row.setPricingMonth(month);row.setMaterialCode(material);row.setMaterialName(material);row.setUnit("件");row.setSupplierCode("CHANGE-S1");row.setSupplierName("CHANGE-S1");
    row.setTaxIncluded(0);row.setFormulaExpr(expression);row.setSourceInputSnapshotJson(snapshots.write(List.of(original),"controlled-change-version"));
    row.setSourceSheetName("原稿");row.setSourceRowNumber(8);row.setSourceFormulaCellRef("H8");row.setSourceFormulaExpr(expression);
    row.setEffectiveFrom(YearMonth.parse(month).atDay(1));row.setEffectiveTo(LocalDate.of(2099,1,1));row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(LocalDateTime.now());
    row.setDeleted(0);row.setTechnicalRevision(0);rules.insert(row);
  }
  private long publication(String bu,String month,String org,int revision) {
    String batch=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,?,'finance-change.xlsx',?,?,'change-fixture','PUBLISHED',?,?,'DEFAULT',NOW())",batch,month,bu,"a".repeat(64),"b".repeat(64),org,revision);
    long id=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,batch);
    String seq=UUID.randomUUID().toString(),factor=PriceFactorContext.COPPER;
    jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES(?,?,?,?, 'AVG',?)",bu,seq,factor,factor,factor);
    long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,seq);
    jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,100,1,?,'PUBLISHED',?,'DEFAULT','GENERAL',?,'CNY/KG',NOW())",identity,month,id,org,revision);
    long price=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=?",Long.class,identity);
    jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,source_number_format,conversion_basis) VALUES(?,'财务基价',3,?,?,4,'D3','DEFAULT','GENERAL','100','CNY/KG','0.000000','IDENTITY')",id,identity,price);
    return id;
  }
  private Source source(List<Source> sources,long item) {return sources.stream().filter(s->s.item()==item).findFirst().orElseThrow();}
  private Map<String,Object> versionRow(long id) {return jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",id);}
}
