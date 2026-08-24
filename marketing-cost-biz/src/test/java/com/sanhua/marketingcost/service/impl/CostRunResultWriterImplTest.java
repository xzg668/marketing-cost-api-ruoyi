package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.CostRunTraceSnapshotService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CostRunResultWriterImplTest {

  @Test
  void overwritesOnlyCurrentVersionDetailsAndRebuildsTrace() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    CostRunCostItemMapper costMapper = mock(CostRunCostItemMapper.class);
    CostRunTraceSnapshotService traceService = mock(CostRunTraceSnapshotService.class);
    CostRunResultWriterImpl writer =
        new CostRunResultWriterImpl(
            partMapper, costMapper, mock(PricePrepareItemMapper.class), traceService);
    CostRunObjectResult result = result("RUN-101", 1001L);

    writer.writeQuoteResult(result);
    writer.writeQuoteResult(result);

    verify(partMapper, times(2)).deleteQuoteItemsByCostRunNo("RUN-101");
    verify(costMapper, times(2)).deleteQuoteItemsByCostRunNo("RUN-101");
    ArgumentCaptor<CostRunPartItem> part = ArgumentCaptor.forClass(CostRunPartItem.class);
    verify(partMapper, times(2)).insert(part.capture());
    assertThat(part.getValue().getCostRunVersionId()).isEqualTo(1001L);
    assertThat(part.getValue().getPricePrepareItemId()).isEqualTo(9501L);
    assertThat(part.getValue().getUnitPrice()).isEqualByComparingTo("3.000000");
    ArgumentCaptor<CostRunCostItem> cost = ArgumentCaptor.forClass(CostRunCostItem.class);
    verify(costMapper, times(2)).insert(cost.capture());
    assertThat(cost.getValue().getCostRunVersionId()).isEqualTo(1001L);
    assertThat(cost.getValue().getAmount()).isEqualByComparingTo("88.123456");
    ArgumentCaptor<QuoteCostRunVersion> version =
        ArgumentCaptor.forClass(QuoteCostRunVersion.class);
    verify(traceService, times(2)).rebuildForVersion(version.capture());
    assertThat(version.getValue().getCostRunNo()).isEqualTo("RUN-101");
  }

  @Test
  void rejectsUnversionedResultInsteadOfCreatingDetachedRows() {
    CostRunResultWriterImpl writer =
        new CostRunResultWriterImpl(
            mock(CostRunPartItemMapper.class),
            mock(CostRunCostItemMapper.class),
            mock(PricePrepareItemMapper.class),
            mock(CostRunTraceSnapshotService.class));
    CostRunContext context = quoteContext();

    assertThatThrownBy(
            () ->
                writer.writeQuoteResult(
                    CostRunObjectResult.of(
                        context, null, null, List.of(partItem()), List.of(totalCost()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("明确的成本版本");
  }

  @Test
  void truncatesDatabaseRemarksWithoutChangingAmounts() {
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    CostRunCostItemMapper costMapper = mock(CostRunCostItemMapper.class);
    CostRunResultWriterImpl writer =
        new CostRunResultWriterImpl(
            partMapper,
            costMapper,
            mock(PricePrepareItemMapper.class),
            mock(CostRunTraceSnapshotService.class));
    CostRunObjectResult result = result("RUN-LONG", 1002L);
    result.getPartItems().get(0).setRemark("价格依据".repeat(100));
    result.getCostItems().get(0).setRemark("核算依据".repeat(2000));

    writer.writeQuoteResult(result);

    ArgumentCaptor<CostRunPartItem> part = ArgumentCaptor.forClass(CostRunPartItem.class);
    verify(partMapper).insert(part.capture());
    assertThat(part.getValue().getRemark()).hasSize(200).endsWith("...(truncated)");
    ArgumentCaptor<CostRunCostItem> cost = ArgumentCaptor.forClass(CostRunCostItem.class);
    verify(costMapper).insert(cost.capture());
    assertThat(cost.getValue().getRemark()).hasSize(4000).endsWith("...(truncated)");
    assertThat(cost.getValue().getAmount()).isEqualByComparingTo("88.123456");
  }

  private CostRunObjectResult result(String runNo, Long versionId) {
    CostRunContext context = quoteContext();
    context.setCostRunNo(runNo);
    context.setCostRunVersionId(versionId);
    context.setPricePrepareNo("PPR-101");
    return CostRunObjectResult.of(
        context, versionId, null, List.of(partItem()), List.of(totalCost()));
  }

  private CostRunContext quoteContext() {
    return CostRunContext.quote(
        "OA-001", 101L, "P-001", null, "客户A", "COMMERCIAL", "2026-08", "OBJ-101");
  }

  private CostRunPartItemDto partItem() {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setProductCode("P-001");
    item.setPartCode("PART-001");
    item.setPartName("Part");
    item.setBomRowId(501L);
    item.setPricePrepareItemId(9501L);
    item.setPartQty(new BigDecimal("2.000000"));
    item.setUnitPrice(new BigDecimal("3.000000"));
    item.setAmount(new BigDecimal("6.000000"));
    return item;
  }

  private CostRunCostItemDto totalCost() {
    CostRunCostItemDto item = new CostRunCostItemDto();
    item.setCostCode("TOTAL");
    item.setCostName("不含税总成本");
    item.setAmount(new BigDecimal("88.123456"));
    return item;
  }
}
