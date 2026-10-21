package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.LinkedPriceVariableContext;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriceLinkedCalculationTest {
  private final PriceLinkedFormulaPreviewService preview =
      mock(PriceLinkedFormulaPreviewService.class);
  private final PriceLinkedCalcItemMapper results = mock(PriceLinkedCalcItemMapper.class);

  private PriceLinkedCalcServiceImpl service(ObjectMapper json) {
    return new PriceLinkedCalcServiceImpl(
        results, mock(PriceLinkedItemMapper.class), mock(OaFormMapper.class), json, preview);
  }

  private PriceLinkedItem rule() {
    var rule = new PriceLinkedItem();
    rule.setMaterialCode("M-001");
    rule.setFormulaExpr("[Cu]+1");
    return rule;
  }

  private PriceLinkedCalcItem previousSuccess() {
    var item = new PriceLinkedCalcItem();
    item.setItemCode("M-001");
    item.setPartUnitPrice(new BigDecimal("90"));
    item.setPartAmount(new BigDecimal("180"));
    item.setInputSnapshotJson("old-input");
    item.setCalcFingerprint("old-fingerprint");
    item.setCalculatorVersion("old-calculator");
    item.setCalcStatus("OK");
    item.setBomQty(new BigDecimal("2"));
    return item;
  }

  @Test
  void failedFormulaClearsPreviousPriceAndEvidenceWithoutFallback() {
    var response = new PriceLinkedFormulaPreviewResponse();
    response.setError("缺少Cu，原格E6");
    response.setResult(BigDecimal.TEN);
    when(preview.previewForRefresh(any(), any(LinkedPriceVariableContext.class), any()))
        .thenReturn(response);
    var item = previousSuccess();
    service(new ObjectMapper()).calculateQuoteItemForEnsure(item, rule(), null, null);
    assertThat(item.getCalcStatus()).isEqualTo("FAILED");
    assertThat(item.getCalcMessage()).isEqualTo("缺少Cu，原格E6");
    assertThat(item.getPartUnitPrice()).isNull();
    assertThat(item.getPartAmount()).isNull();
    assertThat(item.getInputSnapshotJson()).isNull();
    assertThat(item.getCalcFingerprint()).isNull();
    assertThat(item.getCalculatorVersion()).isNull();
    assertThat(item.getTraceJson()).contains("缺少Cu，原格E6");
    verifyNoInteractions(results);
  }

  @Test
  void missingRuleFailsBeforePreviewAndClearsTheOldAmount() {
    var item = previousSuccess();
    service(new ObjectMapper()).calculateQuoteItemForEnsure(item, null, null, null);
    assertThat(item.getCalcStatus()).isEqualTo("FAILED");
    assertThat(item.getPartUnitPrice()).isNull();
    assertThat(item.getPartAmount()).isNull();
    verifyNoInteractions(preview, results);
  }

  @Test
  void zeroIsAValidFormulaResultAndDoesNotBecomeMissing() {
    var response = new PriceLinkedFormulaPreviewResponse();
    response.setResult(BigDecimal.ZERO);
    when(preview.previewForRefresh(any(), any(LinkedPriceVariableContext.class), any()))
        .thenReturn(response);
    var item = previousSuccess();
    service(new ObjectMapper()).calculateQuoteItemForEnsure(item, rule(), null, null);
    assertThat(item.getCalcStatus()).isEqualTo("OK");
    assertThat(item.getCalcMessage()).isNull();
    assertThat(item.getPartUnitPrice()).isEqualByComparingTo("0");
    assertThat(item.getPartAmount()).isEqualByComparingTo("0");
  }

  @Test
  void unrecordableEvidenceCannotBeReportedAsSuccess() throws Exception {
    var json = mock(ObjectMapper.class);
    when(json.writeValueAsString(any())).thenThrow(new JsonProcessingException("invalid") {});
    var response = new PriceLinkedFormulaPreviewResponse();
    response.setResult(BigDecimal.TEN);
    when(preview.previewForRefresh(any(), any(LinkedPriceVariableContext.class), any()))
        .thenReturn(response);
    var item = previousSuccess();
    assertThatThrownBy(() -> service(json).calculateQuoteItemForEnsure(item, rule(), null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("M-001");
    assertThat(item.getPartUnitPrice()).isNull();
    verifyNoInteractions(results);
  }

  @Test
  void historicalTraceIsReturnedUnchangedWithoutRecalculation() {
    var item = new PriceLinkedCalcItem();
    item.setId(42L);
    item.setTraceJson("{\"mode\":\"dual\",\"result\":12.5,\"legacyResult\":13}");
    when(results.selectById(42L)).thenReturn(item);
    assertThat(service(new ObjectMapper()).getCalcTrace(42L).getTraceJson())
        .isEqualTo(item.getTraceJson());
    verifyNoInteractions(preview);
    verify(results, never()).updateById(any(PriceLinkedCalcItem.class));
  }
}
