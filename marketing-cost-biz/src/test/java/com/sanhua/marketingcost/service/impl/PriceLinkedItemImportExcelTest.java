package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.formula.normalize.VariableAliasIndex;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PriceLinkedItemServiceImpl.importExcel} 单测 —— T18 核心路径。
 *
 * <p>覆盖：
 * <ol>
 *   <li>正常路径：2 行联动 + 1 行固定 → 分别走 linked/fixed 表</li>
 *   <li>含非法公式行：该行记入 errors 不入库，其余正常</li>
 *   <li>物料代码为空：skip 不触达任何 Mapper</li>
 * </ol>
 *
 * <p>MP LambdaQuery 依赖 TableInfo 缓存，故 {@code @BeforeAll} 手工预热两个实体。
 */
class PriceLinkedItemImportExcelTest {

  private PriceLinkedItemMapper itemMapper;
  private PriceFixedItemMapper fixedItemMapper;
  private FormulaNormalizer formulaNormalizer;
  private PriceLinkedItemServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceLinkedItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceFixedItem.class);
  }

  @BeforeEach
  void setUp() {
    itemMapper = mock(PriceLinkedItemMapper.class);
    fixedItemMapper = mock(PriceFixedItemMapper.class);
    // 用 stub 的 Normalizer 避开真实 VariableAliasIndex 依赖：非空即通过，空串抛语法异常
    formulaNormalizer = new FormulaNormalizer(
        mock(VariableAliasIndex.class), mock(RowLocalPlaceholderRegistry.class)) {
      @Override
      public String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
          return "";
        }
        if (raw.contains("!!INVALID!!")) {
          throw new FormulaSyntaxException("mocked invalid formula");
        }
        return "[NORMALIZED]" + raw;
      }
    };
    // Plan B T6：toDto 需要 renderer；import 路径只走写表，renderer 读路径不触发
    FormulaDisplayRenderer renderer = new FormulaDisplayRenderer(
        mock(PriceVariableMapper.class), mock(RowLocalPlaceholderRegistry.class));
    // 方案 A 加严：toDto 需要 validator；import 路径不触发，此处给个空白名单 mock 即可
    PriceVariableMapper validatorMapper = mock(PriceVariableMapper.class);
    when(validatorMapper.selectList(any(Wrapper.class))).thenReturn(java.util.List.of());
    FormulaValidator validator = new FormulaValidator(
        validatorMapper, mock(RowLocalPlaceholderRegistry.class));
    validator.init();
    service = new PriceLinkedItemServiceImpl(
        itemMapper, fixedItemMapper, formulaNormalizer, renderer, validator);
  }

  @Test
  @DisplayName("importExcel：联动行 + 固定行 按 orderType 分流")
  void importExcel_routesByOrderType() throws Exception {
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(fixedItemMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    byte[] xlsx = buildXlsx(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "电解铜+加工费", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"},
        {"210", "供管处", "供应商B", "S002", "部品联动", "物料B", "M002", "SPEC-B", "千克",
            "Ag*0.012+Cu*0.5", 0.0, 0.0, 39.0, null, 345.1, "0", null, null, "联动"},
        {null, null, "SUS304板", null, null, "废不锈钢", "B", null, null,
            null, null, null, null, null, 7.0, "0", null, null, "固定"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-02");

    assertThat(resp.getBatchId()).isNotBlank();
    assertThat(resp.getLinkedCount()).isEqualTo(2);
    assertThat(resp.getFixedCount()).isEqualTo(1);
    assertThat(resp.getSkipped()).isZero();
    assertThat(resp.getErrors()).isEmpty();
    verify(itemMapper, times(2)).insert(any(PriceLinkedItem.class));
    verify(fixedItemMapper).insert(any(PriceFixedItem.class));
  }

  @Test
  @DisplayName("importExcel：联动行含非法公式 → 行 skip，其他正常")
  void importExcel_invalidFormulaRowSkipped() throws Exception {
    when(itemMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    byte[] xlsx = buildXlsx(new Object[][] {
        {"210", "供管处", "供应商A", "S001", "部品联动", "物料A", "M001", "SPEC-A", "千克",
            "电解铜+加工费", 0.0, 0.0, 12.8, null, 91.0, "0", null, null, "联动"},
        {"210", "供管处", "供应商B", "S002", "部品联动", "物料B", "M002", "SPEC-B", "千克",
            "!!INVALID!!", 0.0, 0.0, 10.0, null, 50.0, "0", null, null, "联动"}
    });

    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(xlsx), "2026-02");

    assertThat(resp.getLinkedCount()).isEqualTo(1);
    assertThat(resp.getSkipped()).isEqualTo(1);
    assertThat(resp.getErrors()).hasSize(1);
    assertThat(resp.getErrors().get(0).getMaterialCode()).isEqualTo("M002");
    assertThat(resp.getErrors().get(0).getMessage()).contains("公式非法");
    verify(itemMapper, times(1)).insert(any(PriceLinkedItem.class));
    verify(fixedItemMapper, never()).insert(any(PriceFixedItem.class));
  }

  @Test
  @DisplayName("importExcel：pricingMonth 为空 → 全跳过不碰任何 Mapper")
  void importExcel_missingPricingMonth_skipsAll() {
    PriceItemImportResponse resp = service.importExcel(
        new ByteArrayInputStream(new byte[]{1, 2}), null);

    assertThat(resp.getLinkedCount()).isZero();
    assertThat(resp.getFixedCount()).isZero();
    assertThat(resp.getSkipped()).isEqualTo(1);
    assertThat(resp.getErrors()).hasSize(1);
    verify(itemMapper, never()).insert(any(PriceLinkedItem.class));
    verify(fixedItemMapper, never()).insert(any(PriceFixedItem.class));
  }

  /**
   * 构造一个 1 行表头 + N 行数据的 xlsx —— 表头列顺序与 PriceItemExcelImportRow 19 列一致。
   * 传入数据每行 19 个对象（null 也可），按列顺序 cell。
   */
  private byte[] buildXlsx(Object[][] dataRows) throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook();
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("原材料");
      // 表头行（顺序不必与 DTO 声明一致，EasyExcel 按表头名绑定字段）
      String[] headers = {
          "组织", "来源", "供应商名称", "供应商代码", "采购分类",
          "物料名称", "物料代码", "规格型号", "单位", "联动公式",
          "下料重", "净重", "加工费", "代理费", "单价",
          "是否含税", "生效日期", "失效日期", "订单类型"
      };
      Row header = sheet.createRow(0);
      for (int i = 0; i < headers.length; i++) {
        header.createCell(i).setCellValue(headers[i]);
      }
      for (int r = 0; r < dataRows.length; r++) {
        Row row = sheet.createRow(r + 1);
        Object[] v = dataRows[r];
        for (int c = 0; c < v.length; c++) {
          if (v[c] == null) {
            continue;
          }
          if (v[c] instanceof Number n) {
            row.createCell(c).setCellValue(n.doubleValue());
          } else {
            row.createCell(c).setCellValue(String.valueOf(v[c]));
          }
        }
      }
      wb.write(out);
      return out.toByteArray();
    }
  }
}
