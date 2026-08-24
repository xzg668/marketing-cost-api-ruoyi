package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import com.sanhua.marketingcost.service.impl.PriceLinkedItemServiceImpl;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-01 联动价导入依据实体契约")
class PriceLinkedItemImportBasisContractTest {

  @Test
  @DisplayName("实体和 Mapper 保持 lp_price_linked_item 映射")
  void entityAndMapperKeepLinkedItemTableContract() {
    assertThat(PriceLinkedItem.class.getAnnotation(TableName.class).value())
        .isEqualTo("lp_price_linked_item");
    assertThat(BaseMapper.class).isAssignableFrom(PriceLinkedItemMapper.class);
  }

  @Test
  @DisplayName("八个导入依据字段显式映射数据库列")
  void importBasisFieldsMapToDatabaseColumns() throws Exception {
    assertFieldMapping("sourceUploadBatchId", Long.class, "source_upload_batch_id");
    assertFieldMapping("sourceSheetName", String.class, "source_sheet_name");
    assertFieldMapping("sourceRowNumber", Integer.class, "source_row_number");
    assertFieldMapping("sourceFormulaCellRef", String.class, "source_formula_cell_ref");
    assertFieldMapping("sourceFormulaExpr", String.class, "source_formula_expr");
    assertFieldMapping(
        "sourceInputSnapshotJson", String.class, "source_input_snapshot_json");
    assertFieldMapping(
        "sourceTaxIncludedPrice", BigDecimal.class, "source_tax_included_price");
    assertFieldMapping(
        "sourceTaxExcludedPrice", BigDecimal.class, "source_tax_excluded_price");
  }

  @Test
  @DisplayName("旧联动价对象不要求回填新增字段")
  void legacyLinkedItemLeavesImportBasisNull() {
    PriceLinkedItem legacy = new PriceLinkedItem();
    legacy.setFormulaExpr("([factor_identity_191]+[process_fee])");
    legacy.setTaxIncluded(0);

    assertThat(legacy.getSourceUploadBatchId()).isNull();
    assertThat(legacy.getSourceSheetName()).isNull();
    assertThat(legacy.getSourceRowNumber()).isNull();
    assertThat(legacy.getSourceFormulaCellRef()).isNull();
    assertThat(legacy.getSourceFormulaExpr()).isNull();
    assertThat(legacy.getSourceInputSnapshotJson()).isNull();
    assertThat(legacy.getSourceTaxIncludedPrice()).isNull();
    assertThat(legacy.getSourceTaxExcludedPrice()).isNull();
    assertThat(legacy.getFormulaExpr())
        .isEqualTo("([factor_identity_191]+[process_fee])");
    assertThat(legacy.getTaxIncluded()).isZero();
  }

  @Test
  @DisplayName("查询 DTO 完整返回八个导入依据字段")
  void serviceDtoMappingKeepsCompleteImportBasis() throws Exception {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setSourceUploadBatchId(88L);
    item.setSourceSheetName("Sheet1");
    item.setSourceRowNumber(5);
    item.setSourceFormulaCellRef("R5");
    item.setSourceFormulaExpr("=J5*$B$2+K5*$B$3");
    item.setSourceInputSnapshotJson("{\"J5\":10,\"K5\":11}");
    item.setSourceTaxIncludedPrice(new BigDecimal("100.12345678"));
    item.setSourceTaxExcludedPrice(new BigDecimal("88.60482899"));

    PriceLinkedItemServiceImpl service = new PriceLinkedItemServiceImpl(
        mock(PriceLinkedItemMapper.class),
        mock(PriceFixedItemMapper.class),
        mock(FinanceBasePriceMapper.class),
        mock(PriceVariableMapper.class),
        mock(PriceVariableBindingService.class),
        mock(FactorVariableRegistryImpl.class),
        mock(FormulaNormalizer.class),
        mock(FormulaDisplayRenderer.class),
        mock(FormulaValidator.class),
        mock(com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService.class));
    Method toDto = PriceLinkedItemServiceImpl.class.getDeclaredMethod(
        "toDto", PriceLinkedItem.class);
    toDto.setAccessible(true);

    PriceLinkedItemDto dto = (PriceLinkedItemDto) toDto.invoke(service, item);

    assertThat(dto.getSourceUploadBatchId()).isEqualTo(88L);
    assertThat(dto.getSourceSheetName()).isEqualTo("Sheet1");
    assertThat(dto.getSourceRowNumber()).isEqualTo(5);
    assertThat(dto.getSourceFormulaCellRef()).isEqualTo("R5");
    assertThat(dto.getSourceFormulaExpr()).isEqualTo("=J5*$B$2+K5*$B$3");
    assertThat(dto.getSourceInputSnapshotJson()).contains("\"J5\":10");
    assertThat(dto.getSourceTaxIncludedPrice()).isEqualByComparingTo("100.12345678");
    assertThat(dto.getSourceTaxExcludedPrice()).isEqualByComparingTo("88.60482899");
  }

  private static void assertFieldMapping(
      String fieldName, Class<?> expectedType, String expectedColumn) throws Exception {
    Field field = PriceLinkedItem.class.getDeclaredField(fieldName);
    assertThat(field.getType()).isEqualTo(expectedType);
    assertThat(field.getAnnotation(TableField.class)).isNotNull();
    assertThat(field.getAnnotation(TableField.class).value()).isEqualTo(expectedColumn);
  }
}
