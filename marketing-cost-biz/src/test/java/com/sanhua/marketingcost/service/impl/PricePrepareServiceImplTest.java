package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.dto.priceprepare.MakePartPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PackageComponentPricePrepareResult;
import com.sanhua.marketingcost.service.MakePartPricePrepareStrategy;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.PackageComponentPricePrepareStrategy;
import com.sanhua.marketingcost.service.PricePrepareBomItemLoader;
import com.sanhua.marketingcost.service.PricePrepareItemClassifier;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

class PricePrepareServiceImplTest {

  private static final String CURRENT_PERIOD = CostPricingPeriodUtils.currentPricingMonth();

  private PricePrepareBatchMapper batchMapper;
  private PricePrepareItemMapper itemMapper;
  private PricePrepareGapMapper gapMapper;
  private PricePrepareBomItemLoader bomItemLoader;
  private PricePrepareItemClassifier itemClassifier;
  private NormalMaterialPricePrepareStrategy normalMaterialPricePrepareStrategy;
  private PackageComponentPricePrepareStrategy packageComponentPricePrepareStrategy;
  private MakePartPricePrepareStrategy makePartPricePrepareStrategy;
  private PricePrepareServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PricePrepareBatch.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareItem.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareGap.class);
  }

  @BeforeEach
  void setUp() {
    SecurityContextHolder.clearContext();
    batchMapper = mock(PricePrepareBatchMapper.class);
    itemMapper = mock(PricePrepareItemMapper.class);
    gapMapper = mock(PricePrepareGapMapper.class);
    bomItemLoader = mock(PricePrepareBomItemLoader.class);
    itemClassifier = mock(PricePrepareItemClassifier.class);
    normalMaterialPricePrepareStrategy = mock(NormalMaterialPricePrepareStrategy.class);
    packageComponentPricePrepareStrategy = mock(PackageComponentPricePrepareStrategy.class);
    makePartPricePrepareStrategy = mock(MakePartPricePrepareStrategy.class);
    service = new PricePrepareServiceImpl(
        batchMapper,
        itemMapper,
        gapMapper,
        bomItemLoader,
        itemClassifier,
        normalMaterialPricePrepareStrategy,
        packageComponentPricePrepareStrategy,
        makePartPricePrepareStrategy);
    when(packageComponentPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(PackageComponentPricePrepareResult.ready(
            new BigDecimal("9.00"), new BigDecimal("22.500"), 900L, "包装组件价格准备完成"));
    when(makePartPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(MakePartPricePrepareResult.ready(
            new BigDecimal("18.60"), new BigDecimal("46.500"), 800L, "自制件价格准备完成"));
  }

  @Test
  @DisplayName("生成骨架：OA 单号必填")
  void generateRequiresOaNo() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();

    assertThatThrownBy(() -> service.generate(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("oaNo");
  }

  @Test
  @DisplayName("事务边界：价格准备编排不套顶层事务，避免内部事务异常被吞后 rollback-only")
  void generateDoesNotUseTopLevelTransaction() throws Exception {
    assertThat(PricePrepareServiceImpl.class.getDeclaredMethod(
            "generate", PricePrepareGenerateRequest.class)
        .getAnnotation(Transactional.class))
        .isNull();
  }

  @Test
  @DisplayName("生成骨架：期间为空时使用系统当前年月")
  void generateUsesCurrentPeriodWhenBlank() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo(" OA-001 ");
    when(bomItemLoader.loadByOaNo("OA-001")).thenReturn(List.of());

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getPeriodMonth()).isEqualTo(CURRENT_PERIOD);
    assertThat(result.getPrepareNo()).startsWith("PPR-");
    verify(batchMapper).insert(any(PricePrepareBatch.class));
    verify(batchMapper).updateById(any(PricePrepareBatch.class));
  }

  @Test
  @DisplayName("生成骨架：非当前核算月直接拒绝")
  void generateRejectsNonCurrentPeriod() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-001");
    request.setPeriodMonth("2026-05");

    assertThatThrownBy(() -> service.generate(request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("当前重算核算月为", "请按当前月执行价格准备");
  }

  @Test
  @DisplayName("生成骨架：BOM目的固定为主制造，忽略调用方传值")
  void generateForcesMainManufacturingPurpose() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-001");
    request.setPeriodMonth(CURRENT_PERIOD);
    request.setBomPurpose("工程BOM");
    request.setSourceType(" U9 ");
    BomCostingRow row = bomRow(1L, "1079900000536", "MAT-001", "采购件");
    PricePreparePlanItem planItem = planItem(row, "NORMAL", "READY");
    when(bomItemLoader.loadByOaNo("OA-001")).thenReturn(List.of(row));
    when(itemClassifier.classify(List.of(row))).thenReturn(List.of(planItem));
    when(normalMaterialPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.eq("OA-001"),
            org.mockito.ArgumentMatchers.eq("COMMERCIAL"),
            org.mockito.ArgumentMatchers.eq(CURRENT_PERIOD),
            org.mockito.ArgumentMatchers.eq(planItem)))
        .thenReturn(NormalMaterialPricePrepareResult.ready(
            new BigDecimal("12.30"), new BigDecimal("30.750"), "固定采购价", "FIXED_PRICE", null, "普通料号价格准备完成"));
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("tester", "N/A");
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getBomPurpose()).isEqualTo("主制造");
    assertThat(result.getSourceType()).isEqualTo("U9");
    assertThat(result.getStatus()).isEqualTo("SUCCESS");
    assertThat(result.getTotalCount()).isEqualTo(1);
    assertThat(result.getPrepareNo()).startsWith("PPR-");
    assertThat(result.getMessage()).contains("价格准备");
    ArgumentCaptor<PricePrepareBatch> batchCaptor = ArgumentCaptor.forClass(PricePrepareBatch.class);
    verify(batchMapper).insert(batchCaptor.capture());
    verify(batchMapper).updateById(batchCaptor.getValue());
    assertThat(batchCaptor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    ArgumentCaptor<PricePrepareItem> itemCaptor = ArgumentCaptor.forClass(PricePrepareItem.class);
    verify(itemMapper).insert(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getPrepareNo()).isEqualTo(result.getPrepareNo());
    assertThat(itemCaptor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(itemCaptor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
  }

  @Test
  @DisplayName("生成骨架：OA 无 BOM 结算明细时写缺口")
  void generateWritesGapWhenNoBomRows() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-NO-BOM");
    when(bomItemLoader.loadByOaNo("OA-NO-BOM")).thenReturn(List.of());

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("PARTIAL");
    assertThat(result.getGapCount()).isEqualTo(1);
    assertThat(result.getMessage()).contains("无BOM结算明细");
    ArgumentCaptor<PricePrepareGap> gapCaptor = ArgumentCaptor.forClass(PricePrepareGap.class);
    verify(gapMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_STRUCTURE");
    assertThat(gapCaptor.getValue().getSourceTable()).isEqualTo("lp_bom_costing_row");
  }

  @Test
  @DisplayName("统一编排：BOM 读取异常时返回 FAILED 当前状态")
  void generateMarksBatchFailedWhenBomLoadFails() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-BOM-FAIL");
    request.setPeriodMonth(CURRENT_PERIOD);
    when(bomItemLoader.loadByOaNo("OA-BOM-FAIL"))
        .thenThrow(new IllegalStateException("U9明细读取失败"));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("FAILED");
    assertThat(result.getTotalCount()).isZero();
    assertThat(result.getSuccessCount()).isZero();
    assertThat(result.getGapCount()).isZero();
    assertThat(result.getMessage()).contains("读取BOM结算明细失败");
    verify(itemClassifier, org.mockito.Mockito.never()).classify(org.mockito.ArgumentMatchers.any());
    ArgumentCaptor<PricePrepareBatch> batchCaptor = ArgumentCaptor.forClass(PricePrepareBatch.class);
    verify(batchMapper).insert(batchCaptor.capture());
    verify(batchMapper).updateById(batchCaptor.getValue());
    assertThat(batchCaptor.getValue().getStatus()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("统一编排：分类关键流程异常时落 FAILED 批次")
  void generateMarksBatchFailedWhenClassifierFails() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-CLASSIFY-FAIL");
    request.setPeriodMonth(CURRENT_PERIOD);
    BomCostingRow row = bomRow(1L, "TOP-A", "MAT-A", "采购件");
    when(bomItemLoader.loadByOaNo("OA-CLASSIFY-FAIL")).thenReturn(List.of(row));
    when(itemClassifier.classify(List.of(row)))
        .thenThrow(new IllegalStateException("料号分类失败"));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("FAILED");
    assertThat(result.getMessage()).contains("价格准备分类失败");
    verify(itemMapper, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.any(PricePrepareItem.class));
    verify(gapMapper, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.any(PricePrepareGap.class));
  }

  @Test
  @DisplayName("统一编排：同 OA 同期间重跑复用当前价格准备批次")
  void generateSameOaAndPeriodReusesCurrentBatch() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-RERUN");
    request.setPeriodMonth(CURRENT_PERIOD);
    PricePrepareBatch existing = new PricePrepareBatch();
    existing.setId(10L);
    existing.setPrepareNo("PPR-EXISTING");
    existing.setOaNo("OA-RERUN");
    existing.setPeriodMonth(CURRENT_PERIOD);
    when(batchMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(existing);
    when(bomItemLoader.loadByOaNo("OA-RERUN")).thenReturn(List.of());

    PricePrepareGenerateResult first = service.generate(request);
    PricePrepareGenerateResult second = service.generate(request);

    assertThat(first.getPrepareNo()).isEqualTo("PPR-EXISTING");
    assertThat(second.getPrepareNo()).isEqualTo("PPR-EXISTING");
    verify(batchMapper, org.mockito.Mockito.never()).insert(any(PricePrepareBatch.class));
    verify(batchMapper, org.mockito.Mockito.times(4)).updateById(existing);
    ArgumentCaptor<PricePrepareGap> gapCaptor = ArgumentCaptor.forClass(PricePrepareGap.class);
    verify(gapMapper, org.mockito.Mockito.times(2)).insert(gapCaptor.capture());
    assertThat(gapCaptor.getAllValues())
        .extracting(PricePrepareGap::getPrepareNo)
        .containsExactly("PPR-EXISTING", "PPR-EXISTING");
  }

  @Test
  @DisplayName("生成骨架：同一个 OA 多个顶级成品分别写入待处理明细")
  void generateWritesItemsForMultipleTopProducts() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-MULTI");
    BomCostingRow row1 = bomRow(1L, "TOP-A", "MAT-A", "采购件");
    BomCostingRow row2 = bomRow(2L, "TOP-B", "PKG-B", "虚拟");
    PricePreparePlanItem item1 = planItem(row1, "NORMAL", "READY");
    PricePreparePlanItem item2 = planItem(row2, "PACKAGE_COMPONENT", "READY");
    when(bomItemLoader.loadByOaNo("OA-MULTI")).thenReturn(List.of(row1, row2));
    when(itemClassifier.classify(List.of(row1, row2))).thenReturn(List.of(item1, item2));
    when(normalMaterialPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.eq("OA-MULTI"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(item1)))
        .thenReturn(NormalMaterialPricePrepareResult.ready(
            new BigDecimal("1.20"), new BigDecimal("3.000"), "固定采购价", "FIXED_PRICE", null, "普通料号价格准备完成"));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getTotalCount()).isEqualTo(2);
    assertThat(result.getSuccessCount()).isEqualTo(2);
    ArgumentCaptor<PricePrepareItem> itemCaptor = ArgumentCaptor.forClass(PricePrepareItem.class);
    verify(itemMapper, org.mockito.Mockito.times(2)).insert(itemCaptor.capture());
    assertThat(itemCaptor.getAllValues())
        .extracting(PricePrepareItem::getTopProductCode)
        .containsExactly("TOP-A", "TOP-B");
    assertThat(itemCaptor.getAllValues())
        .filteredOn(item -> "PACKAGE_COMPONENT".equals(item.getItemType()))
        .first()
        .extracting(PricePrepareItem::getTopProductCode)
        .isEqualTo("TOP-B");
  }

  @Test
  @DisplayName("行级生成：只清理并读取指定 OA+成品范围")
  void generateByTopProductsKeepsOtherTopProductsUntouched() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-LINE");
    request.setTopProductCodes(List.of("TOP-A", "TOP-A", " TOP-B "));
    request.setPeriodMonth(CURRENT_PERIOD);
    BomCostingRow row = bomRow(1L, "TOP-A", "MAT-A", "采购件");
    PricePreparePlanItem planItem = planItem(row, "NORMAL", "READY");
    when(bomItemLoader.loadByOaNoAndTopProducts("OA-LINE", List.of("TOP-A", "TOP-B")))
        .thenReturn(List.of(row));
    when(itemClassifier.classify(List.of(row))).thenReturn(List.of(planItem));
    when(normalMaterialPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.eq("OA-LINE"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(CURRENT_PERIOD),
            org.mockito.ArgumentMatchers.eq(planItem)))
        .thenReturn(NormalMaterialPricePrepareResult.ready(
            new BigDecimal("1.20"), new BigDecimal("3.000"), "固定采购价", "FIXED_PRICE", null, "普通料号价格准备完成"));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("SUCCESS");
    verify(bomItemLoader)
        .loadByOaNoAndTopProducts("OA-LINE", List.of("TOP-A", "TOP-B"));
    verify(bomItemLoader, org.mockito.Mockito.never()).loadByOaNo("OA-LINE");
    ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PricePrepareItem>>
        itemDeleteCaptor = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
    ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PricePrepareGap>>
        gapDeleteCaptor = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
    verify(itemMapper).delete(itemDeleteCaptor.capture());
    verify(gapMapper).delete(gapDeleteCaptor.capture());
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
                    itemDeleteCaptor.getValue())
                .getSqlSegment())
        .contains("oa_no", "top_product_code");
    assertThat(itemDeleteCaptor.getValue().getParamNameValuePairs().values())
        .contains("OA-LINE", "TOP-A", "TOP-B");
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
                    gapDeleteCaptor.getValue())
                .getSqlSegment())
        .contains("oa_no", "top_product_code");
  }

  @Test
  @DisplayName("统一编排：普通、包装、自制件全 READY 时当前状态 SUCCESS")
  void generateAllStrategySuccessBatch() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-ALL-SUCCESS");
    request.setPeriodMonth(CURRENT_PERIOD);
    BomCostingRow normalRow = bomRow(1L, "TOP-A", "MAT-A", "采购件");
    BomCostingRow packageRow = bomRow(2L, "TOP-A", "PKG-A", "虚拟");
    BomCostingRow makeRow = bomRow(3L, "TOP-A", "MAKE-A", "制造件");
    PricePreparePlanItem normalItem = planItem(normalRow, "NORMAL", "READY");
    PricePreparePlanItem packageItem = planItem(packageRow, "PACKAGE_COMPONENT", "READY");
    PricePreparePlanItem makeItem = planItem(makeRow, "MAKE_PART", "READY");
    when(bomItemLoader.loadByOaNo("OA-ALL-SUCCESS"))
        .thenReturn(List.of(normalRow, packageRow, makeRow));
    when(itemClassifier.classify(List.of(normalRow, packageRow, makeRow)))
        .thenReturn(List.of(normalItem, packageItem, makeItem));
    when(normalMaterialPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.eq("OA-ALL-SUCCESS"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(CURRENT_PERIOD),
            org.mockito.ArgumentMatchers.eq(normalItem)))
        .thenReturn(NormalMaterialPricePrepareResult.ready(
            new BigDecimal("1.20"), new BigDecimal("3.000"), "固定采购价", "FIXED_PRICE", null, "普通料号价格准备完成"));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("SUCCESS");
    assertThat(result.getTotalCount()).isEqualTo(3);
    assertThat(result.getSuccessCount()).isEqualTo(3);
    assertThat(result.getWarningCount()).isZero();
    assertThat(result.getGapCount()).isZero();
    ArgumentCaptor<PricePrepareItem> itemCaptor = ArgumentCaptor.forClass(PricePrepareItem.class);
    verify(itemMapper, org.mockito.Mockito.times(3)).insert(itemCaptor.capture());
    assertThat(itemCaptor.getAllValues())
        .extracting(PricePrepareItem::getItemType)
        .containsExactly("NORMAL", "PACKAGE_COMPONENT", "MAKE_PART");
    verify(gapMapper, org.mockito.Mockito.never()).insert(org.mockito.ArgumentMatchers.any(PricePrepareGap.class));
  }

  @Test
  @DisplayName("生成骨架：缺主档分类写明细和缺口")
  void generateWritesMissingMasterItemAndGap() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-MISSING");
    BomCostingRow row = bomRow(1L, "TOP-A", "MISS-001", null);
    row.setMaterialName(null);
    PricePreparePlanItem planItem = planItem(row, "NORMAL", "MISSING_MASTER");
    planItem.setMessage("缺料品主档，无法判断料号类型");
    when(bomItemLoader.loadByOaNo("OA-MISSING")).thenReturn(List.of(row));
    when(itemClassifier.classify(List.of(row))).thenReturn(List.of(planItem));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("PARTIAL");
    assertThat(result.getGapCount()).isEqualTo(1);
    verify(itemMapper).insert(any(PricePrepareItem.class));
    ArgumentCaptor<PricePrepareGap> gapCaptor = ArgumentCaptor.forClass(PricePrepareGap.class);
    verify(gapMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_MASTER");
    assertThat(gapCaptor.getValue().getGapMaterialCode()).isEqualTo("MISS-001");
  }

  @Test
  @DisplayName("普通料号策略：缺价只写缺口，不阻断其他明细")
  void normalMissingPriceDoesNotBlockWholeBatch() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-NORMAL-GAP");
    request.setPeriodMonth(CURRENT_PERIOD);
    BomCostingRow row1 = bomRow(1L, "TOP-A", "MAT-MISS", "采购件");
    BomCostingRow row2 = bomRow(2L, "TOP-A", "PKG-001", "虚拟");
    BomCostingRow row3 = bomRow(3L, "TOP-A", "MAKE-001", "制造件");
    PricePreparePlanItem item1 = planItem(row1, "NORMAL", "READY");
    PricePreparePlanItem item2 = planItem(row2, "PACKAGE_COMPONENT", "READY");
    PricePreparePlanItem item3 = planItem(row3, "MAKE_PART", "READY");
    when(bomItemLoader.loadByOaNo("OA-NORMAL-GAP")).thenReturn(List.of(row1, row2, row3));
    when(itemClassifier.classify(List.of(row1, row2, row3))).thenReturn(List.of(item1, item2, item3));
    when(normalMaterialPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.eq("OA-NORMAL-GAP"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(CURRENT_PERIOD),
            org.mockito.ArgumentMatchers.eq(item1)))
        .thenReturn(NormalMaterialPricePrepareResult.gap(
            "MISSING_PRICE", "MISSING_PRICE", "ERROR", "PriceResolver", "路由=[FIXED] 但桶内无该料号"));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("PARTIAL");
    assertThat(result.getTotalCount()).isEqualTo(3);
    assertThat(result.getSuccessCount()).isEqualTo(2);
    assertThat(result.getGapCount()).isEqualTo(1);
    ArgumentCaptor<PricePrepareItem> itemCaptor = ArgumentCaptor.forClass(PricePrepareItem.class);
    verify(itemMapper, org.mockito.Mockito.times(3)).insert(itemCaptor.capture());
    assertThat(itemCaptor.getAllValues())
        .extracting(PricePrepareItem::getStatus)
        .containsExactly("MISSING_PRICE", "READY", "READY");
    ArgumentCaptor<PricePrepareGap> gapCaptor = ArgumentCaptor.forClass(PricePrepareGap.class);
    verify(gapMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(gapCaptor.getValue().getOaPushStatus()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("包装组件策略：包装组件不走普通父料号价格，并保留顶层产品上下文")
  void packageComponentUsesPackageStrategyInsteadOfNormalRoute() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-PKG");
    request.setPeriodMonth(CURRENT_PERIOD);
    BomCostingRow row = bomRow(1L, "TOP-PKG", "PKG-001", "虚拟");
    PricePreparePlanItem planItem = planItem(row, "PACKAGE_COMPONENT", "READY");
    when(bomItemLoader.loadByOaNo("OA-PKG")).thenReturn(List.of(row));
    when(itemClassifier.classify(List.of(row))).thenReturn(List.of(planItem));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("SUCCESS");
    verify(normalMaterialPricePrepareStrategy, org.mockito.Mockito.never())
        .prepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    ArgumentCaptor<PricePrepareItem> itemCaptor = ArgumentCaptor.forClass(PricePrepareItem.class);
    verify(itemMapper).insert(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getStatus()).isEqualTo("READY");
    assertThat(itemCaptor.getValue().getResultRefType()).isEqualTo("PACKAGE_PRICE");
    assertThat(itemCaptor.getValue().getResultRefId()).isEqualTo(900L);
    assertThat(itemCaptor.getValue().getTopProductCode()).isEqualTo("TOP-PKG");
    verify(packageComponentPricePrepareStrategy)
        .prepare(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("OA-PKG"),
            org.mockito.ArgumentMatchers.eq(CURRENT_PERIOD),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("主制造"),
            org.mockito.ArgumentMatchers.eq("U9"),
            org.mockito.ArgumentMatchers.eq(planItem));
  }

  @Test
  @DisplayName("包装组件策略：缺结构写 MISSING_STRUCTURE 缺口")
  void packageComponentMissingStructureWritesGap() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-PKG-STRUCTURE");
    request.setPeriodMonth(CURRENT_PERIOD);
    BomCostingRow row = bomRow(1L, "TOP-PKG", "PKG-001", "虚拟");
    PricePreparePlanItem planItem = planItem(row, "PACKAGE_COMPONENT", "READY");
    when(bomItemLoader.loadByOaNo("OA-PKG-STRUCTURE")).thenReturn(List.of(row));
    when(itemClassifier.classify(List.of(row))).thenReturn(List.of(planItem));
    when(packageComponentPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("OA-PKG-STRUCTURE"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(planItem)))
        .thenReturn(PackageComponentPricePrepareResult.notReady(
            "MISSING_STRUCTURE",
            "包装组件缺结构",
            List.of(new PackageComponentPricePrepareResult.Gap(
                "MISSING_STRUCTURE", "PKG-001", "PackageComponentSnapshotService", "包装组件缺结构"))));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("PARTIAL");
    assertThat(result.getGapCount()).isEqualTo(1);
    ArgumentCaptor<PricePrepareGap> gapCaptor = ArgumentCaptor.forClass(PricePrepareGap.class);
    verify(gapMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_STRUCTURE");
    assertThat(gapCaptor.getValue().getMaterialCode()).isEqualTo("PKG-001");
    assertThat(gapCaptor.getValue().getGapMaterialCode()).isEqualTo("PKG-001");
  }

  @Test
  @DisplayName("包装组件策略：缺子件价写 MISSING_PRICE 缺口，gap_material_code 指向子件")
  void packageComponentMissingChildPriceWritesGap() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-PKG-PRICE");
    request.setPeriodMonth(CURRENT_PERIOD);
    BomCostingRow row = bomRow(1L, "TOP-PKG", "PKG-001", "虚拟");
    PricePreparePlanItem planItem = planItem(row, "PACKAGE_COMPONENT", "READY");
    when(bomItemLoader.loadByOaNo("OA-PKG-PRICE")).thenReturn(List.of(row));
    when(itemClassifier.classify(List.of(row))).thenReturn(List.of(planItem));
    when(packageComponentPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("OA-PKG-PRICE"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(planItem)))
        .thenReturn(PackageComponentPricePrepareResult.notReady(
            "MISSING_PRICE",
            "包装组件存在子件缺价",
            List.of(new PackageComponentPricePrepareResult.Gap(
                "MISSING_PRICE", "CHILD-001", "lp_package_component_price_detail", "子件缺价"))));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("PARTIAL");
    ArgumentCaptor<PricePrepareGap> gapCaptor = ArgumentCaptor.forClass(PricePrepareGap.class);
    verify(gapMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(gapCaptor.getValue().getMaterialCode()).isEqualTo("PKG-001");
    assertThat(gapCaptor.getValue().getGapMaterialCode()).isEqualTo("CHILD-001");
  }

  @Test
  @DisplayName("自制件策略：MAKE_PART 不走普通料号价格，并写入生成结果引用")
  void makePartUsesMakePartStrategyInsteadOfNormalRoute() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-MAKE");
    request.setPeriodMonth(CURRENT_PERIOD);
    BomCostingRow row = bomRow(1L, "TOP-MAKE", "MAKE-001", "制造件");
    PricePreparePlanItem planItem = planItem(row, "MAKE_PART", "READY");
    when(bomItemLoader.loadByOaNo("OA-MAKE")).thenReturn(List.of(row));
    when(itemClassifier.classify(List.of(row))).thenReturn(List.of(planItem));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("SUCCESS");
    verify(normalMaterialPricePrepareStrategy, org.mockito.Mockito.never())
        .prepare(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    ArgumentCaptor<PricePrepareItem> itemCaptor = ArgumentCaptor.forClass(PricePrepareItem.class);
    verify(itemMapper).insert(itemCaptor.capture());
    assertThat(itemCaptor.getValue().getStatus()).isEqualTo("READY");
    assertThat(itemCaptor.getValue().getResultRefType()).isEqualTo("MAKE_PART_PRICE");
    assertThat(itemCaptor.getValue().getResultRefId()).isEqualTo(800L);
    verify(makePartPricePrepareStrategy)
        .prepare(
            org.mockito.ArgumentMatchers.eq("OA-MAKE"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(CURRENT_PERIOD),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(planItem));
  }

  @Test
  @DisplayName("自制件策略：缺原材料或废料价写 MISSING_PRICE 缺口")
  void makePartMissingPriceWritesGap() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-MAKE-MISS");
    request.setPeriodMonth(CURRENT_PERIOD);
    BomCostingRow row = bomRow(1L, "TOP-MAKE", "MAKE-001", "制造件");
    PricePreparePlanItem planItem = planItem(row, "MAKE_PART", "READY");
    when(bomItemLoader.loadByOaNo("OA-MAKE-MISS")).thenReturn(List.of(row));
    when(itemClassifier.classify(List.of(row))).thenReturn(List.of(planItem));
    when(makePartPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.eq("OA-MAKE-MISS"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(CURRENT_PERIOD),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(planItem)))
        .thenReturn(MakePartPricePrepareResult.notReady(
            "MISSING_PRICE",
            "自制件价格生成结果存在缺口",
            List.of(new MakePartPricePrepareResult.Gap(
                "MISSING_PRICE", "SCRAP-001", "lp_make_part_price_gap_item", "缺废料价格"))));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("PARTIAL");
    assertThat(result.getGapCount()).isEqualTo(1);
    ArgumentCaptor<PricePrepareGap> gapCaptor = ArgumentCaptor.forClass(PricePrepareGap.class);
    verify(gapMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getItemType()).isEqualTo("MAKE_PART");
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(gapCaptor.getValue().getMaterialCode()).isEqualTo("MAKE-001");
    assertThat(gapCaptor.getValue().getGapMaterialCode()).isEqualTo("SCRAP-001");
    assertThat(gapCaptor.getValue().getOaPushStatus()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("统一编排：单个策略异常写 FAILED 明细，不影响其他行继续记录")
  void singleStrategyFailureDoesNotBlockOtherRows() {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo("OA-STRATEGY-FAIL");
    request.setPeriodMonth(CURRENT_PERIOD);
    BomCostingRow normalRow = bomRow(1L, "TOP-A", "MAT-A", "采购件");
    BomCostingRow packageRow = bomRow(2L, "TOP-A", "PKG-A", "虚拟");
    BomCostingRow makeRow = bomRow(3L, "TOP-A", "MAKE-A", "制造件");
    PricePreparePlanItem normalItem = planItem(normalRow, "NORMAL", "READY");
    PricePreparePlanItem packageItem = planItem(packageRow, "PACKAGE_COMPONENT", "READY");
    PricePreparePlanItem makeItem = planItem(makeRow, "MAKE_PART", "READY");
    when(bomItemLoader.loadByOaNo("OA-STRATEGY-FAIL"))
        .thenReturn(List.of(normalRow, packageRow, makeRow));
    when(itemClassifier.classify(List.of(normalRow, packageRow, makeRow)))
        .thenReturn(List.of(normalItem, packageItem, makeItem));
    when(normalMaterialPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.eq("OA-STRATEGY-FAIL"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(CURRENT_PERIOD),
            org.mockito.ArgumentMatchers.eq(normalItem)))
        .thenReturn(NormalMaterialPricePrepareResult.ready(
            new BigDecimal("1.20"), new BigDecimal("3.000"), "固定采购价", "FIXED_PRICE", null, "普通料号价格准备完成"));
    when(packageComponentPricePrepareStrategy.prepare(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("OA-STRATEGY-FAIL"),
            org.mockito.ArgumentMatchers.eq(CURRENT_PERIOD),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(packageItem)))
        .thenThrow(new IllegalStateException("包装生成失败"));

    PricePrepareGenerateResult result = service.generate(request);

    assertThat(result.getStatus()).isEqualTo("PARTIAL");
    assertThat(result.getTotalCount()).isEqualTo(3);
    assertThat(result.getSuccessCount()).isEqualTo(2);
    assertThat(result.getGapCount()).isEqualTo(1);
    ArgumentCaptor<PricePrepareItem> itemCaptor = ArgumentCaptor.forClass(PricePrepareItem.class);
    verify(itemMapper, org.mockito.Mockito.times(3)).insert(itemCaptor.capture());
    assertThat(itemCaptor.getAllValues())
        .extracting(PricePrepareItem::getStatus)
        .containsExactly("READY", "FAILED", "READY");
    ArgumentCaptor<PricePrepareGap> gapCaptor = ArgumentCaptor.forClass(PricePrepareGap.class);
    verify(gapMapper).insert(gapCaptor.capture());
    assertThat(gapCaptor.getValue().getGapType()).isEqualTo("MISSING_PRICE");
    assertThat(gapCaptor.getValue().getSourceTable()).isEqualTo("PackageComponentPricePrepareStrategy");
  }

  private BomCostingRow bomRow(Long id, String topProductCode, String materialCode, String shapeAttr) {
    BomCostingRow row = new BomCostingRow();
    row.setId(id);
    row.setOaNo("OA-001");
    row.setTopProductCode(topProductCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialCode + "-name");
    row.setShapeAttr(shapeAttr);
    row.setQtyPerTop(new BigDecimal("2.5"));
    return row;
  }

  private PricePreparePlanItem planItem(BomCostingRow row, String itemType, String status) {
    PricePreparePlanItem item = new PricePreparePlanItem();
    item.setBomRow(row);
    item.setBomRowId(row.getId());
    item.setTopProductCode(row.getTopProductCode());
    item.setMaterialCode(row.getMaterialCode());
    item.setMaterialName(row.getMaterialName());
    item.setItemType(itemType);
    item.setStatus(status);
    item.setMessage(itemType + " plan");
    return item;
  }
}
