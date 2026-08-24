package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunRegressionDifference;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostRunResultService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostRunRegressionCompareServiceImplTest {

  @Test
  void comparisonTreatsScaleOnlyDifferenceAsEqual() {
    var report = service().compare(objectResult("100.000000", "90.000000", "固定价"),
        objectResult("100", "90", "固定价"));

    assertThat(report.isMatched()).isTrue();
    assertThat(report.getDifferences()).isEmpty();
  }

  @Test
  void comparisonReportsBomPriceAndTotalDifferencesSeparately() {
    var report = service().compare(objectResult("100", "90", "固定价"),
        objectResult("110", "91", "联动价"));

    assertThat(report.isMatched()).isFalse();
    assertThat(report.getDifferences())
        .extracting(CostRunRegressionDifference::getSection)
        .contains("RESULT", "PART_ITEM", "COST_ITEM");
    assertThat(report.getDifferences())
        .extracting(CostRunRegressionDifference::getFieldName)
        .contains("totalCost", "unitPrice", "priceSource", "amount");
  }

  @Test
  void storedSnapshotUsesOneExactVersionForHeaderAndDetails() {
    QuoteCostRunVersionMapper versionMapper = mock(QuoteCostRunVersionMapper.class);
    CostRunResultService resultService = mock(CostRunResultService.class);
    CostRunPartItemMapper partMapper = mock(CostRunPartItemMapper.class);
    CostRunCostItemMapper costMapper = mock(CostRunCostItemMapper.class);
    CostRunRegressionCompareServiceImpl service =
        new CostRunRegressionCompareServiceImpl(versionMapper, resultService, partMapper, costMapper);
    QuoteCostRunVersion version = version();
    when(versionMapper.selectList(any())).thenReturn(List.of(version));
    when(resultService.getResult(41L)).thenReturn(resultDto("130.000000"));
    when(partMapper.selectList(any())).thenReturn(List.of(partEntity()));
    when(costMapper.selectList(any())).thenReturn(List.of(costEntity()));

    CostRunObjectResult snapshot = service.loadStoredSnapshot("OA-001", "P-001");

    assertThat(snapshot.getSourceCostVersionId()).isEqualTo(41L);
    assertThat(snapshot.getResult().getTotalCost()).isEqualByComparingTo("130.000000");
    assertThat(snapshot.getPartItems()).hasSize(1);
    verify(resultService).getResult(41L);
  }

  private CostRunRegressionCompareServiceImpl service() {
    return new CostRunRegressionCompareServiceImpl(
        mock(QuoteCostRunVersionMapper.class),
        mock(CostRunResultService.class),
        mock(CostRunPartItemMapper.class),
        mock(CostRunCostItemMapper.class));
  }

  private CostRunObjectResult objectResult(String total, String unitPrice, String priceSource) {
    CostRunPartItemDto part = new CostRunPartItemDto();
    part.setProductCode("P-001");
    part.setPartCode("PART-001");
    part.setPartQty(BigDecimal.ONE);
    part.setUnitPrice(new BigDecimal(unitPrice));
    part.setAmount(new BigDecimal(unitPrice));
    part.setPriceSource(priceSource);
    CostRunCostItemDto material = new CostRunCostItemDto();
    material.setCostCode("MATERIAL");
    material.setAmount(new BigDecimal(unitPrice));
    CostRunCostItemDto totalItem = new CostRunCostItemDto();
    totalItem.setCostCode("TOTAL");
    totalItem.setAmount(new BigDecimal(total));
    return CostRunObjectResult.of(
        CostRunContext.quote(
            "OA-001", 1L, "P-001", null, "客户A", "COMMERCIAL", "2026-08", "OBJ-1"),
        41L,
        resultDto(total),
        List.of(part),
        List.of(material, totalItem));
  }

  private CostRunResultDto resultDto(String total) {
    CostRunResultDto dto = new CostRunResultDto();
    dto.setOaNo("OA-001");
    dto.setProductCode("P-001");
    dto.setPeriod("2026-08");
    dto.setTotalCost(new BigDecimal(total));
    return dto;
  }

  private QuoteCostRunVersion version() {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(41L);
    version.setOaNo("OA-001");
    version.setOaFormItemId(1L);
    version.setProductCode("P-001");
    version.setPricingMonth("2026-08");
    version.setStatus("SUCCESS");
    version.setTotalCost(new BigDecimal("130.000000"));
    return version;
  }

  private CostRunPartItem partEntity() {
    CostRunPartItem item = new CostRunPartItem();
    item.setCostRunVersionId(41L);
    item.setProductCode("P-001");
    item.setPartCode("PART-001");
    item.setQty(BigDecimal.ONE);
    item.setUnitPrice(new BigDecimal("130.000000"));
    item.setAmount(new BigDecimal("130.000000"));
    return item;
  }

  private CostRunCostItem costEntity() {
    CostRunCostItem item = new CostRunCostItem();
    item.setCostRunVersionId(41L);
    item.setLineNo(1);
    item.setProductCode("P-001");
    item.setCostCode("TOTAL");
    item.setAmount(new BigDecimal("130.000000"));
    return item;
  }
}
