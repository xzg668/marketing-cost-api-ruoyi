package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.entity.SupplierPriceDecision;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.SupplierPriceDecisionMapper;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewExportService;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewWorkflow;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class SupplierPriceReviewConfirmationTest {
  @BeforeAll static void metadata() {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SupplierPriceDecision.class);
  }
  private final QuotePricePrepareWorkbenchService prices = mock(QuotePricePrepareWorkbenchService.class);
  private final SupplierPriceDecisionMapper decisions = mock(SupplierPriceDecisionMapper.class);
  private final SupplierPriceReviewWorkflow workflow = spy(new SupplierPriceReviewWorkflow(prices, decisions,
      mock(OaFormItemMapper.class), unit -> "2026-11"));
  private final SupplierPriceReviewController controller = new SupplierPriceReviewController(workflow,
      mock(SupplierPriceReviewExportService.class));
  private final SupplierPriceReviewWorkflow.Confirmation request = new SupplierPriceReviewWorkflow.Confirmation(
      "2026-11", "scope", "fingerprint", false, LocalDate.of(2026, 11, 17));
  private final UsernamePasswordAuthenticationToken actor = new UsernamePasswordAuthenticationToken("staff", "");

  @Test void savedHighestPriceChoiceMustNotBeReportedAsFailedWhenSalaryBlocksRepricing() {
    var saved = saved("FALLBACK_HIGH");
    when(prices.generate(eq("OA-024"), eq(217L), any())).thenThrow(salaryMissing());
    var response = controller.confirm("OA-024", 217L, request, actor);
    assertThat(response.getCode()).as("the confirmation committed before repricing").isZero();
    var result = response.getData();
    assertThat(result.decisionId()).isEqualTo(43L);
    assertThat(result.decision()).isEqualTo("FALLBACK_HIGH");
    assertThat(result.repriceStatus()).isEqualTo("BLOCKED");
    assertThat(result.message()).contains("最高价选择已保存", "重新取价未完成", "两项工资均不存在");
    assertThat(result.workbench()).isNull();
    assertThat(saved.getDecision()).isEqualTo("FALLBACK_HIGH");
    verify(decisions).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    verify(decisions, never()).insert(any(SupplierPriceDecision.class));
  }
  @Test void actualPriceResultStillReachesOriginalContinuation() {
    saved("FALLBACK_HIGH");
    var prepared = new QuotePricePrepareWorkbenchResponse();
    when(prices.generate(eq("OA-024"), eq(217L), any())).thenReturn(prepared);
    var result = controller.confirm("OA-024", 217L, request, actor).getData();
    assertThat(result.repriceStatus()).isEqualTo("COMPLETED");
    assertThat(result.workbench()).isSameAs(prepared);
    verify(decisions).updateById(any(SupplierPriceDecision.class));
  }
  @Test void newApprovalChoiceIsSavedEvenWhenRepricingIsBlocked() {
    saved("WAIT_IMPORT");
    when(prices.generate(eq("OA-024"), eq(217L), any())).thenThrow(salaryMissing());
    var result = controller.confirm("OA-024", 217L, request, actor).getData();
    assertThat(result.decision()).isEqualTo("WAIT_IMPORT");
    assertThat(result.message()).contains("新增审核选择已保存").doesNotContain("最高价选择");
  }
  @Test void invalidConfirmationIsStillAnErrorAndDoesNotReprice() {
    doThrow(new IllegalArgumentException("候选价格或供货比率已变化")).when(workflow).confirm("OA-024", 217L, request, "staff");
    var result = controller.confirm("OA-024", 217L, request, actor);
    assertThat(result.getCode()).isEqualTo(400);
    assertThat(result.getMsg()).contains("已变化");
    verifyNoInteractions(prices, decisions);
  }
  @Test void unexpectedFailureRetainsConfirmationButDoesNotExposeInternalDetails() {
    saved("FALLBACK_HIGH");
    when(prices.generate(eq("OA-024"), eq(217L), any())).thenThrow(new IllegalStateException("private database detail"));
    var result = controller.confirm("OA-024", 217L, request, actor).getData();
    assertThat(result.repriceStatus()).isEqualTo("FAILED");
    assertThat(result.message()).contains("已保存", "暂时无法完成").doesNotContain("private database");
    assertThat(result.workbench()).isNull();
  }
  @Test void failureToRecordRetryReasonDoesNotEraseAlreadyCommittedConfirmation() {
    saved("FALLBACK_HIGH");
    when(prices.generate(eq("OA-024"), eq(217L), any())).thenThrow(salaryMissing());
    doThrow(new IllegalStateException("unavailable")).when(decisions).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    assertThat(controller.confirm("OA-024", 217L, request, actor).getData().repriceStatus()).isEqualTo("BLOCKED");
  }
  @Test void blockedRepricingReturnsFreshReadOnlyPricePreviewWithoutPretendingItCompleted() {
    saved("FALLBACK_HIGH");
    when(prices.generate(eq("OA-024"), eq(217L), any())).thenThrow(salaryMissing());
    var preview = new QuotePricePrepareWorkbenchResponse();
    when(prices.checkPriceSources(eq("OA-024"), eq(217L), any())).thenReturn(preview);
    var result = controller.confirm("OA-024", 217L, request, actor).getData();
    assertThat(result.repriceStatus()).isEqualTo("BLOCKED");
    assertThat(result.workbench()).isSameAs(preview);
    verify(prices).checkPriceSources(eq("OA-024"), eq(217L), argThat(query ->
        query.isRefreshCandidates() && "2026-11".equals(query.getPeriodMonth())
        && query.getPriceAsOfTime().equals(LocalDate.of(2026, 11, 17).atStartOfDay())));
    verify(prices, times(1)).generate(eq("OA-024"), eq(217L), any());
  }
  private SupplierPriceDecision saved(String choice) {
    var saved = new SupplierPriceDecision();
    saved.setId(43L); saved.setDecision(choice); saved.setOaNo("OA-024"); saved.setOaFormItemId(217L);
    saved.setPeriodMonth("2026-11"); saved.setPricingDate(LocalDate.of(2026, 11, 17)); saved.setBusinessUnitType("COMMERCIAL");
    saved.setRetryStatus("WAITING");
    doReturn(saved).when(workflow).confirm("OA-024", 217L, request, "staff");
    return saved;
  }
  private EffectiveTechnicalDataException salaryMissing() {
    return new EffectiveTechnicalDataException("TECH_DATA_EFFECTIVE_VERSION_MISSING", 217L, "2026-11",
        List.of("SALARY"), "CMS 已完成核实，本产品两项工资均不存在");
  }
}
