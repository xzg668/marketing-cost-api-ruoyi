package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
class TechnicalDataPendingCurrentSourcesIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired TechnicalDataPendingProductQuery pending;
  @Autowired TechnicalDataPendingState pendingState;
  @Autowired TechnicalDataPendingRefresh pendingRefresh;
  @Autowired TechnicalDataTaskApplicationService tasks;
  @Autowired com.sanhua.marketingcost.service.PriceFixedItemService fixedPrices;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @MockBean TechnicalDataSourceCheckService sources;
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L,"管理员",java.util.Set.of("*:*:*"));
  private String oaNo;
  private long itemId;

  @BeforeEach void fixture() throws Exception {
    when(sources.preview(any(), any(), any())).thenAnswer(call -> sources.check(call.getArgument(0), call.getArgument(1)));
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "admin",null,List.of(new SimpleGrantedAuthority("*:*:*"))));
    oaNo="PENDING-IMPORT-"+UUID.randomUUID();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')",oaNo);
    long formId=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oaNo);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,'测试阀','COMMERCIAL')",formId,oaNo);
    itemId=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,formId);
    var now=LocalDateTime.now();
    var saved=new TechnicalDataSourceCheckResponse(itemId,"2026-11","old-check",now,List.of(
        new TechnicalDataModuleRequirement("PRICE",true,"PRICE_SOURCE_MISSING","当时缺价格",TechnicalDataAvailability.MISSING,"old-price",now),
        new TechnicalDataModuleRequirement("NET_LOSS",true,"NET_LOSS_MISSING","当时缺净损失率",TechnicalDataAvailability.MISSING,"old-rate",now)),null,List.of());
    jdbc.update("""
        INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,
          workspace_status,gap_count,last_checked_at,technical_check_json,technical_check_fingerprint)
        VALUES(?,?,?,'2026-11','COMMERCIAL','WAIT_PRICE',2,NOW(),?,'old-check')
        """,oaNo,itemId,oaNo,json.writeValueAsString(Map.of("check",saved)));
    current(false,false);
  }

  @AfterEach void clearActor() {
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?",oaNo);
    jdbc.update("DELETE FROM lp_quote_costing_workspace WHERE oa_no=?",oaNo);
    jdbc.update("DELETE FROM oa_form_item WHERE oa_form_id IN (SELECT id FROM oa_form WHERE oa_no=?)",oaNo);
    jdbc.update("DELETE FROM oa_form WHERE oa_no=?",oaNo);
    SecurityContextHolder.clearContext();
  }

  @Test void oldUnapprovedSalaryAppearsWithoutReplayingCostingOrWritingSavedCheck() throws Exception {
    var now=LocalDateTime.now();
    var saved=new TechnicalDataSourceCheckResponse(itemId,"2026-11","old-check",now,List.of(
        new TechnicalDataModuleRequirement("SALARY",false,"SALARY_SOURCE_UNCONFIRMED","工时未审批，工资尚未生效",
            TechnicalDataAvailability.UNCONFIRMED,"original-salary-evidence",now)),null,List.of());
    jdbc.update("UPDATE lp_quote_costing_workspace SET technical_check_json=? WHERE oa_form_item_id=?",
        json.writeValueAsString(Map.of("check",saved,"pending",Map.of("check",saved))),itemId);
    var before=protectedRows();
    clearInvocations(sources);
    var page=tasks.workbench(1,20,null,"2026-11",null,oaNo,ADMIN);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.summary().total()).isEqualTo(1);
    assertThat(page.records().getFirst().product().modules()).filteredOn(m -> m.required())
        .singleElement().satisfies(m -> {
          assertThat(m.moduleType()).isEqualTo("SALARY");
          assertThat(m.sourceAvailability()).isEqualTo("MISSING");
          assertThat(m.requirementReason()).contains("补录", "审批");
          assertThat(m.sourceReference()).isEqualTo("original-salary-evidence");
        });
    assertThat(pending.hasPendingItem(oaNo,itemId)).isTrue();
    verifyNoInteractions(sources);
    assertThat(protectedRows()).isEqualTo(before);
  }

  @Test void uncheckedOrFailedSalaryDoesNotBecomeASupplementTask() throws Exception {
    var now=LocalDateTime.now();
    for (var module : List.of(
        new TechnicalDataModuleRequirement("SALARY",false,"SALARY_QUERY_FAILED","查询失败",TechnicalDataAvailability.ERROR,null,now),
        new TechnicalDataModuleRequirement("SALARY",false,"SOURCE_NOT_CHECKED","未检查",TechnicalDataAvailability.UNCONFIRMED,null,null),
        new TechnicalDataModuleRequirement("SALARY",false,"SALARY_SOURCE_UNCONFIRMED","未检查",TechnicalDataAvailability.UNCONFIRMED,null,null))) {
      var saved=new TechnicalDataSourceCheckResponse(itemId,"2026-11","old-check",now,List.of(module),null,List.of());
      jdbc.update("UPDATE lp_quote_costing_workspace SET technical_check_json=? WHERE oa_form_item_id=?",
          json.writeValueAsString(Map.of("check",saved)),itemId);
      assertThat(pending.count("ALL",null,"2026-11",null,oaNo)).isZero();
      assertThat(pending.hasPendingItem(oaNo,itemId)).isFalse();
    }
  }

  @Test void oldUnapprovedSalaryLeavesPendingAfterApprovedPublicSalaryArrives() throws Exception {
    var now=LocalDateTime.now();
    var saved=new TechnicalDataSourceCheckResponse(itemId,"2026-11","old-check",now,List.of(
        new TechnicalDataModuleRequirement("SALARY",false,"SALARY_SOURCE_UNCONFIRMED","工资未审批",
            TechnicalDataAvailability.UNCONFIRMED,"original-salary-evidence",now)),null,List.of());
    jdbc.update("UPDATE lp_quote_costing_workspace SET technical_check_json=? WHERE oa_form_item_id=?",
        json.writeValueAsString(Map.of("check",saved,"pending",Map.of("check",saved))),itemId);
    var before=jdbc.queryForMap("SELECT technical_check_fingerprint,last_checked_at,workspace_status FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",itemId);
    when(sources.check(any(),any())).thenReturn(new TechnicalDataSourceCheckService.Check(List.of(
        new TechnicalDataSourceFact(TechnicalDataModuleType.SALARY,TechnicalDataAvailability.AVAILABLE,
            "SALARY_SOURCE_AVAILABLE","工资审批已生效","approved",now)),Map.of()));
    assertThat(pendingState.changed(new TechnicalDataPendingState.Change(java.util.Set.of("SALARY"),
        "COMMERCIAL",java.util.Set.of(oaNo),java.util.Set.of(itemId)))).isEqualTo(1);
    assertThat(pendingRefresh.runBatch()).isEqualTo(1);
    assertThat(pending.count("ALL",null,"2026-11",null,oaNo)).isZero();
    assertThat(jdbc.queryForMap("SELECT technical_check_fingerprint,last_checked_at,workspace_status FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",itemId)).isEqualTo(before);
    assertThat(json.readTree(jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",String.class,itemId)).path("check"))
        .isEqualTo(json.readTree(json.writeValueAsString(saved)));
  }

  @Test void importingMissingPublicDataRemovesPendingRowWithoutAnySupplementWrites() {
    assertThat(pending.count("ALL",null,"2026-11",null,oaNo)).isEqualTo(1);
    var before=protectedRows();
    current(true,true);
    for(int i=0;i<2;i++) {
      assertThat(pending.count("ALL",null,"2026-11",null,oaNo)).as("已导入资料的产品不再计入待补录").isZero();
      assertThat(pending.page("ALL",null,"2026-11",null,oaNo,0,20)).isEmpty();
      assertThat(pending.hasPendingItem(oaNo,itemId)).isFalse();
      var page=tasks.workbench(1,20,null,"2026-11",null,oaNo,ADMIN);
      assertThat(page.total()).isZero();
      assertThat(page.records()).isEmpty();
      assertThat(page.summary().total()).isZero();
    }
    assertThat(protectedRows()).isEqualTo(before);
  }

  @Test void partiallyImportedDataKeepsOnlyTheRemainingGapInTheList() {
    current(true,false);
    var row=pending.page("ALL",null,"2026-11",null,oaNo,0,20).getFirst();
    assertThat(row.product().modules()).filteredOn(m->m.required())
        .extracting(m->m.moduleType()).containsExactly("NET_LOSS");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_task WHERE oa_form_item_id=?",Integer.class,itemId)).isZero();
  }

  @Test void previouslyCheckedMixedGapsCanRecoverMissingPriceWithoutReplayingCostingOrWritingHistory() throws Exception {
    var time=LocalDateTime.now();
    var saved=new TechnicalDataSourceCheckResponse(itemId,"2026-11","old-check",time,List.of(
        new TechnicalDataModuleRequirement("PRICE",false,"PRICE_BASIS_UNCONFIRMED","仍有结构或来源冲突",
            TechnicalDataAvailability.UNCONFIRMED,"old-price",time)),null,List.of());
    jdbc.update("UPDATE lp_quote_costing_workspace SET technical_check_json=? WHERE oa_form_item_id=?",
        json.writeValueAsString(Map.of("check",saved)),itemId);
    current(false,true);
    var before=protectedRows();
    for(int i=0;i<2;i++) {
      var page=tasks.workbench(1,20,null,"2026-11",null,oaNo,ADMIN);
      assertThat(page.total()).isEqualTo(1);
      assertThat(page.summary().total()).isEqualTo(1);
      assertThat(page.records().getFirst().product().modules()).filteredOn(m->m.required())
          .extracting(m->m.moduleType()).containsExactly("PRICE");
      assertThat(pending.hasPendingItem(oaNo,itemId)).isTrue();
    }
    assertThat(protectedRows()).isEqualTo(before);
    current(true,true);
    assertThat(pending.count("ALL",null,"2026-11",null,oaNo)).isZero();
    assertThat(protectedRows()).isEqualTo(before);
  }

  @Test void uncheckedProductsCannotEnterWorkbenchThroughMixedGapRecovery() throws Exception {
    jdbc.update("UPDATE lp_quote_costing_workspace SET last_checked_at=NULL WHERE oa_form_item_id=?",itemId);
    clearInvocations(sources);
    assertThat(pending.count("ALL",null,"2026-11",null,oaNo)).isZero();
    verifyNoInteractions(sources);
  }

  @Test void resolvedPriceWithRemainingSourceMaintenanceIsNotATechnicalPriceTask() {
    var facts = current(true, true).facts().stream().map(fact -> fact.moduleType() == TechnicalDataModuleType.PRICE
        ? new TechnicalDataSourceFact(TechnicalDataModuleType.PRICE, TechnicalDataAvailability.UNCONFIRMED,
            "PRICE_BASIS_UNCONFIRMED", "仍有结构或来源问题，但无实际缺价", "price", LocalDateTime.now()) : fact).toList();
    when(sources.check(any(),any())).thenReturn(new TechnicalDataSourceCheckService.Check(facts,Map.of()));
    refreshPending();
    var before=protectedRows();
    assertThat(pending.count("ALL",null,"2026-11",null,oaNo)).isZero();
    assertThat(protectedRows()).isEqualTo(before);
  }

  @Test void unavailableSourcesKeepTheOriginalPendingRowWithoutWritingAnything() {
    var before=protectedRows();
    when(sources.check(any(),any())).thenThrow(new IllegalStateException("当前来源暂时不可用"));
    pendingState.changed(new TechnicalDataPendingState.Change(java.util.Set.of("PRICE"),"COMMERCIAL",java.util.Set.of(),java.util.Set.of(itemId)));
    assertThat(pendingRefresh.runBatch()).isZero();
    assertThat(pending.count("ALL",null,"2026-11",null,oaNo)).isEqualTo(1);
    assertThat(pending.page("ALL",null,"2026-11",null,oaNo,0,20).getFirst().product().modules())
        .filteredOn(m->m.required()).hasSize(2);
    assertThat(protectedRows()).isEqualTo(before);
  }

  @Test void sourceTransactionFailureDoesNotRollbackTheWorkbenchQuery() {
    var before=protectedRows();
    when(sources.check(any(),any())).thenAnswer(call -> {
      org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      throw new IllegalStateException("历史来源检查使当前事务回滚");
    });
    var page=tasks.workbench(1,20,null,"2026-11",null,oaNo,ADMIN);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.records().getFirst().product().modules()).filteredOn(m->m.required()).hasSize(2);
    assertThat(protectedRows()).isEqualTo(before);
  }

  @Test void queryScopeCannotReadAnotherBusinessUnitOrWrongMonth() {
    clearInvocations(sources);
    assertThat(pending.count("FINANCE","HOUSEHOLD","2026-11",null,oaNo)).isZero();
    assertThat(pending.count("ALL",null,"2026-10",null,oaNo)).isZero();
    assertThat(pending.count("ASSIGNEE","COMMERCIAL","2026-11",null,oaNo)).isZero();
    verifyNoInteractions(sources);
  }

  @Test void savedChecksCannotUseAnotherProductsCurrentSources() {
    jdbc.update("UPDATE lp_quote_costing_workspace SET technical_check_json=JSON_SET(technical_check_json,'$.check.oaFormItemId',?) WHERE oa_form_item_id=?",itemId+1,itemId);
    assertThatThrownBy(()->pending.page("ALL",null,"2026-11",null,oaNo,0,20))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("产品、月份不一致");
  }

  @Test void filteringResolvedRowsPrecedesPaginationAndStatistics() throws Exception {
    long formId=jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?",Long.class,itemId);
    var saved=json.readTree(jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",String.class,itemId));
    var expected=new java.util.ArrayList<Long>();
    for(int i=0;i<3;i++) {
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,'测试阀','COMMERCIAL')",formId,oaNo+"-"+i);
      long next=jdbc.queryForObject("SELECT MAX(id) FROM oa_form_item WHERE oa_form_id=?",Long.class,formId);
      expected.add(next);
      ((com.fasterxml.jackson.databind.node.ObjectNode)saved).remove("pending");
      ((com.fasterxml.jackson.databind.node.ObjectNode)saved.path("check")).put("oaFormItemId",next);
      jdbc.update("""
          INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,
            workspace_status,gap_count,last_checked_at,technical_check_json,technical_check_fingerprint)
          VALUES(?,?,?,'2026-11','COMMERCIAL','WAIT_PRICE',2,NOW(),?,'old-check')
          """,oaNo,next,oaNo+"-"+i,json.writeValueAsString(saved));
    }
    var ready=facts(true,true);
    var missing=facts(true,false);
    when(sources.check(any(),any())).thenAnswer(call ->
        ((com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext)call.getArgument(0)).oaFormItemId()==itemId?ready:missing);
    refreshPending();
    var page=pending.readPage("ALL",null,"2026-11",null,oaNo,1,1);
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.records()).hasSize(1);
    assertThat(page.records().getFirst().product().oaFormItemId()).isEqualTo(expected.get(1));
    var workbench=tasks.workbench(2,1,null,"2026-11",null,oaNo,ADMIN);
    assertThat(workbench.total()).isEqualTo(3);
    assertThat(workbench.summary().total()).isEqualTo(3);
    assertThat(workbench.records().getFirst().product().oaFormItemId()).isEqualTo(expected.get(1));
  }

  @Test void exactProductScopeDoesNotIncludeAnotherOccurrenceOfTheSameMaterial() throws Exception {
    long form=jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?",Long.class,itemId);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,'同料号另一行','COMMERCIAL')",form,oaNo);
    long other=jdbc.queryForObject("SELECT MAX(id) FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    var saved=json.readTree(jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",String.class,itemId));
    ((com.fasterxml.jackson.databind.node.ObjectNode)saved).remove("pending");
    ((com.fasterxml.jackson.databind.node.ObjectNode)saved.path("check")).put("oaFormItemId",other);
    jdbc.update("INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,workspace_status,gap_count,last_checked_at,technical_check_json) VALUES(?,?,?,'2026-11','COMMERCIAL','WAIT_PRICE',2,NOW(),?)",oaNo,other,oaNo,json.writeValueAsString(saved));
    var focused=tasks.workbench(1,20,null,"2026-11",null,oaNo,itemId,ADMIN);
    assertThat(focused.total()).isEqualTo(1);assertThat(focused.summary().total()).isEqualTo(1);
    assertThat(focused.records()).extracting(r->r.product().oaFormItemId()).containsExactly(itemId);
    assertThat(tasks.workbench(1,20,null,"2026-11",null,oaNo,ADMIN).total()).isEqualTo(2);
    assertThat(tasks.workbench(1,20,null,"2026-10",null,oaNo,itemId,ADMIN).total()).isZero();
    assertThat(tasks.workbench(1,20,null,"2026-11",null,"OTHER-OA",itemId,ADMIN).total()).isZero();
    assertThatThrownBy(()->tasks.workbench(1,20,null,null,null,oaNo,itemId,ADMIN)).hasMessageContaining("定位产品");
  }


  @Test void actualPriceWriteQueuesOnlyItsAffectedProductAndTheListNeverCallsSources() {
    when(sources.check(any(),any())).thenReturn(facts(true,false));
    var authentication=(UsernamePasswordAuthenticationToken)SecurityContextHolder.getContext().getAuthentication();
    authentication.setDetails(Map.of("businessUnitType","COMMERCIAL"));
    clearInvocations(sources);
    fixedPrices.create(priceRequest());
    verifyNoInteractions(sources);
    assertThat(pendingState.waiting(100)).as("Saved=%s; price=%s; bean=%s", jdbc.queryForList("SELECT technical_check_json,business_unit_type FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",itemId), jdbc.queryForList("SELECT business_unit_type,material_code FROM lp_price_fixed_item WHERE material_code=?",oaNo),fixedPrices.getClass()).extracting(TechnicalDataPendingState.Work::itemId).containsExactly(itemId);
    assertThat(pendingRefresh.runBatch()).isEqualTo(1);
    clearInvocations(sources);
    long started=System.nanoTime();
    for(int i=0;i<10;i++) {
      var page=tasks.workbench(1,20,null,"2026-11",null,oaNo,ADMIN);
      assertThat(page.total()).isEqualTo(1);
      assertThat(page.records().getFirst().product().modules()).filteredOn(row -> row.required())
          .extracting(row -> row.moduleType()).containsExactly("NET_LOSS");
    }
    System.out.println("PENDING_READ_BENCH tenActualWorkbenchReadsMs="+(System.nanoTime()-started)/1_000_000);
    verifyNoInteractions(sources);
  }

  @Test void rolledBackSourceWriteDoesNotLeaveARefreshOrPriceBehind() {
    var before=jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",String.class,itemId);
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
      fixedPrices.create(priceRequest());status.setRollbackOnly();
    });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_fixed_item WHERE material_code=?",Integer.class,oaNo)).isZero();
    assertThat(jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",String.class,itemId)).isEqualTo(before);
    assertThat(pendingState.waiting(100)).isEmpty();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ITEM_DELETED", "FORM_DELETED", "MISSING_ITEM", "WRONG_UNIT"})
  void unavailableProductsAreNotInitializedOrMarkedBySourceChanges(String kind) {
    long form = jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?", Long.class, itemId);
    jdbc.update("UPDATE lp_quote_costing_workspace SET technical_check_json=JSON_REMOVE(technical_check_json,'$.pending') WHERE oa_form_item_id=?", itemId);
    makeUnavailable(kind, form);
    try {
      var before = jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_no=?", String.class, oaNo);
      clearInvocations(sources);
      assertThat(pendingState.initializeLegacy()).isZero();
      assertThat(pendingState.changed(new TechnicalDataPendingState.Change(java.util.Set.of("PRICE"),
          "COMMERCIAL", java.util.Set.of(), java.util.Set.of(itemId)))).isZero();
      assertThat(pendingState.waiting(16)).isEmpty();
      assertThat(pendingRefresh.runBatch()).isZero();
      verifyNoInteractions(sources);
      assertThat(jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_no=?", String.class, oaNo)).isEqualTo(before);
    } finally { restoreProduct(form); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ITEM_DELETED", "FORM_DELETED", "MISSING_ITEM", "WRONG_UNIT"})
  void queuedProductsBecomingUnavailableStopRefreshingAndRetainTheirSavedCheck(String kind) {
    long form = jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?", Long.class, itemId);
    pendingState.changed(new TechnicalDataPendingState.Change(java.util.Set.of("PRICE"),
        "COMMERCIAL", java.util.Set.of(), java.util.Set.of(itemId)));
    assertThat(pendingState.waiting(16)).hasSize(1);
    makeUnavailable(kind, form);
    try {
      var before = jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_no=?", String.class, oaNo);
      clearInvocations(sources);
      for (int pass = 0; pass < 3; pass++) {
        assertThat(pendingState.waiting(16)).isEmpty();
        assertThat(pendingRefresh.runBatch()).isZero();
      }
      verifyNoInteractions(sources);
      assertThat(jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_no=?", String.class, oaNo)).isEqualTo(before);
    } finally { restoreProduct(form); }
    // If the product is restored, its existing change token is still usable.
    assertThat(pendingState.waiting(16)).singleElement()
        .extracting(TechnicalDataPendingState.Work::itemId).isEqualTo(itemId);
    assertThat(pendingRefresh.runBatch()).isEqualTo(1);
  }

  private void makeUnavailable(String kind, long form) {
    switch (kind) {
      case "ITEM_DELETED" -> jdbc.update("UPDATE oa_form_item SET deleted=1 WHERE id=?", itemId);
      case "FORM_DELETED" -> jdbc.update("UPDATE oa_form SET deleted=1 WHERE id=?", form);
      case "MISSING_ITEM" -> jdbc.update("UPDATE lp_quote_costing_workspace SET oa_form_item_id=? WHERE oa_form_item_id=?", itemId + 10000000, itemId);
      case "WRONG_UNIT" -> jdbc.update("UPDATE oa_form SET business_unit_type='HOUSEHOLD' WHERE id=?", form);
      default -> throw new IllegalArgumentException(kind);
    }
  }

  private void restoreProduct(long form) {
    jdbc.update("UPDATE oa_form_item SET deleted=0 WHERE id=?", itemId);
    jdbc.update("UPDATE oa_form SET deleted=0,business_unit_type='COMMERCIAL' WHERE id=?", form);
    jdbc.update("UPDATE lp_quote_costing_workspace SET oa_form_item_id=? WHERE oa_no=?", itemId, oaNo);
  }

  @Test void thousandPendingProductsUseSavedStatesAndOnlyReturnTheRequestedPage() throws Exception {
    long form=jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?",Long.class,itemId);
    var values=new java.util.ArrayList<Object[]>();
    for(int i=0;i<1000;i++) values.add(new Object[]{form,oaNo+"-"+i,"COMMERCIAL"});
    jdbc.batchUpdate("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,?)",values);
    String original=jdbc.queryForObject("SELECT technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",String.class,itemId);
    jdbc.update("""
      INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,
        workspace_status,gap_count,last_checked_at,technical_check_json,technical_check_fingerprint)
      SELECT ?,i.id,i.material_no,'2026-11','COMMERCIAL','WAIT_PRICE',2,NOW(),
        JSON_SET(CAST(? AS JSON),'$.check.oaFormItemId',i.id,'$.pending.check.oaFormItemId',i.id),'old-check'
      FROM oa_form_item i WHERE i.oa_form_id=? AND i.id<>?
      """,oaNo,original,form,itemId);
    clearInvocations(sources);
    var times=new java.util.ArrayList<Long>();
    for(int pageNo:List.of(1,2,20,40)) {
      long start=System.nanoTime();
      var page=tasks.workbench(pageNo,20,null,"2026-11",null,oaNo,ADMIN);
      times.add((System.nanoTime()-start)/1_000_000);
      assertThat(page.total()).isEqualTo(1001);assertThat(page.summary().total()).isEqualTo(1001);
      assertThat(page.records()).hasSize(20);
    }
    verifyNoInteractions(sources);
    System.out.println("PENDING_READ_BENCH savedProducts=1001 pageSize=20 pageMilliseconds="+times+" sourceChecks=0");
  }

  private com.sanhua.marketingcost.dto.PriceFixedItemUpdateRequest priceRequest() {
    var request=new com.sanhua.marketingcost.dto.PriceFixedItemUpdateRequest();
    request.setMaterialCode(oaNo);request.setOrgCode("210");request.setSupplierCode("PENDING-SUPPLIER");
    request.setSourceType("PURCHASE_FIXED");request.setFixedPrice(java.math.BigDecimal.TEN);
    request.setEffectiveFrom(java.time.LocalDate.of(2026,11,1));return request;
  }

  private TechnicalDataSourceCheckService.Check current(boolean priceAvailable,boolean rateAvailable) {
    var checked = facts(priceAvailable,rateAvailable);
    when(sources.check(any(),any())).thenReturn(checked);
    refreshPending();
    return checked;
  }

  private TechnicalDataSourceCheckService.Check facts(boolean priceAvailable,boolean rateAvailable) {
    return new TechnicalDataSourceCheckService.Check(
        TechnicalDataModuleType.orderedCodes().stream().map(code->{
          boolean missing="PRICE".equals(code)&&!priceAvailable||"NET_LOSS".equals(code)&&!rateAvailable;
          return new TechnicalDataSourceFact(TechnicalDataModuleType.valueOf(code),
              missing?TechnicalDataAvailability.MISSING:TechnicalDataAvailability.AVAILABLE,
              missing?"SOURCE_MISSING":"SOURCE_AVAILABLE",missing?"仍缺资料":"公共资料已导入",code,LocalDateTime.now());
        }).toList(),Map.of());
  }

  private void refreshPending() {
    pendingState.initializeLegacy();
    int marked=pendingState.changed(new TechnicalDataPendingState.Change(java.util.Set.of("PRICE","NET_LOSS"),"COMMERCIAL",java.util.Set.of(),java.util.Set.of(itemId)));
    assertThat(pendingState.waiting(100)).as("marked="+marked+" "+jdbc.queryForList("SELECT technical_check_fingerprint,technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",itemId)).isNotEmpty();
    pendingRefresh.runBatch();
  }

  private Map<String,Object> protectedRows() {
    return Map.of("workspace",jdbc.queryForList("SELECT id,workspace_status,current_bom_build_batch_id,technical_check_fingerprint,JSON_REMOVE(technical_check_json,'$.pending') technical_check_json FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",itemId),
        "tasks",jdbc.queryForList("SELECT * FROM lp_quote_tech_task WHERE oa_form_item_id=?",itemId),
        "products",jdbc.queryForList("SELECT * FROM lp_quote_tech_product WHERE oa_form_item_id=?",itemId),
        "modules",jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_module",Long.class),
        "versions",jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version",Long.class),
        "priceBatches",jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_batch",Long.class),
        "monthlyPrices",jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class));
  }
}
