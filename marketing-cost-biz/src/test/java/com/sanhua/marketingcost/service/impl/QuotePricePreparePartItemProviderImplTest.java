package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QuotePricePreparePartItemProviderImplTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PricePrepareItem.class);
    TableInfoHelper.initTableInfo(assistant, BomCostingRow.class);
    TableInfoHelper.initTableInfo(assistant, MaterialMaster.class);
  }

  @Test
  void mapsQuotePricePrepareItemsToCostRunPartItems() {
    PricePrepareItemMapper itemMapper = mock(PricePrepareItemMapper.class);
    BomCostingRowMapper bomMapper = mock(BomCostingRowMapper.class);
    MaterialMasterMapper materialMasterMapper = mock(MaterialMasterMapper.class);
    QuotePricePreparePartItemProviderImpl provider =
        new QuotePricePreparePartItemProviderImpl(itemMapper, bomMapper, materialMasterMapper);
    CostRunContext context = quoteContext();
    PricePrepareItem ready = prepareItem(
        801L, 901L, "301300339", "PEEK棒料", "READY", "固定价",
        "固定价价格准备完成", "0.00031", "0.005000");
    PricePrepareItem missing = prepareItem(
        802L, 902L, "201290727", null, "MISSING_PRICE", "NO_ROUTE",
        "未配价格类型路由：去价格类型表录入 201290727", "1", null);
    when(itemMapper.selectList(any())).thenReturn(List.of(ready, missing));
    when(bomMapper.selectList(any())).thenReturn(List.of(
        bomRow(901L, "301300339", "BOM棒料", "原材料", "棒料规格"),
        bomRow(902L, "201290727", "密封塞", "自制件", "密封塞规格")));
    when(materialMasterMapper.selectList(any())).thenReturn(List.of(
        master("301300339", "PEEK棒料主档", "DRAW-339", "SUS303Cu Φ7", "原材料"),
        master("201290727", "密封塞主档", "DRAW-727", null, "自制件")));

    var result = provider.listPreparedPartItems(context);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getPricePrepareItemId()).isEqualTo(801L);
    assertThat(result.get(0).getBomRowId()).isEqualTo(901L);
    assertThat(result.get(0).getPartCode()).isEqualTo("301300339");
    assertThat(result.get(0).getPartName()).isEqualTo("PEEK棒料");
    assertThat(result.get(0).getPartDrawingNo()).isEqualTo("DRAW-339");
    assertThat(result.get(0).getMaterial()).isEqualTo("SUS303Cu Φ7");
    assertThat(result.get(0).getShapeAttr()).isEqualTo("原材料");
    assertThat(result.get(0).getPriceSource()).isEqualTo("固定价");
    assertThat(result.get(0).getAmount()).isEqualByComparingTo("0.005000");
    assertThat(result.get(1).getPartName()).isEqualTo("密封塞");
    assertThat(result.get(1).getAmount()).isNull();
    assertThat(result.get(1).getPriceSource()).isEqualTo("NO_ROUTE");
    assertThat(result.get(1).getRemark()).contains("未配价格类型路由");
  }

  @Test
  void failsWhenPrepareSnapshotHasNoItems() {
    PricePrepareItemMapper itemMapper = mock(PricePrepareItemMapper.class);
    QuotePricePreparePartItemProviderImpl provider =
        new QuotePricePreparePartItemProviderImpl(
            itemMapper, mock(BomCostingRowMapper.class), mock(MaterialMasterMapper.class));
    when(itemMapper.selectList(any())).thenReturn(List.of());

    assertThatThrownBy(() -> provider.listPreparedPartItems(quoteContext()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PPR-001")
        .hasMessageContaining("缺少部品明细");
  }

  @Test
  void supportsOnlyQuoteContextWithPrepareNo() {
    QuotePricePreparePartItemProviderImpl provider =
        new QuotePricePreparePartItemProviderImpl(
            mock(PricePrepareItemMapper.class),
            mock(BomCostingRowMapper.class),
            mock(MaterialMasterMapper.class));
    CostRunContext quote = quoteContext();
    CostRunContext withoutPrepare = quoteContext();
    withoutPrepare.setPricePrepareNo(null);
    CostRunContext monthly = CostRunContext.monthlyReprice(
        "2026-06", 1L, "MR-1", "COMMERCIAL", "OA-001", 14L,
        "1001900001090", "箱装", "客户A", "OBJ-1");
    monthly.setPricePrepareNo("PPR-001");

    assertThat(provider.supports(quote)).isTrue();
    assertThat(provider.supports(withoutPrepare)).isFalse();
    assertThat(provider.supports(monthly)).isFalse();
  }

  private CostRunContext quoteContext() {
    CostRunContext context = CostRunContext.quote(
        "OA-001",
        14L,
        "1001900001090",
        "箱装",
        "客户A",
        "COMMERCIAL",
        "2026-06",
        "QUOTE:14");
    context.setPricePrepareNo("PPR-001");
    return context;
  }

  private PricePrepareItem prepareItem(
      Long id,
      Long bomRowId,
      String materialCode,
      String materialName,
      String status,
      String priceSource,
      String message,
      String quantity,
      String amount) {
    PricePrepareItem item = new PricePrepareItem();
    item.setId(id);
    item.setPrepareNo("PPR-001");
    item.setPeriodMonth("2026-06");
    item.setOaNo("OA-001");
    item.setOaFormItemId(14L);
    item.setTopProductCode("1001900001090");
    item.setBomRowId(bomRowId);
    item.setMaterialCode(materialCode);
    item.setMaterialName(materialName);
    item.setQuantity(quantity == null ? null : new BigDecimal(quantity));
    item.setUnitPrice(new BigDecimal("16.129032"));
    item.setAmount(amount == null ? null : new BigDecimal(amount));
    item.setStatus(status);
    item.setPriceSource(priceSource);
    item.setMessage(message);
    return item;
  }

  private BomCostingRow bomRow(
      Long id, String materialCode, String materialName, String shapeAttr, String materialSpec) {
    BomCostingRow row = new BomCostingRow();
    row.setId(id);
    row.setOaNo("OA-001");
    row.setOaFormItemId(14L);
    row.setTopProductCode("1001900001090");
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialName);
    row.setShapeAttr(shapeAttr);
    row.setMaterialSpec(materialSpec);
    row.setQtyPerTop(BigDecimal.ONE);
    return row;
  }

  private MaterialMaster master(
      String materialCode, String materialName, String drawingNo, String material, String shapeAttr) {
    MaterialMaster master = new MaterialMaster();
    master.setMaterialCode(materialCode);
    master.setMaterialName(materialName);
    master.setDrawingNo(drawingNo);
    master.setMaterial(material);
    master.setShapeAttr(shapeAttr);
    return master;
  }
}
