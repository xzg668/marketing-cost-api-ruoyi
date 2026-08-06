package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceRangeItemImportResult;
import com.sanhua.marketingcost.dto.RangePriceTypeConflict;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.MaterialPriceTypeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@DisplayName("MFRP-02 区间价导入服务")
class PriceRangeItemServiceImplTest {

  private PriceRangeItemMapper itemMapper;
  private PriceRangeFactorRuleMapper factorRuleMapper;
  private PriceRangeItemServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceRangeItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceRangeFactorRule.class);
  }

  @BeforeEach
  void setUp() {
    itemMapper = mock(PriceRangeItemMapper.class);
    factorRuleMapper = mock(PriceRangeFactorRuleMapper.class);
    service = new PriceRangeItemServiceImpl(itemMapper, factorRuleMapper);
  }

  @Test
  @DisplayName("QTY 导入保持旧逻辑，只写 lp_price_range_item")
  void qtyImportStillWritesOnlyRangeItems() {
    when(itemMapper.selectOne(any())).thenReturn(null);
    when(itemMapper.selectList(any())).thenReturn(List.of());
    PriceRangeItemImportRequest request = new PriceRangeItemImportRequest();
    request.setRows(List.of(row("MAT-QTY", "0", "10", "5.000000")));

    List<PriceRangeItem> imported = service.importItems(request);

    assertThat(imported).hasSize(1);
    verifyNoInteractions(factorRuleMapper);
    ArgumentCaptor<PriceRangeItem> itemCaptor = ArgumentCaptor.forClass(PriceRangeItem.class);
    verify(itemMapper).insert(itemCaptor.capture());
    PriceRangeItem inserted = itemCaptor.getValue();
    assertThat(inserted.getMaterialCode()).isEqualTo("MAT-QTY");
    assertThat(inserted.getRangeBasis()).isEqualTo("QTY");
    assertThat(inserted.getFactorRuleId()).isNull();
    assertThat(inserted.getFactorCode()).isNull();
    assertThat(inserted.getCurrentFlag()).isOne();
  }

  @Test
  @DisplayName("FACTOR 导入插入 1 条规则和多条区间明细")
  void factorImportCreatesRuleAndItems() {
    when(factorRuleMapper.selectList(any())).thenReturn(List.of());
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(101L);
      return 1;
    });
    PriceRangeItemImportRequest request = factorRequest(
        "CU",
        row("201850160", "87501", "92500", "0.392035"),
        row("201850160", "92501", "97500", "0.412035"));

    List<PriceRangeItem> imported = service.importItems(request);

    assertThat(imported).hasSize(2);
    ArgumentCaptor<PriceRangeFactorRule> ruleCaptor =
        ArgumentCaptor.forClass(PriceRangeFactorRule.class);
    verify(factorRuleMapper).insert(ruleCaptor.capture());
    PriceRangeFactorRule rule = ruleCaptor.getValue();
    assertThat(rule.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(rule.getMaterialCode()).isEqualTo("201850160");
    assertThat(rule.getFactorCode()).isEqualTo("CU");
    assertThat(rule.getFactorName()).isEqualTo("电解铜");
    assertThat(rule.getVersionNo()).isOne();
    assertThat(rule.getImportBatchNo()).isEqualTo("BATCH-CU-001");
    assertThat(rule.getSourceSheet()).isEqualTo("区间铜价");
    assertThat(rule.getCurrentFlag()).isOne();

    ArgumentCaptor<PriceRangeItem> itemCaptor = ArgumentCaptor.forClass(PriceRangeItem.class);
    verify(itemMapper, times(2)).insert(itemCaptor.capture());
    assertThat(itemCaptor.getAllValues())
        .allSatisfy(item -> {
          assertThat(item.getBusinessUnitType()).isEqualTo("COMMERCIAL");
          assertThat(item.getRangeBasis()).isEqualTo("FACTOR");
          assertThat(item.getFactorRuleId()).isEqualTo(101L);
          assertThat(item.getFactorCode()).isEqualTo("CU");
          assertThat(item.getImportBatchNo()).isEqualTo("BATCH-CU-001");
          assertThat(item.getCurrentFlag()).isOne();
          assertThat(item.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
        });
  }

  @Test
  @DisplayName("FACTOR 再次导入同一物料时旧规则和旧明细失效")
  void factorReimportExpiresOldRuleAndItems() {
    PriceRangeFactorRule oldRule = oldRule(10L, "CU", 1);
    PriceRangeItem oldItem = oldItem(100L, 10L);
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(oldRule));
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(11L);
      return 1;
    });
    when(itemMapper.selectList(any())).thenReturn(List.of(oldItem));
    PriceRangeItemImportRequest request = factorRequest(
        "ZN",
        row("201850160", "20000", "25000", "0.512000"));

    service.importItems(request);

    assertThat(oldRule.getCurrentFlag()).isZero();
    assertThat(oldRule.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(oldItem.getCurrentFlag()).isZero();
    assertThat(oldItem.getEffectiveTo()).isNull();
    verify(factorRuleMapper).updateById(oldRule);
    verify(itemMapper).updateById(oldItem);

    InOrder order = inOrder(factorRuleMapper, itemMapper);
    order.verify(factorRuleMapper).insert(any(PriceRangeFactorRule.class));
    order.verify(itemMapper).insert(any(PriceRangeItem.class));
    order.verify(factorRuleMapper).updateById(oldRule);
  }

  @Test
  @DisplayName("同一物料同一批次存在多个 factorCode 时整批失败")
  void factorImportRejectsMultipleFactorCodesForSameMaterial() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow cu =
        row("201850160", "87501", "92500", "0.392035");
    cu.setFactorCode("CU");
    PriceRangeItemImportRequest.PriceRangeItemImportRow zn =
        row("201850160", "92501", "97500", "0.412035");
    zn.setFactorCode("ZN");
    PriceRangeItemImportRequest request = factorRequest("CU", cu, zn);

    assertThatThrownBy(() -> service.importItems(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("多个 factorCode");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("同一物料同一批次区间重叠时整批失败")
  void factorImportRejectsOverlappingRanges() {
    PriceRangeItemImportRequest request = factorRequest(
        "CU",
        row("201850160", "87501", "92500", "0.392035"),
        row("201850160", "92400", "97500", "0.412035"));

    assertThatThrownBy(() -> service.importItems(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("区间重叠");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("FACTOR 导入价格为空时整批失败")
  void factorImportRejectsMissingPrice() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow row =
        row("201850160", "87501", "92500", null);
    PriceRangeItemImportRequest request = factorRequest("CU", row);

    assertThatThrownBy(() -> service.importItems(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("价格为空");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("新规则插入失败时旧版本不会被提前失效")
  void factorRuleInsertFailureDoesNotExpireOldVersion() {
    PriceRangeFactorRule oldRule = oldRule(10L, "CU", 1);
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(oldRule));
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class)))
        .thenThrow(new IllegalStateException("insert failed"));
    PriceRangeItemImportRequest request = factorRequest(
        "CU",
        row("201850160", "87501", "92500", "0.392035"));

    assertThatThrownBy(() -> service.importItems(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("insert failed");

    assertThat(oldRule.getCurrentFlag()).isOne();
    assertThat(oldRule.getEffectiveTo()).isNull();
    verify(factorRuleMapper, never()).updateById(any(PriceRangeFactorRule.class));
    verify(itemMapper, never()).updateById(any(PriceRangeItem.class));
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("FACTOR 导入结果返回价格类型冲突清单")
  void factorImportWithResultReturnsPriceTypeConflicts() {
    MaterialPriceTypeService materialPriceTypeService = mock(MaterialPriceTypeService.class);
    PriceRangeItemServiceImpl serviceWithPriceType =
        new PriceRangeItemServiceImpl(itemMapper, factorRuleMapper, materialPriceTypeService);
    when(factorRuleMapper.selectList(any())).thenReturn(List.of());
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(101L);
      return 1;
    });
    RangePriceTypeConflict conflict = new RangePriceTypeConflict();
    conflict.setMaterialCode("201850160");
    conflict.setCurrentPriceType("固定价");
    conflict.setSuggestedPriceType("区间价");
    when(materialPriceTypeService.findRangePriceTypeConflicts(any())).thenReturn(List.of(conflict));

    PriceRangeItemImportResult result = serviceWithPriceType.importItemsWithResult(
        factorRequest("CU", row("201850160", "87501", "92500", "0.392035")));

    assertThat(result.getItems()).hasSize(1);
    assertThat(result.getPriceTypeConflicts()).hasSize(1);
    assertThat(result.getPriceTypeConflicts().get(0).getCurrentPriceType()).isEqualTo("固定价");
    verify(materialPriceTypeService).findRangePriceTypeConflicts(any());
  }

  @Test
  @DisplayName("RPI1-07 同料号不同供应商代码允许使用相同区间")
  void factorImportAllowsSameRangeForDifferentSupplierCodes() {
    prepareFactorRuleInsert(201L);
    PriceRangeItemImportRequest.PriceRangeItemImportRow supplierA =
        supplierRow("201503873", "57001", "60000", "0.9947", "S000841", "供应商A");
    PriceRangeItemImportRequest.PriceRangeItemImportRow supplierB =
        supplierRow("201503873", "57001", "60000", "1.0047", "S001289", "供应商B");

    List<PriceRangeItem> imported = service.importItems(factorRequest("CU", supplierA, supplierB));

    assertThat(imported).hasSize(2);
    verify(factorRuleMapper).insert(any(PriceRangeFactorRule.class));
    verify(itemMapper, times(2)).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-07 同料号同供应商代码的重叠区间仍拒绝")
  void factorImportRejectsOverlapForSameSupplierCode() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow first =
        supplierRow("201503873", "57001", "60000", "0.9947", "s000841", "供应商旧名称");
    PriceRangeItemImportRequest.PriceRangeItemImportRow second =
        supplierRow("201503873", "59000", "63000", "1.0047", "S000841", "供应商新名称");

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", first, second)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("区间重叠")
        .hasMessageContaining("201503873")
        .hasMessageContaining("CODE:S000841");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-07 无代码的不同供应商名称允许相同区间")
  void factorImportAllowsSameRangeForDifferentSupplierNamesWithoutCode() {
    prepareFactorRuleInsert(202L);
    PriceRangeItemImportRequest.PriceRangeItemImportRow supplierA =
        supplierRow("201503873", "57001", "60000", "0.9947", null, "供应商甲有限公司");
    PriceRangeItemImportRequest.PriceRangeItemImportRow supplierB =
        supplierRow("201503873", "57001", "60000", "1.0047", null, "供应商乙有限公司");

    List<PriceRangeItem> imported = service.importItems(factorRequest("CU", supplierA, supplierB));

    assertThat(imported).hasSize(2);
    verify(itemMapper, times(2)).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-07 无代码但标准化名称相同的重叠区间拒绝")
  void factorImportRejectsOverlapForSameNormalizedSupplierName() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow first =
        supplierRow("201503873", "57001", "60000", "0.9947", null, "吉林省 合信汽配有限公司");
    PriceRangeItemImportRequest.PriceRangeItemImportRow second =
        supplierRow("201503873", "59000", "63000", "1.0047", null, "吉林省合信汽配有限公司");

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", first, second)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("区间重叠")
        .hasMessageContaining("NAME:吉林省合信汽配有限公司");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-07 供应商名称相同但代码不同仍按两个身份校验")
  void factorImportKeepsDifferentCodesSeparateWhenNamesAreEqual() {
    prepareFactorRuleInsert(203L);
    PriceRangeItemImportRequest.PriceRangeItemImportRow supplierA =
        supplierRow("201503873", "57001", "60000", "0.9947", "SUP-A", "同名供应商");
    PriceRangeItemImportRequest.PriceRangeItemImportRow supplierB =
        supplierRow("201503873", "57001", "60000", "1.0047", "SUP-B", "同名供应商");

    List<PriceRangeItem> imported = service.importItems(factorRequest("CU", supplierA, supplierB));

    assertThat(imported).hasSize(2);
    verify(itemMapper, times(2)).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-07 LEGACY行同料号重叠仍按原逻辑拒绝")
  void factorImportRejectsOverlappingLegacyRows() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow first =
        row("LEGACY-MAT", "57001", "60000", "0.9947");
    PriceRangeItemImportRequest.PriceRangeItemImportRow second =
        row("LEGACY-MAT", "59000", "63000", "1.0047");

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", first, second)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("区间重叠")
        .hasMessageContaining("LEGACY-MAT")
        .hasMessageContaining("LEGACY");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-07 不同factorCode分组校验后仍由物料单因素规则阻断")
  void factorImportValidatesDifferentFactorCodesSeparately() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow cu =
        supplierRow("201503873", "57001", "60000", "0.9947", "S000841", "供应商A");
    cu.setFactorCode("CU");
    PriceRangeItemImportRequest.PriceRangeItemImportRow zn =
        supplierRow("201503873", "57001", "60000", "1.0047", "S000841", "供应商A");
    zn.setFactorCode("ZN");

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", cu, zn)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("多个 factorCode")
        .hasMessageNotContaining("区间重叠");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-07 任一供应商分组校验失败时整批不写入")
  void factorImportValidationFailureBlocksWholeBatchBeforeWrites() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow valid =
        supplierRow("VALID-MAT", "57001", "60000", "0.9947", "SUP-OK", "正常供应商");
    PriceRangeItemImportRequest.PriceRangeItemImportRow invalidFirst =
        supplierRow("INVALID-MAT", "57001", "60000", "1.0047", "SUP-BAD", "异常供应商");
    PriceRangeItemImportRequest.PriceRangeItemImportRow invalidSecond =
        supplierRow("INVALID-MAT", "59000", "63000", "1.0147", "SUP-BAD", "异常供应商");

    assertThatThrownBy(() -> service.importItems(
        factorRequest("CU", valid, invalidFirst, invalidSecond)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("INVALID-MAT")
        .hasMessageContaining("CODE:SUP-BAD");
    verifyNoInteractions(factorRuleMapper);
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
    verify(itemMapper, never()).updateById(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-07 重叠错误提示同时包含料号供应商身份和影响因素")
  void factorImportOverlapMessageContainsMaterialSupplierAndFactor() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow first =
        supplierRow("MESSAGE-MAT", "57001", "60000", "0.9947", "SUP-001", "供应商");
    PriceRangeItemImportRequest.PriceRangeItemImportRow second =
        supplierRow("MESSAGE-MAT", "59000", "63000", "1.0047", "SUP-001", "供应商");

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", first, second)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MESSAGE-MAT")
        .hasMessageContaining("CODE:SUP-001")
        .hasMessageContaining("CU");
  }

  @Test
  @DisplayName("RPI1-07 真实结构8个产品供应商组合可导入80条相同区间")
  void factorImportAcceptsEightyRowsAcrossMaterialSupplierGroups() {
    long[] nextRuleId = {300L};
    when(factorRuleMapper.selectList(any())).thenReturn(List.of());
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(nextRuleId[0]++);
      return 1;
    });
    String[][] combinations = {
        {"201503873", "S000841", "公主岭市远达实业有限公司"},
        {"201503874", "S000841", "公主岭市远达实业有限公司"},
        {"201503702", "S000841", "公主岭市远达实业有限公司"},
        {"201503705", "S000841", "公主岭市远达实业有限公司"},
        {"201503873", "S001289", "吉林省合信汽配有限公司"},
        {"201503874", "S001289", "吉林省合信汽配有限公司"},
        {"201503703", "S001289", "吉林省合信汽配有限公司"},
        {"201503706", "S001289", "吉林省合信汽配有限公司"}
    };
    List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows = new ArrayList<>();
    for (String[] combination : combinations) {
      for (int intervalIndex = 0; intervalIndex < 10; intervalIndex += 1) {
        int rangeLow = 57001 + intervalIndex * 3000;
        int rangeHigh = 60000 + intervalIndex * 3000;
        rows.add(supplierRow(
            combination[0],
            String.valueOf(rangeLow),
            String.valueOf(rangeHigh),
            "0.9947",
            combination[1],
            combination[2]));
      }
    }

    List<PriceRangeItem> imported = service.importItems(
        factorRequest(
            "CU",
            rows.toArray(PriceRangeItemImportRequest.PriceRangeItemImportRow[]::new)));

    assertThat(imported).hasSize(80);
    verify(factorRuleMapper, times(6)).insert(any(PriceRangeFactorRule.class));
    verify(itemMapper, times(80)).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-08 同料号两个供应商共享当前版本并保留各自有效期")
  void factorImportCreatesOneCurrentVersionWithSupplierSpecificDates() {
    prepareFactorRuleInsert(401L);
    PriceRangeItemImportRequest.PriceRangeItemImportRow supplierA = datedSupplierRow(
        "201503873", "57001", "60000", "0.9947", "S000841", "供应商A",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31));
    PriceRangeItemImportRequest.PriceRangeItemImportRow supplierB = datedSupplierRow(
        "201503873", "57001", "60000", "1.0047", "S001289", "供应商B",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

    List<PriceRangeItem> imported = service.importItems(factorRequest("CU", supplierA, supplierB));

    assertThat(imported).hasSize(2);
    assertThat(imported).extracting(PriceRangeItem::getFactorRuleId).containsOnly(401L);
    assertThat(imported)
        .filteredOn(item -> "S000841".equals(item.getSupplierCode()))
        .singleElement()
        .satisfies(item -> {
          assertThat(item.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
          assertThat(item.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 7, 31));
        });
    assertThat(imported)
        .filteredOn(item -> "S001289".equals(item.getSupplierCode()))
        .singleElement()
        .satisfies(item -> {
          assertThat(item.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
          assertThat(item.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        });
    verify(factorRuleMapper).insert(any(PriceRangeFactorRule.class));
  }

  @Test
  @DisplayName("RPI1-08 旧版本失效时保留历史明细原有效期")
  void factorReimportPreservesHistoricalItemValidityDates() {
    PriceRangeFactorRule oldRule = oldRule("201503873", 410L, "CU", 1);
    PriceRangeItem oldItem = currentFactorItem(
        411L, 410L, "201503873", "S000841", "供应商A",
        "57001", "60000", "0.9000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31));
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(oldRule));
    when(itemMapper.selectList(any())).thenReturn(List.of(oldItem));
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(412L);
      return 1;
    });
    PriceRangeItemImportRequest.PriceRangeItemImportRow changedA = datedSupplierRow(
        "201503873", "57001", "60000", "0.9500", "S000841", "供应商A",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

    service.importItems(factorRequest("CU", changedA));

    assertThat(oldRule.getCurrentFlag()).isZero();
    assertThat(oldRule.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 7, 1));
    assertThat(oldItem.getCurrentFlag()).isZero();
    assertThat(oldItem.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
    assertThat(oldItem.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 7, 31));
    verify(itemMapper).updateById(oldItem);
  }

  @Test
  @DisplayName("RPI1-08 新版本明细插入失败时旧版本不提前失效")
  void factorItemInsertFailureDoesNotExpireOldVersion() {
    PriceRangeFactorRule oldRule = oldRule("201503873", 420L, "CU", 1);
    PriceRangeItem oldItem = currentFactorItem(
        421L, 420L, "201503873", "S000841", "供应商A",
        "57001", "60000", "0.9000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(oldRule));
    when(itemMapper.selectList(any())).thenReturn(List.of(oldItem));
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(422L);
      return 1;
    });
    when(itemMapper.insert(any(PriceRangeItem.class)))
        .thenThrow(new IllegalStateException("item insert failed"));
    PriceRangeItemImportRequest.PriceRangeItemImportRow changedA = datedSupplierRow(
        "201503873", "57001", "60000", "0.9500", "S000841", "供应商A",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", changedA)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("item insert failed");

    assertThat(oldRule.getCurrentFlag()).isOne();
    assertThat(oldItem.getCurrentFlag()).isOne();
    verify(factorRuleMapper, never()).updateById(any(PriceRangeFactorRule.class));
    verify(itemMapper, never()).updateById(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-08 第二次只导入供应商A时合并供应商B原区间和有效期")
  void factorReimportCarriesForwardSuppliersMissingFromRequest() {
    PriceRangeFactorRule oldRule = oldRule("201503873", 430L, "CU", 1);
    PriceRangeItem oldA = currentFactorItem(
        431L, 430L, "201503873", "S000841", "供应商A",
        "57001", "60000", "0.9000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 31));
    PriceRangeItem oldB = currentFactorItem(
        432L, 430L, "201503873", "S001289", "供应商B",
        "57001", "60000", "1.1000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(oldRule));
    when(itemMapper.selectList(any())).thenReturn(List.of(oldA, oldB));
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(433L);
      return 1;
    });
    PriceRangeItemImportRequest.PriceRangeItemImportRow changedA = datedSupplierRow(
        "201503873", "57001", "60000", "0.9500", "S000841", "供应商A",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

    List<PriceRangeItem> imported = service.importItems(factorRequest("CU", changedA));

    assertThat(imported).hasSize(2);
    assertThat(imported)
        .filteredOn(item -> "S000841".equals(item.getSupplierCode()))
        .singleElement()
        .satisfies(item -> {
          assertThat(item.getPriceExclTax()).isEqualByComparingTo("0.9500");
          assertThat(item.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 7, 1));
          assertThat(item.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 7, 31));
        });
    assertThat(imported)
        .filteredOn(item -> "S001289".equals(item.getSupplierCode()))
        .singleElement()
        .satisfies(item -> {
          assertThat(item.getId()).isNull();
          assertThat(item.getFactorRuleId()).isEqualTo(433L);
          assertThat(item.getPriceExclTax()).isEqualByComparingTo("1.1000");
          assertThat(item.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 6, 1));
          assertThat(item.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        });
    verify(itemMapper, times(2)).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-08 完全相同的供应商日期区间和价格重复导入保持幂等")
  void factorReimportSkipsIdenticalCurrentVersion() {
    PriceRangeFactorRule currentRule = oldRule("201503873", 440L, "CU", 3);
    PriceRangeItem currentA = currentFactorItem(
        441L, 440L, "201503873", "S000841", "供应商A",
        "57001", "60000", "0.9500",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
    PriceRangeItem currentB = currentFactorItem(
        442L, 440L, "201503873", "S001289", "供应商B",
        "57001", "60000", "1.1000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(currentRule));
    when(itemMapper.selectList(any())).thenReturn(List.of(currentA, currentB));
    PriceRangeItemImportRequest.PriceRangeItemImportRow sameA = datedSupplierRow(
        "201503873", "57001", "60000", "0.950000", "S000841", "供应商A",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

    List<PriceRangeItem> imported = service.importItems(factorRequest("CU", sameA));

    assertThat(imported).containsExactlyInAnyOrder(currentA, currentB);
    verify(factorRuleMapper, never()).insert(any(PriceRangeFactorRule.class));
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
    verify(factorRuleMapper, never()).updateById(any(PriceRangeFactorRule.class));
    verify(itemMapper, never()).updateById(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-08 历史名称身份唯一匹配时升级为供应商代码且不复制旧身份")
  void factorReimportUpgradesUniqueNameIdentityToCode() {
    PriceRangeFactorRule oldRule = oldRule("201503873", 450L, "CU", 1);
    PriceRangeItem nameOnly = currentFactorItem(
        451L, 450L, "201503873", null, "吉林省 合信汽配有限公司",
        "57001", "60000", "1.0000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(oldRule));
    when(itemMapper.selectList(any())).thenReturn(List.of(nameOnly));
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(452L);
      return 1;
    });
    PriceRangeItemImportRequest.PriceRangeItemImportRow coded = datedSupplierRow(
        "201503873", "57001", "60000", "1.0500", "S001289", "吉林省合信汽配有限公司",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

    List<PriceRangeItem> imported = service.importItems(factorRequest("CU", coded));

    assertThat(imported).singleElement().satisfies(item -> {
      assertThat(item.getSupplierCode()).isEqualTo("S001289");
      assertThat(item.getSupplierName()).isEqualTo("吉林省合信汽配有限公司");
    });
    verify(itemMapper).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-08 名称对应多个历史供应商身份时阻断代码升级")
  void factorReimportRejectsAmbiguousNameIdentityUpgrade() {
    PriceRangeFactorRule oldRule = oldRule("201503873", 460L, "CU", 1);
    PriceRangeItem nameOnly = currentFactorItem(
        461L, 460L, "201503873", null, "同名供应商",
        "57001", "60000", "1.0000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    PriceRangeItem codedHistory = currentFactorItem(
        462L, 460L, "201503873", "OLD-CODE", "同名供应商",
        "57001", "60000", "1.1000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(oldRule));
    when(itemMapper.selectList(any())).thenReturn(List.of(nameOnly, codedHistory));
    PriceRangeItemImportRequest.PriceRangeItemImportRow incoming = datedSupplierRow(
        "201503873", "57001", "60000", "1.0500", "NEW-CODE", "同名供应商",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", incoming)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("供应商名称身份不唯一")
        .hasMessageContaining("201503873")
        .hasMessageContaining("同名供应商");
    verify(factorRuleMapper, never()).insert(any(PriceRangeFactorRule.class));
    verify(itemMapper, never()).insert(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-08 生成新版本只转历史不删除旧规则和旧明细")
  void factorReimportNeverDeletesHistoricalRows() {
    PriceRangeFactorRule oldRule = oldRule("201503873", 470L, "CU", 1);
    PriceRangeItem oldItem = currentFactorItem(
        471L, 470L, "201503873", "S000841", "供应商A",
        "57001", "60000", "0.9000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    when(factorRuleMapper.selectList(any())).thenReturn(List.of(oldRule));
    when(itemMapper.selectList(any())).thenReturn(List.of(oldItem));
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(472L);
      return 1;
    });
    PriceRangeItemImportRequest.PriceRangeItemImportRow changed = datedSupplierRow(
        "201503873", "57001", "60000", "0.9500", "S000841", "供应商A",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

    service.importItems(factorRequest("CU", changed));

    verify(factorRuleMapper, never()).deleteById(any(Long.class));
    verify(itemMapper, never()).deleteById(any(Long.class));
    verify(factorRuleMapper).updateById(oldRule);
    verify(itemMapper).updateById(oldItem);
  }

  @Test
  @DisplayName("RPI1-08 多物料中途失败时不提前失效已处理物料的旧版本")
  void factorBatchDefersOldVersionExpirationUntilAllNewRowsAreInserted() {
    PriceRangeFactorRule oldRuleA = oldRule("MAT-A", 480L, "CU", 1);
    PriceRangeFactorRule oldRuleB = oldRule("MAT-B", 490L, "CU", 1);
    PriceRangeItem oldA = currentFactorItem(
        481L, 480L, "MAT-A", "SUP-A", "供应商A",
        "57001", "60000", "0.9000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    PriceRangeItem oldB = currentFactorItem(
        491L, 490L, "MAT-B", "SUP-B", "供应商B",
        "57001", "60000", "1.0000",
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    when(factorRuleMapper.selectList(any()))
        .thenReturn(List.of(oldRuleA), List.of(oldRuleB));
    when(itemMapper.selectList(any()))
        .thenReturn(List.of(oldA), List.of(oldB));
    long[] nextRuleId = {482L};
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(nextRuleId[0]);
      nextRuleId[0] = 492L;
      return 1;
    });
    when(itemMapper.insert(any(PriceRangeItem.class))).thenAnswer(invocation -> {
      PriceRangeItem item = invocation.getArgument(0);
      if ("MAT-B".equals(item.getMaterialCode())) {
        throw new IllegalStateException("second material failed");
      }
      return 1;
    });
    PriceRangeItemImportRequest.PriceRangeItemImportRow changedA = datedSupplierRow(
        "MAT-A", "57001", "60000", "0.9500", "SUP-A", "供应商A",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
    PriceRangeItemImportRequest.PriceRangeItemImportRow changedB = datedSupplierRow(
        "MAT-B", "57001", "60000", "1.0500", "SUP-B", "供应商B",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", changedA, changedB)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("second material failed");

    assertThat(oldRuleA.getCurrentFlag()).isOne();
    assertThat(oldRuleB.getCurrentFlag()).isOne();
    assertThat(oldA.getCurrentFlag()).isOne();
    assertThat(oldB.getCurrentFlag()).isOne();
    verify(factorRuleMapper, never()).updateById(any(PriceRangeFactorRule.class));
    verify(itemMapper, never()).updateById(any(PriceRangeItem.class));
  }

  @Test
  @DisplayName("RPI1-08 行情区间缺少供应商生效日期时整批阻断")
  void factorImportRejectsMissingSupplierEffectiveFrom() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow missingDate = datedSupplierRow(
        "201503873", "57001", "60000", "0.9500", "S000841", "供应商A",
        null, LocalDate.of(2026, 7, 31));

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", missingDate)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("缺少生效日期")
        .hasMessageContaining("201503873");
    verifyNoInteractions(factorRuleMapper);
    verifyNoInteractions(itemMapper);
  }

  @Test
  @DisplayName("RPI1-08 供应商失效日期早于生效日期时整批阻断")
  void factorImportRejectsReversedSupplierValidityDates() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow reversed = datedSupplierRow(
        "201503873", "57001", "60000", "0.9500", "S000841", "供应商A",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 6, 30));

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", reversed)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("失效日期早于生效日期")
        .hasMessageContaining("201503873");
    verifyNoInteractions(factorRuleMapper);
    verifyNoInteractions(itemMapper);
  }

  @Test
  @DisplayName("RPI1-08 同一供应商各区间有效期不一致时整批阻断")
  void factorImportRejectsInconsistentDatesWithinSupplier() {
    PriceRangeItemImportRequest.PriceRangeItemImportRow first = datedSupplierRow(
        "201503873", "57001", "60000", "0.9500", "S000841", "供应商A",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31));
    PriceRangeItemImportRequest.PriceRangeItemImportRow second = datedSupplierRow(
        "201503873", "60001", "63000", "0.9600", "S000841", "供应商A",
        LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31));

    assertThatThrownBy(() -> service.importItems(factorRequest("CU", first, second)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("有效期不一致")
        .hasMessageContaining("CODE:S000841");
    verifyNoInteractions(factorRuleMapper);
    verifyNoInteractions(itemMapper);
  }

  private static PriceRangeItemImportRequest factorRequest(
      String factorCode,
      PriceRangeItemImportRequest.PriceRangeItemImportRow... rows) {
    PriceRangeItemImportRequest request = new PriceRangeItemImportRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setRangeBasis("FACTOR");
    request.setFactorCode(factorCode);
    request.setFactorName("电解铜");
    request.setFactorUnit("元/吨");
    request.setPriceUnit("元/米");
    request.setSourceFile("range-price.xlsx");
    request.setSourceSheet("区间铜价");
    request.setImportBatchNo("BATCH-CU-001");
    request.setRows(List.of(rows));
    return request;
  }

  private static PriceRangeItemImportRequest.PriceRangeItemImportRow row(
      String materialCode,
      String rangeLow,
      String rangeHigh,
      String priceExclTax) {
    PriceRangeItemImportRequest.PriceRangeItemImportRow row =
        new PriceRangeItemImportRequest.PriceRangeItemImportRow();
    row.setMaterialCode(materialCode);
    row.setMaterialName("测试物料");
    row.setSpecModel("SPEC");
    row.setRangeLow(new BigDecimal(rangeLow));
    row.setRangeHigh(new BigDecimal(rangeHigh));
    if (priceExclTax != null) {
      row.setPriceExclTax(new BigDecimal(priceExclTax));
    }
    row.setEffectiveFrom(LocalDate.of(2026, 7, 1));
    return row;
  }

  private static PriceRangeItemImportRequest.PriceRangeItemImportRow supplierRow(
      String materialCode,
      String rangeLow,
      String rangeHigh,
      String priceExclTax,
      String supplierCode,
      String supplierName) {
    PriceRangeItemImportRequest.PriceRangeItemImportRow row =
        row(materialCode, rangeLow, rangeHigh, priceExclTax);
    row.setSupplierCode(supplierCode);
    row.setSupplierName(supplierName);
    return row;
  }

  private static PriceRangeItemImportRequest.PriceRangeItemImportRow datedSupplierRow(
      String materialCode,
      String rangeLow,
      String rangeHigh,
      String priceExclTax,
      String supplierCode,
      String supplierName,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    PriceRangeItemImportRequest.PriceRangeItemImportRow row =
        supplierRow(materialCode, rangeLow, rangeHigh, priceExclTax, supplierCode, supplierName);
    row.setEffectiveFrom(effectiveFrom);
    row.setEffectiveTo(effectiveTo);
    return row;
  }

  private void prepareFactorRuleInsert(Long factorRuleId) {
    when(factorRuleMapper.selectList(any())).thenReturn(List.of());
    when(factorRuleMapper.insert(any(PriceRangeFactorRule.class))).thenAnswer(invocation -> {
      PriceRangeFactorRule rule = invocation.getArgument(0);
      rule.setId(factorRuleId);
      return 1;
    });
  }

  private static PriceRangeFactorRule oldRule(Long id, String factorCode, int versionNo) {
    return oldRule("201850160", id, factorCode, versionNo);
  }

  private static PriceRangeFactorRule oldRule(
      String materialCode,
      Long id,
      String factorCode,
      int versionNo) {
    PriceRangeFactorRule rule = new PriceRangeFactorRule();
    rule.setId(id);
    rule.setBusinessUnitType("COMMERCIAL");
    rule.setMaterialCode(materialCode);
    rule.setFactorCode(factorCode);
    rule.setVersionNo(versionNo);
    rule.setCurrentFlag(1);
    return rule;
  }

  private static PriceRangeItem oldItem(Long id, Long factorRuleId) {
    PriceRangeItem item = new PriceRangeItem();
    item.setId(id);
    item.setRangeBasis("FACTOR");
    item.setFactorRuleId(factorRuleId);
    item.setCurrentFlag(1);
    return item;
  }

  private static PriceRangeItem currentFactorItem(
      Long id,
      Long factorRuleId,
      String materialCode,
      String supplierCode,
      String supplierName,
      String rangeLow,
      String rangeHigh,
      String priceExclTax,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    PriceRangeItem item = oldItem(id, factorRuleId);
    item.setBusinessUnitType("COMMERCIAL");
    item.setMaterialCode(materialCode);
    item.setMaterialName("测试物料");
    item.setSupplierCode(supplierCode);
    item.setSupplierName(supplierName);
    item.setFactorCode("CU");
    item.setRangeLow(new BigDecimal(rangeLow));
    item.setRangeHigh(new BigDecimal(rangeHigh));
    item.setPriceExclTax(new BigDecimal(priceExclTax));
    item.setTaxIncluded(1);
    item.setEffectiveFrom(effectiveFrom);
    item.setEffectiveTo(effectiveTo);
    item.setImportBatchNo("OLD-BATCH");
    return item;
  }
}
