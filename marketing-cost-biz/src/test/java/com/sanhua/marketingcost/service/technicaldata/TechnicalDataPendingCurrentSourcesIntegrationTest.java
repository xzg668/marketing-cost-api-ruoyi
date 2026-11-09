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
  @Autowired TechnicalDataTaskApplicationService tasks;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @MockBean TechnicalDataSourceCheckService sources;
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L,"管理员",java.util.Set.of("*:*:*"));
  private String oaNo;
  private long itemId;

  @BeforeEach void fixture() throws Exception {
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

  @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }

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
    var before=protectedRows();
    assertThat(pending.count("ALL",null,"2026-11",null,oaNo)).isZero();
    assertThat(protectedRows()).isEqualTo(before);
  }

  @Test void unavailableSourcesKeepTheOriginalPendingRowWithoutWritingAnything() {
    var before=protectedRows();
    when(sources.check(any(),any())).thenThrow(new IllegalStateException("当前来源暂时不可用"));
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
      ((com.fasterxml.jackson.databind.node.ObjectNode)saved.path("check")).put("oaFormItemId",next);
      jdbc.update("""
          INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,
            workspace_status,gap_count,last_checked_at,technical_check_json,technical_check_fingerprint)
          VALUES(?,?,?,'2026-11','COMMERCIAL','WAIT_PRICE',2,NOW(),?,'old-check')
          """,oaNo,next,oaNo+"-"+i,json.writeValueAsString(saved));
    }
    var ready=current(true,true);
    var missing=current(true,false);
    when(sources.check(any(),any())).thenAnswer(call ->
        ((com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext)call.getArgument(0)).oaFormItemId()==itemId?ready:missing);
    var page=pending.readPage("ALL",null,"2026-11",null,oaNo,1,1);
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.records()).hasSize(1);
    assertThat(page.records().getFirst().product().oaFormItemId()).isEqualTo(expected.get(1));
    var workbench=tasks.workbench(2,1,null,"2026-11",null,oaNo,ADMIN);
    assertThat(workbench.total()).isEqualTo(3);
    assertThat(workbench.summary().total()).isEqualTo(3);
    assertThat(workbench.records().getFirst().product().oaFormItemId()).isEqualTo(expected.get(1));
  }

  private TechnicalDataSourceCheckService.Check current(boolean priceAvailable,boolean rateAvailable) {
    var checked = new TechnicalDataSourceCheckService.Check(
        TechnicalDataModuleType.orderedCodes().stream().map(code->{
          boolean missing="PRICE".equals(code)&&!priceAvailable||"NET_LOSS".equals(code)&&!rateAvailable;
          return new TechnicalDataSourceFact(TechnicalDataModuleType.valueOf(code),
              missing?TechnicalDataAvailability.MISSING:TechnicalDataAvailability.AVAILABLE,
              missing?"SOURCE_MISSING":"SOURCE_AVAILABLE",missing?"仍缺资料":"公共资料已导入",code,LocalDateTime.now());
        }).toList(),Map.of());
    when(sources.check(any(),any())).thenReturn(checked);
    return checked;
  }

  private Map<String,Object> protectedRows() {
    return Map.of("workspace",jdbc.queryForList("SELECT * FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",itemId),
        "tasks",jdbc.queryForList("SELECT * FROM lp_quote_tech_task WHERE oa_form_item_id=?",itemId),
        "products",jdbc.queryForList("SELECT * FROM lp_quote_tech_product WHERE oa_form_item_id=?",itemId),
        "modules",jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_module",Long.class),
        "versions",jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version",Long.class),
        "priceBatches",jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_batch",Long.class),
        "monthlyPrices",jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Long.class));
  }
}
