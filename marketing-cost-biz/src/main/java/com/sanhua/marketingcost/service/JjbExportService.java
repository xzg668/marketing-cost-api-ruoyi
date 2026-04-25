package com.sanhua.marketingcost.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 见机表 Excel 导出服务 (Task #10)。
 *
 * <p>对标 Excel《产品成本计算表》"见机表3" 版式：
 * <ol>
 *   <li>Sheet1 = 部品明细：图号 / 部品名 / 用量 / 单价 / 金额 / 材质 / 形态 / 价格来源 / 备注</li>
 *   <li>Sheet2 = 成本汇总：科目代码 / 名称 / 基数 / 费率 / 金额</li>
 * </ol>
 *
 * <p>不引模板 xlsx —— 直接用 EasyExcel 编程式 writer 输出，避免后端打包二进制；
 * 财务对账只看数值，金标 152.503 由 GoldenSampleRegressionTest 保证，导出仅做载体。
 */
@Service
public class JjbExportService {

  private final CostRunPartItemService partItemService;
  private final CostRunCostItemService costItemService;

  public JjbExportService(
      CostRunPartItemService partItemService, CostRunCostItemService costItemService) {
    this.partItemService = partItemService;
    this.costItemService = costItemService;
  }

  /**
   * 导出指定 OA + 产品的见机表到 OutputStream。
   *
   * @return 写入的部品行数（>0 表示有数据，0 表示无明细但仍输出空表头）
   */
  public int export(String oaNo, String productCode, OutputStream output) {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      throw new IllegalArgumentException("oaNo / productCode 不能为空");
    }
    String oaNoValue = oaNo.trim();
    String productCodeValue = productCode.trim();

    List<CostRunPartItemDto> allParts = partItemService.listStoredByOaNo(oaNoValue);
    List<CostRunPartItemDto> parts = new ArrayList<>();
    for (CostRunPartItemDto p : allParts) {
      if (productCodeValue.equals(p.getProductCode())) {
        parts.add(p);
      }
    }
    List<CostRunCostItemDto> costs =
        costItemService.listStoredByOaNo(oaNoValue, productCodeValue);

    try (ExcelWriter writer = EasyExcel.write(output).build()) {
      writeParts(writer, parts);
      writeCosts(writer, costs);
    }
    return parts.size();
  }

  /** 写部品明细 sheet */
  private void writeParts(ExcelWriter writer, List<CostRunPartItemDto> parts) {
    WriteSheet sheet =
        EasyExcel.writerSheet(0, "部品明细")
            .head(buildPartHead())
            .build();
    List<List<Object>> rows = new ArrayList<>();
    for (CostRunPartItemDto p : parts) {
      List<Object> row = new ArrayList<>();
      row.add(nz(p.getPartDrawingNo()));
      row.add(nz(p.getPartName()));
      row.add(nz(p.getPartCode()));
      row.add(num(p.getPartQty()));
      row.add(num(p.getUnitPrice()));
      row.add(num(p.getAmount()));
      row.add(nz(p.getMaterial()));
      row.add(nz(p.getShapeAttr()));
      row.add(nz(p.getPriceType()));
      row.add(nz(p.getPriceSource()));
      row.add(nz(p.getRemark()));
      rows.add(row);
    }
    writer.write(rows, sheet);
  }

  /** 写成本汇总 sheet */
  private void writeCosts(ExcelWriter writer, List<CostRunCostItemDto> costs) {
    WriteSheet sheet =
        EasyExcel.writerSheet(1, "成本汇总")
            .head(buildCostHead())
            .build();
    List<List<Object>> rows = new ArrayList<>();
    for (CostRunCostItemDto c : costs) {
      List<Object> row = new ArrayList<>();
      row.add(nz(c.getCostCode()));
      row.add(nz(c.getCostName()));
      row.add(num(c.getBaseAmount()));
      row.add(num(c.getRate()));
      row.add(num(c.getAmount()));
      rows.add(row);
    }
    writer.write(rows, sheet);
  }

  private List<List<String>> buildPartHead() {
    List<List<String>> head = new ArrayList<>();
    head.add(Collections.singletonList("图号"));
    head.add(Collections.singletonList("部品名称"));
    head.add(Collections.singletonList("部品编码"));
    head.add(Collections.singletonList("用量"));
    head.add(Collections.singletonList("单价"));
    head.add(Collections.singletonList("金额"));
    head.add(Collections.singletonList("材质"));
    head.add(Collections.singletonList("形态属性"));
    head.add(Collections.singletonList("价格类型"));
    head.add(Collections.singletonList("价格来源"));
    head.add(Collections.singletonList("备注"));
    return head;
  }

  private List<List<String>> buildCostHead() {
    List<List<String>> head = new ArrayList<>();
    head.add(Collections.singletonList("科目代码"));
    head.add(Collections.singletonList("科目名称"));
    head.add(Collections.singletonList("基数"));
    head.add(Collections.singletonList("费率"));
    head.add(Collections.singletonList("金额"));
    return head;
  }

  /** null 字符串归一为 ""，避免 EasyExcel 写出 "null" 字面量 */
  private static String nz(String s) {
    return s == null ? "" : s;
  }

  /** BigDecimal 直接透传保留精度，null 写空字符串 */
  private static Object num(BigDecimal v) {
    return v == null ? "" : v;
  }
}
