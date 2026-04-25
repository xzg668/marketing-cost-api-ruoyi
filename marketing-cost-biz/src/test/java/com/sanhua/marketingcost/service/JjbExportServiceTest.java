package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 见机表导出测试 (Task #10)。
 *
 * <p>校验：写入 → 重新读取 → 单元格值精度一致（每行偏差 ≤ 0.001 即金标）。
 */
class JjbExportServiceTest {

  @Test
  @DisplayName("两行部品 + 两行成本：往返读写，数值精度逐字段对齐")
  void roundTripPreservesValues() {
    CostRunPartItemDto p1 = newPart("203250749", "S接管", new BigDecimal("3"),
        new BigDecimal("6.658"), new BigDecimal("19.974"), "黄铜", "原材料", "BOM_CALC");
    CostRunPartItemDto p2 = newPart("203259319", "端盖", new BigDecimal("1"),
        new BigDecimal("2.94115"), new BigDecimal("2.94115"), "H59", "联动价", "FORMULA_REF");
    CostRunCostItemDto c1 = newCost("MATERIAL", "材料费", null, null, new BigDecimal("93.493229"));
    CostRunCostItemDto c2 = newCost("TOTAL", "不含税总成本", null, null, new BigDecimal("152.503"));

    CostRunPartItemService partSvc = mock(CostRunPartItemService.class);
    CostRunCostItemService costSvc = mock(CostRunCostItemService.class);
    when(partSvc.listStoredByOaNo("OA001")).thenReturn(List.of(p1, p2));
    when(costSvc.listStoredByOaNo(eq("OA001"), eq("PROD-A"))).thenReturn(List.of(c1, c2));

    JjbExportService svc = new JjbExportService(partSvc, costSvc);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int rows = svc.export("OA001", "PROD-A", out);
    assertThat(rows).isEqualTo(2);

    byte[] bytes = out.toByteArray();
    assertThat(bytes.length).isGreaterThan(100); // 至少包含 xlsx 容器头

    List<Map<Integer, Object>> partRows = readSheet(bytes, 0);
    assertThat(partRows).hasSize(2);
    // EasyExcel BigDecimal → Double 反序列化；用字符串比较确保精度
    assertThat(partRows.get(0).get(0)).hasToString("203250749");
    assertThat(partRows.get(0).get(1)).hasToString("S接管");
    assertThat(new BigDecimal(partRows.get(0).get(4).toString()))
        .isEqualByComparingTo(new BigDecimal("6.658"));
    assertThat(new BigDecimal(partRows.get(1).get(4).toString()))
        .isEqualByComparingTo(new BigDecimal("2.94115"));

    List<Map<Integer, Object>> costRows = readSheet(bytes, 1);
    assertThat(costRows).hasSize(2);
    assertThat(costRows.get(1).get(1)).hasToString("不含税总成本");
    assertThat(new BigDecimal(costRows.get(1).get(4).toString()))
        .isEqualByComparingTo(new BigDecimal("152.503"));
  }

  @Test
  @DisplayName("过滤其他产品的部品：仅导出指定 productCode 行")
  void filtersByProductCode() {
    CostRunPartItemDto target = newPart("X1", "目标件", BigDecimal.ONE, new BigDecimal("1"),
        new BigDecimal("1"), "M1", "原材料", "FIXED");
    target.setProductCode("PROD-A");
    CostRunPartItemDto other = newPart("X2", "他人件", BigDecimal.ONE, new BigDecimal("9"),
        new BigDecimal("9"), "M2", "原材料", "FIXED");
    other.setProductCode("PROD-B");

    CostRunPartItemService partSvc = mock(CostRunPartItemService.class);
    CostRunCostItemService costSvc = mock(CostRunCostItemService.class);
    when(partSvc.listStoredByOaNo("OA")).thenReturn(List.of(target, other));
    when(costSvc.listStoredByOaNo(eq("OA"), eq("PROD-A"))).thenReturn(List.of());

    JjbExportService svc = new JjbExportService(partSvc, costSvc);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertThat(svc.export("OA", "PROD-A", out)).isEqualTo(1);

    List<Map<Integer, Object>> partRows = readSheet(out.toByteArray(), 0);
    assertThat(partRows).hasSize(1);
    assertThat(partRows.get(0).get(1)).hasToString("目标件");
  }

  @Test
  @DisplayName("oaNo 或 productCode 缺失抛 IllegalArgumentException")
  void rejectsBlankParams() {
    JjbExportService svc =
        new JjbExportService(mock(CostRunPartItemService.class), mock(CostRunCostItemService.class));
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.export("", "P", new ByteArrayOutputStream()))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> svc.export("O", null, new ByteArrayOutputStream()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---------- 辅助 ----------

  private CostRunPartItemDto newPart(
      String drawingNo, String name, BigDecimal qty,
      BigDecimal unitPrice, BigDecimal amount, String material,
      String shape, String priceType) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setProductCode("PROD-A");
    dto.setPartDrawingNo(drawingNo);
    dto.setPartCode(drawingNo);
    dto.setPartName(name);
    dto.setPartQty(qty);
    dto.setUnitPrice(unitPrice);
    dto.setAmount(amount);
    dto.setMaterial(material);
    dto.setShapeAttr(shape);
    dto.setPriceType(priceType);
    dto.setPriceSource("test");
    return dto;
  }

  private CostRunCostItemDto newCost(String code, String name, BigDecimal base,
                                      BigDecimal rate, BigDecimal amount) {
    CostRunCostItemDto dto = new CostRunCostItemDto();
    dto.setCostCode(code);
    dto.setCostName(name);
    dto.setBaseAmount(base);
    dto.setRate(rate);
    dto.setAmount(amount);
    return dto;
  }

  /** 读 sheet：返回每行 cell index → value 的 map（headRowNumber=1 已自动跳表头） */
  private List<Map<Integer, Object>> readSheet(byte[] bytes, int sheetNo) {
    List<Map<Integer, Object>> collected = new ArrayList<>();
    EasyExcel.read(new ByteArrayInputStream(bytes), new ReadListener<Map<Integer, Object>>() {
      @Override
      public void invoke(Map<Integer, Object> row, AnalysisContext ctx) {
        collected.add(row);
      }

      @Override
      public void doAfterAllAnalysed(AnalysisContext ctx) {}
    }).sheet(sheetNo).headRowNumber(1).doRead();
    return collected;
  }
}
