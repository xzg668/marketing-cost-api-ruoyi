package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.entity.MonthlyRepriceCostItem;
import com.sanhua.marketingcost.entity.MonthlyRepricePartItem;
import com.sanhua.marketingcost.entity.MonthlyRepriceResult;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceCostItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepricePartItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class MonthlyRepriceResultWriterImplTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceBatch.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceResult.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepricePartItem.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceCostItem.class);
  }

  @Test
  void writesMonthlyResultPartItemsAndCostItems() {
    MonthlyRepriceBatchMapper batchMapper = writableBatchMapper();
    MonthlyRepriceResultMapper resultMapper = mock(MonthlyRepriceResultMapper.class);
    MonthlyRepricePartItemMapper partItemMapper = mock(MonthlyRepricePartItemMapper.class);
    MonthlyRepriceCostItemMapper costItemMapper = mock(MonthlyRepriceCostItemMapper.class);
    MonthlyRepriceResultWriterImpl writer =
        new MonthlyRepriceResultWriterImpl(batchMapper, resultMapper, partItemMapper, costItemMapper);

    writer.write(objectResult());

    ArgumentCaptor<MonthlyRepriceResult> resultCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceResult.class);
    verify(resultMapper).insert(resultCaptor.capture());
    MonthlyRepriceResult result = resultCaptor.getValue();
    assertThat(result.getRepriceNo()).isEqualTo("MRP-001");
    assertThat(result.getCalcObjectKey()).isEqualTo("OBJ-001");
    assertThat(result.getTotalCost()).isEqualByComparingTo("130.000000");
    assertThat(result.getMaterialCost()).isEqualByComparingTo("100.000000");
    assertThat(result.getLaborCost()).isEqualByComparingTo("15.000000");
    assertThat(result.getSourceCostResultId()).isEqualTo(99L);

    ArgumentCaptor<MonthlyRepricePartItem> partCaptor =
        ArgumentCaptor.forClass(MonthlyRepricePartItem.class);
    verify(partItemMapper).insert(partCaptor.capture());
    assertThat(partCaptor.getValue().getPartCode()).isEqualTo("PART-1");
    assertThat(partCaptor.getValue().getLineNo()).isEqualTo(1);

    ArgumentCaptor<MonthlyRepriceCostItem> costCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceCostItem.class);
    verify(costItemMapper, org.mockito.Mockito.times(5)).insert(costCaptor.capture());
    assertThat(costCaptor.getAllValues())
        .extracting(MonthlyRepriceCostItem::getCostItemCode)
        .contains("MATERIAL", "DIRECT_LABOR", "INDIRECT_LABOR", "AUX_PACK", "TOTAL");
  }

  @Test
  void repeatedWriteDeletesExistingRowsBeforeInsertToKeepIdempotent() {
    MonthlyRepriceBatchMapper batchMapper = writableBatchMapper();
    MonthlyRepriceResultMapper resultMapper = mock(MonthlyRepriceResultMapper.class);
    MonthlyRepricePartItemMapper partItemMapper = mock(MonthlyRepricePartItemMapper.class);
    MonthlyRepriceCostItemMapper costItemMapper = mock(MonthlyRepriceCostItemMapper.class);
    MonthlyRepriceResultWriterImpl writer =
        new MonthlyRepriceResultWriterImpl(batchMapper, resultMapper, partItemMapper, costItemMapper);

    writer.write(objectResult());
    writer.write(objectResult());

    verify(resultMapper, org.mockito.Mockito.times(2)).delete(any());
    verify(partItemMapper, org.mockito.Mockito.times(2)).delete(any());
    verify(costItemMapper, org.mockito.Mockito.times(2)).delete(any());
    verify(resultMapper, org.mockito.Mockito.times(2)).insert(any(MonthlyRepriceResult.class));

    InOrder ordered = inOrder(costItemMapper, partItemMapper, resultMapper);
    ordered.verify(costItemMapper).delete(any());
    ordered.verify(partItemMapper).delete(any());
    ordered.verify(resultMapper).delete(any());
    ordered.verify(resultMapper).insert(any(MonthlyRepriceResult.class));
  }

  @Test
  void confirmedBatchCannotBeOverwritten() {
    MonthlyRepriceBatchMapper batchMapper = mock(MonthlyRepriceBatchMapper.class);
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setStatus("CONFIRMED");
    when(batchMapper.selectByRepriceNoForUpdate("MRP-001")).thenReturn(batch);
    MonthlyRepriceResultMapper resultMapper = mock(MonthlyRepriceResultMapper.class);
    MonthlyRepricePartItemMapper partItemMapper = mock(MonthlyRepricePartItemMapper.class);
    MonthlyRepriceCostItemMapper costItemMapper = mock(MonthlyRepriceCostItemMapper.class);
    MonthlyRepriceResultWriterImpl writer =
        new MonthlyRepriceResultWriterImpl(batchMapper, resultMapper, partItemMapper, costItemMapper);

    assertThatThrownBy(() -> writer.write(objectResult()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只读");
  }

  @Test
  void cancelledBatchCannotBeOverwritten() {
    MonthlyRepriceBatchMapper batchMapper = mock(MonthlyRepriceBatchMapper.class);
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setStatus("CANCELLED");
    when(batchMapper.selectByRepriceNoForUpdate("MRP-001")).thenReturn(batch);
    MonthlyRepriceResultMapper resultMapper = mock(MonthlyRepriceResultMapper.class);
    MonthlyRepricePartItemMapper partItemMapper = mock(MonthlyRepricePartItemMapper.class);
    MonthlyRepriceCostItemMapper costItemMapper = mock(MonthlyRepriceCostItemMapper.class);
    MonthlyRepriceResultWriterImpl writer =
        new MonthlyRepriceResultWriterImpl(batchMapper, resultMapper, partItemMapper, costItemMapper);

    assertThatThrownBy(() -> writer.write(objectResult()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只读");
  }

  private CostRunObjectResult objectResult() {
    CostRunResultDto result = new CostRunResultDto();
    result.setTotalCost(new BigDecimal("130.000000"));
    return CostRunObjectResult.of(
        context(),
        99L,
        result,
        List.of(partItem()),
        List.of(
            cost("MATERIAL", "材料费", "100.000000"),
            cost("DIRECT_LABOR", "直接人工", "10.000000"),
            cost("INDIRECT_LABOR", "辅助人工", "5.000000"),
            cost("AUX_PACK", "辅料", "2.000000"),
            cost("TOTAL", "总成本", "130.000000")));
  }

  private MonthlyRepriceBatchMapper writableBatchMapper() {
    MonthlyRepriceBatchMapper batchMapper = mock(MonthlyRepriceBatchMapper.class);
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setStatus("RUNNING");
    when(batchMapper.selectByRepriceNoForUpdate("MRP-001")).thenReturn(batch);
    return batchMapper;
  }

  private CostRunContext context() {
    return CostRunContext.monthlyReprice(
        "2026-05",
        88L,
        "MRP-001",
        "COMMERCIAL",
        "OA-001",
        7L,
        "P-001",
        "箱装",
        "客户A",
        "OBJ-001");
  }

  private CostRunPartItemDto partItem() {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setProductCode("P-001");
    item.setPartCode("PART-1");
    item.setPartName("部品1");
    item.setPartQty(BigDecimal.ONE);
    item.setUnitPrice(new BigDecimal("100.000000"));
    item.setAmount(new BigDecimal("100.000000"));
    item.setPriceSource("历史OA");
    return item;
  }

  private CostRunCostItemDto cost(String code, String name, String amount) {
    CostRunCostItemDto item = new CostRunCostItemDto();
    item.setCostCode(code);
    item.setCostName(name);
    item.setAmount(new BigDecimal(amount));
    return item;
  }
}
