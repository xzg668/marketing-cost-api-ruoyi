package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.DepartmentFundRateImportRequest;
import com.sanhua.marketingcost.dto.DepartmentFundRateRequest;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DepartmentFundRateServiceImplTest {

  @Test
  void newlyCreatedRateDefaultsToFinalQuoteMode() {
    DepartmentFundRateMapper mapper = mock(DepartmentFundRateMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    DepartmentFundRateServiceImpl service = new DepartmentFundRateServiceImpl(mapper);

    DepartmentFundRate created = service.create(validRequest());

    assertThat(created.getRateCalculationMode())
        .isEqualTo(DepartmentFundRate.RATE_CALCULATION_MODE_FINAL_QUOTE);
    verify(mapper).insert(created);
  }

  @Test
  void editingExistingLegacyRateWithoutModeKeepsLegacyMode() {
    DepartmentFundRateMapper mapper = mock(DepartmentFundRateMapper.class);
    DepartmentFundRate existing = existingRate();
    existing.setRateCalculationMode(DepartmentFundRate.RATE_CALCULATION_MODE_PLAN_UPLIFT);
    when(mapper.selectById(7L)).thenReturn(existing);
    DepartmentFundRateServiceImpl service = new DepartmentFundRateServiceImpl(mapper);

    DepartmentFundRateRequest update = new DepartmentFundRateRequest();
    update.setQuoteRatio(new BigDecimal("0.054"));
    DepartmentFundRate updated = service.update(7L, update);

    assertThat(updated.getRateCalculationMode())
        .isEqualTo(DepartmentFundRate.RATE_CALCULATION_MODE_PLAN_UPLIFT);
    verify(mapper).updateById(existing);
  }

  @Test
  void importedTemplateRowsDefaultToFinalQuoteMode() {
    DepartmentFundRateMapper mapper = mock(DepartmentFundRateMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    DepartmentFundRateServiceImpl service = new DepartmentFundRateServiceImpl(mapper);
    DepartmentFundRateImportRequest request = new DepartmentFundRateImportRequest();
    request.setRateYear(2026);
    request.setRows(List.of(validImportRow()));

    var response = service.importItems(request);

    assertThat(response.getInserted()).isEqualTo(1);
    assertThat(response.getRecords())
        .singleElement()
        .extracting(DepartmentFundRate::getRateCalculationMode)
        .isEqualTo(DepartmentFundRate.RATE_CALCULATION_MODE_FINAL_QUOTE);
  }

  @Test
  void importingCanonicalSubjectUpdatesLegacyAliasInsteadOfInsertingDuplicate() {
    DepartmentFundRateMapper mapper = mock(DepartmentFundRateMapper.class);
    DepartmentFundRate legacyAlias = existingRate();
    legacyAlias.setExpenseSubject("水电");
    legacyAlias.setRateCalculationMode(DepartmentFundRate.RATE_CALCULATION_MODE_PLAN_UPLIFT);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null, legacyAlias);
    DepartmentFundRateServiceImpl service = new DepartmentFundRateServiceImpl(mapper);
    DepartmentFundRateImportRequest request = new DepartmentFundRateImportRequest();
    request.setRateYear(2026);
    request.setRows(List.of(validImportRow()));

    var response = service.importItems(request);

    assertThat(response.getInserted()).isZero();
    assertThat(response.getUpdated()).isEqualTo(1);
    assertThat(legacyAlias.getExpenseSubject()).isEqualTo("水电费用");
    assertThat(legacyAlias.getRateCalculationMode())
        .isEqualTo(DepartmentFundRate.RATE_CALCULATION_MODE_FINAL_QUOTE);
    verify(mapper).updateById(legacyAlias);
  }

  private static DepartmentFundRateRequest validRequest() {
    DepartmentFundRateRequest request = new DepartmentFundRateRequest();
    request.setRateYear(2026);
    request.setBusinessDivision("商用部品事业部");
    request.setExpenseSubject("水电费用");
    request.setQuoteRatio(new BigDecimal("0.0556204944405979"));
    request.setUpliftRatio(new BigDecimal("1.05"));
    request.setManhourRate(new BigDecimal("0.4249"));
    return request;
  }

  private static DepartmentFundRate existingRate() {
    DepartmentFundRate rate = new DepartmentFundRate();
    rate.setId(7L);
    rate.setRateYear(2026);
    rate.setBusinessUnit("商用部品事业部");
    rate.setBusinessDivision("商用部品事业部");
    rate.setBusinessUnitType("COMMERCIAL");
    rate.setExpenseSubject("水电费用");
    rate.setQuoteRatio(new BigDecimal("0.053"));
    rate.setUpliftRatio(new BigDecimal("1.05"));
    rate.setManhourRate(new BigDecimal("0.4249"));
    return rate;
  }

  private static DepartmentFundRateImportRequest.DepartmentFundRateRow validImportRow() {
    var row = new DepartmentFundRateImportRequest.DepartmentFundRateRow();
    row.setBusinessDivision("商用部品事业部");
    row.setExpenseSubject("水电费用");
    row.setQuoteRatio(new BigDecimal("0.0556204944405979"));
    row.setUpliftRatio(new BigDecimal("1.05"));
    row.setManhourRate(new BigDecimal("0.4249"));
    return row;
  }
}
