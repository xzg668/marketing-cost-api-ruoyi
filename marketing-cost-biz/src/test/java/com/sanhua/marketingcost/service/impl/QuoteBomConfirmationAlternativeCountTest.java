package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomCancelConfirmRequest;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationLogMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeConfirmationGuard;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteBomConfirmationAlternativeCountTest {

  private OaFormMapper formMapper;
  private OaFormItemMapper itemMapper;
  private QuoteBomStatusMapper statusMapper;
  private BomCostingRowMapper costingRowMapper;
  private QuoteBomConfirmationMapper confirmationMapper;
  private QuoteBomPreparationRecordMapper preparationMapper;
  private QuoteBomAlternativeConfirmationGuard alternativeGuard;
  private QuoteBomConfirmationServiceImpl service;

  @BeforeEach
  void setUp() {
    formMapper = mock(OaFormMapper.class);
    itemMapper = mock(OaFormItemMapper.class);
    statusMapper = mock(QuoteBomStatusMapper.class);
    costingRowMapper = mock(BomCostingRowMapper.class);
    confirmationMapper = mock(QuoteBomConfirmationMapper.class);
    preparationMapper = mock(QuoteBomPreparationRecordMapper.class);
    alternativeGuard = mock(QuoteBomAlternativeConfirmationGuard.class);
    service =
        new QuoteBomConfirmationServiceImpl(
            formMapper,
            itemMapper,
            statusMapper,
            costingRowMapper,
            confirmationMapper,
            mock(QuoteBomConfirmationLogMapper.class),
            mock(QuoteCostRunVersionInvalidationService.class),
            preparationMapper,
            alternativeGuard,
            new QuoteBomContextResolver());
    mockScope();
  }

  @Test
  void defaultStandardConfirmsWithZeroReplacementCount() {
    assertConfirmationCounts(0, 0);
  }

  @Test
  void oneManualAlternativeConfirmsWithOneReplacementCount() {
    assertConfirmationCounts(1, 0);
  }

  @Test
  void twoManualAlternativesConfirmWithTwoReplacementCount() {
    assertConfirmationCounts(2, 0);
  }

  @Test
  void restoringStandardReducesReplacementCountWithoutChangingManualRowCount() {
    assertConfirmationCounts(0, 1);
  }

  @Test
  void manualCostingRowModificationIsNotCountedAsAlternativeSelection() {
    assertConfirmationCounts(2, 1);
  }

  @Test
  void laterSelectionDoesNotOverwriteHistoricalConfirmationCount() {
    when(costingRowMapper.selectQuoteCostingSnapshot(
            "OA-QBA-10", 10L, "QUOTE-TOP", "2026-07"))
        .thenReturn(List.of(costingRow(0)));
    when(confirmationMapper.selectList(any()))
        .thenReturn(List.of(), List.of());
    when(confirmationMapper.insert(any(QuoteBomConfirmation.class)))
        .thenReturn(1);
    when(
            alternativeGuard.validateAndCountManualAlternatives(
                any(), any(), eq("主制造")))
        .thenReturn(1);

    service.confirm(
        "OA-QBA-10", 10L, new QuoteBomConfirmRequest());
    ArgumentCaptor<QuoteBomConfirmation> firstCaptor =
        ArgumentCaptor.forClass(QuoteBomConfirmation.class);
    org.mockito.Mockito.verify(confirmationMapper)
        .insert(firstCaptor.capture());
    QuoteBomConfirmation first = firstCaptor.getValue();

    when(confirmationMapper.selectOne(any())).thenReturn(first);
    when(confirmationMapper.updateById(first)).thenReturn(1);
    service.cancelConfirm(
        "OA-QBA-10",
        10L,
        new QuoteBomCancelConfirmRequest());

    reset(confirmationMapper);
    when(confirmationMapper.selectList(any()))
        .thenReturn(List.of(), List.of(first));
    when(confirmationMapper.insert(any(QuoteBomConfirmation.class)))
        .thenReturn(1);
    when(
            alternativeGuard.validateAndCountManualAlternatives(
                any(), any(), eq("主制造")))
        .thenReturn(0);

    var second =
        service.confirm(
            "OA-QBA-10", 10L, new QuoteBomConfirmRequest());

    assertThat(first.getReplaceCount()).isEqualTo(1);
    assertThat(first.getConfirmStatus())
        .isEqualTo(QuoteBomConfirmation.STATUS_INVALID);
    assertThat(second.getReplaceCount()).isZero();
    assertThat(second.getConfirmVersion()).isEqualTo(2);
  }

  private void assertConfirmationCounts(int replacementCount, int manualModifiedCount) {
    when(
            alternativeGuard.validateAndCountManualAlternatives(
                any(), any(), eq("主制造")))
        .thenReturn(replacementCount);
    when(costingRowMapper.selectQuoteCostingSnapshot("OA-QBA-10", 10L, "QUOTE-TOP", "2026-07"))
        .thenReturn(
            manualModifiedCount == 0
                ? List.of(costingRow(0))
                : List.of(costingRow(0), costingRow(1)));
    when(confirmationMapper.selectList(any())).thenReturn(List.of(), List.of());
    when(confirmationMapper.insert(any(QuoteBomConfirmation.class))).thenReturn(1);

    var response =
        service.confirm("OA-QBA-10", 10L, new QuoteBomConfirmRequest());

    assertThat(response.getReplaceCount()).isEqualTo(replacementCount);
    assertThat(response.getManualModifiedCount()).isEqualTo(manualModifiedCount);
    ArgumentCaptor<QuoteBomConfirmation> captor =
        ArgumentCaptor.forClass(QuoteBomConfirmation.class);
    org.mockito.Mockito.verify(confirmationMapper).insert(captor.capture());
    assertThat(captor.getValue().getReplaceCount()).isEqualTo(replacementCount);
  }

  private void mockScope() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-QBA-10");
    form.setAccountingPeriodMonth("2026-07");
    form.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectOne(any())).thenReturn(form);

    OaFormItem item = new OaFormItem();
    item.setId(10L);
    item.setOaFormId(1L);
    item.setMaterialNo("QUOTE-TOP");
    item.setBusinessUnitType("COMMERCIAL");
    when(itemMapper.selectById(10L)).thenReturn(item);

    QuoteBomStatus status = new QuoteBomStatus();
    status.setCostPeriodMonth("2026-07");
    when(statusMapper.selectOne(any())).thenReturn(status);

    QuoteBomPreparationRecord preparation = new QuoteBomPreparationRecord();
    preparation.setOaNo("OA-QBA-10");
    preparation.setOaFormId(1L);
    preparation.setOaFormItemId(10L);
    preparation.setQuoteProductCode("QUOTE-TOP");
    preparation.setSourceTopProductCode("SOURCE-TOP");
    preparation.setPriceOrgCode("210");
    preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setActiveFlag(1);
    when(preparationMapper.selectOne(any())).thenReturn(preparation);
  }

  private BomCostingRow costingRow(int manualModified) {
    BomCostingRow row = new BomCostingRow();
    row.setMaterialCode("MAT-" + manualModified);
    row.setQtyPerParent(BigDecimal.ONE);
    row.setManualModified(manualModified);
    return row;
  }
}
