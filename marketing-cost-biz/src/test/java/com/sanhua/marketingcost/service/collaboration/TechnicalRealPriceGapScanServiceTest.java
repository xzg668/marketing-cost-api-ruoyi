package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.QuoteBomPackageReferenceDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-13 完整候选BOM真实底层缺价扫描")
class TechnicalRealPriceGapScanServiceTest {

  private FormalBomReadService formalBomReadService;
  private QuoteBomSupplementDetailMapper supplementDetailMapper;
  private QuoteBomPackageReferenceDetailMapper packageDetailMapper;
  private NormalMaterialPricePrepareStrategy priceStrategy;
  private TechnicalRealPriceGapScanService service;

  @BeforeEach
  void setUp() {
    formalBomReadService = mock(FormalBomReadService.class);
    supplementDetailMapper = mock(QuoteBomSupplementDetailMapper.class);
    packageDetailMapper = mock(QuoteBomPackageReferenceDetailMapper.class);
    priceStrategy = mock(NormalMaterialPricePrepareStrategy.class);
    service = new TechnicalRealPriceGapScanService(
        formalBomReadService, supplementDetailMapper, packageDetailMapper, priceStrategy);
  }

  @Test
  @DisplayName("完整BOM按路径保留缺口、同路径同料号汇总用量并略过已有价格")
  void fullBomAggregatesSamePathButKeepsDifferentPaths() {
    QuoteCollaborationProductTask task = task("FULL_BOM");
    task.setSupplementVersionId(88L);
    task.setElectronicBomFingerprint("F".repeat(64));
    List<QuoteBomSupplementDetail> rows = List.of(
        supplement(1L, 0, null, "P-1", "产品", "制造件", "1", "/P-1/"),
        supplement(2L, 1, "P-1", "M-1", "制造组件一", "制造件", "1", "/P-1/M-1/"),
        supplement(3L, 2, "M-1", "RAW-1", "原材料铜管", "采购件", "2", "/P-1/M-1/RAW-1/"),
        supplement(4L, 2, "M-1", "RAW-1", "原材料铜管", "采购件", "3", "/P-1/M-1/RAW-1/"),
        supplement(5L, 1, "P-1", "M-2", "制造组件二", "制造件", "1", "/P-1/M-2/"),
        supplement(6L, 2, "M-2", "RAW-1", "原材料铜管", "采购件", "1", "/P-1/M-2/RAW-1/"),
        supplement(7L, 1, "P-1", "OK-1", "密封圈", "采购件", "2", "/P-1/OK-1/"));
    when(supplementDetailMapper.selectList(any(Wrapper.class))).thenReturn(rows);
    List<PricePreparePlanItem> checked = new ArrayList<>();
    when(priceStrategy.calculate(any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          PricePreparePlanItem plan = invocation.getArgument(5);
          checked.add(plan);
          if ("OK-1".equals(plan.getMaterialCode())) {
            return NormalMaterialPricePrepareResult.ready(
                BigDecimal.TEN, BigDecimal.valueOf(20), "固定价", "FIXED_PRICE", 10L, "命中");
          }
          return NormalMaterialPricePrepareResult.gap(
              "MISSING_PRICE", "MISSING_PRICE", "MAINTAIN_PRICE",
              "lp_price_linked_item", "当前组织和月份无有效联动价");
        });

    CollaborationPriceScanResult result = service.scan(task, owner());

    assertThat(result.status()).withFailMessage(result.message())
        .isEqualTo(CollaborationPriceScanResult.Status.GAPS);
    assertThat(result.checkedItemCount()).isEqualTo(3);
    assertThat(result.gaps()).hasSize(2);
    assertThat(result.gaps()).extracting(CollaborationPriceScanResult.PriceGap::bomPath)
        .containsExactly("/P-1/M-1/RAW-1/", "/P-1/M-2/RAW-1/");
    assertThat(result.gaps()).extracting(CollaborationPriceScanResult.PriceGap::bomQuantity)
        .containsExactly(new BigDecimal("5"), BigDecimal.ONE);
    assertThat(result.gaps()).allSatisfy(gap -> {
      assertThat(gap.materialCode()).isEqualTo("RAW-1");
      assertThat(gap.materialRole()).isEqualTo("RAW");
      assertThat(gap.accountingMonth()).isEqualTo("2026-08");
      assertThat(gap.applicableOrgCode()).isEqualTo("210");
      assertThat(gap.sourceType()).isEqualTo("ELECTRONIC_DRAWING_BOM");
      assertThat(gap.reason()).contains("lp_price_linked_item");
    });
    assertThat(checked).allSatisfy(plan -> {
      assertThat(plan.getBomRow().getPeriodMonth()).isEqualTo("2026-08");
      assertThat(plan.getBomRow().getPriceOrgCode()).isEqualTo("210");
      assertThat(plan.getBomRow().getMaterialOrganizationCode()).isEqualTo("COMMERCIAL");
    });
  }

  @Test
  @DisplayName("裸品用U9本体加包装草稿检查，只把新增无价包装底层物料列为缺口")
  void barePackageCombinesU9BodyAndPackageDraft() {
    QuoteCollaborationProductTask task = task("BARE_PACKAGE");
    task.setPackageReferenceId(99L);
    when(formalBomReadService.read(
        any(String.class), any(String.class), nullable(String.class),
        any(java.time.LocalDate.class),
        any(com.sanhua.marketingcost.dto.QuoteDataOrganization.class)))
        .thenReturn(new FormalBomReadResult("P-1", "2026-08", "主制造", true,
            List.of(
                sourceLine(1L, 0, null, "P-1", "产品", "制造件", "1", "/P-1/"),
                sourceLine(2L, 1, "P-1", "M-1", "制造组件", "制造件", "1", "/P-1/M-1/"),
                sourceLine(3L, 2, "M-1", "RAW-1", "原材料铜管", "采购件", "0.5", "/P-1/M-1/RAW-1/")),
            null));
    when(packageDetailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        packageLine(10L, 1, "P-1", "PACK-ROOT", "包装总成", "1", "/P-1/PACK-ROOT/"),
        packageLine(11L, 2, "PACK-ROOT", "TAPE-NEW", "封箱胶带", "2", "/P-1/PACK-ROOT/TAPE-NEW/")));
    when(priceStrategy.calculate(any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          PricePreparePlanItem plan = invocation.getArgument(5);
          return "RAW-1".equals(plan.getMaterialCode())
              ? NormalMaterialPricePrepareResult.ready(
                  BigDecimal.TEN, BigDecimal.valueOf(5), "联动价", "LINKED_PRICE", 20L, "命中")
              : NormalMaterialPricePrepareResult.gap(
                  "MISSING_PRICE_TYPE", "MISSING_PRICE_TYPE", "MAINTAIN_PRICE",
                  "lp_material_price_type", "新品包装材料尚无价格类型");
        });

    CollaborationPriceScanResult result = service.scan(task, owner());

    assertThat(result.status()).withFailMessage(result.message())
        .isEqualTo(CollaborationPriceScanResult.Status.GAPS);
    assertThat(result.checkedItemCount()).isEqualTo(2);
    assertThat(result.gaps()).singleElement().satisfies(gap -> {
      assertThat(gap.materialCode()).isEqualTo("TAPE-NEW");
      assertThat(gap.materialRole()).isEqualTo("PACKAGE_MATERIAL");
      assertThat(gap.bomPath()).isEqualTo("/P-1/PACK-ROOT/TAPE-NEW/");
      assertThat(gap.bomQuantity()).isEqualByComparingTo("2");
      assertThat(gap.bomUnit()).isEqualTo("卷");
      assertThat(gap.sourceType()).isEqualTo("PACKAGE_REFERENCE");
      assertThat(gap.sourceId()).isEqualTo(11L);
    });
  }

  @Test
  @DisplayName("价格服务失败只返回系统错误，不创建伪业务缺口")
  void priceServiceFailureIsErrorWithoutFakeGap() {
    QuoteCollaborationProductTask task = task("FULL_BOM");
    task.setSupplementVersionId(88L);
    task.setElectronicBomFingerprint("F".repeat(64));
    when(supplementDetailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        supplement(1L, 0, null, "P-1", "产品", "制造件", "1", "/P-1/"),
        supplement(2L, 1, "P-1", "RAW-1", "原材料铜管", "采购件", "1", "/P-1/RAW-1/")));
    when(priceStrategy.calculate(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("价格路由服务不可用"));

    CollaborationPriceScanResult result = service.scan(task, owner());

    assertThat(result.status()).isEqualTo(CollaborationPriceScanResult.Status.ERROR);
    assertThat(result.gaps()).isEmpty();
    assertThat(result.message()).contains("价格路由服务不可用");
  }

  private QuoteCollaborationProductTask task(String scope) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(10L);
    task.setProductCode("P-1");
    task.setProductName("产品");
    task.setPrimaryScope(scope);
    task.setAccountingMonth("2026-08");
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setPriceOrgCode("210");
    task.setMaterialOrgCode("COMMERCIAL");
    return task;
  }

  private QuoteCollaborationQuoteLink owner() {
    QuoteCollaborationQuoteLink owner = new QuoteCollaborationQuoteLink();
    owner.setOaNo("OA-13");
    owner.setOaFormItemId(30L);
    return owner;
  }

  private QuoteBomSupplementDetail supplement(
      Long id, int level, String parent, String code, String name, String nature,
      String qty, String path) {
    QuoteBomSupplementDetail row = new QuoteBomSupplementDetail();
    row.setId(id);
    row.setLineNo(id.intValue());
    row.setLevel(level);
    row.setParentCode(parent);
    row.setMaterialCode(code);
    row.setMaterialName(name);
    row.setMaterialSpec("S-" + code);
    row.setMaterialModel("M-" + code);
    row.setShapeAttr(nature);
    row.setSourceCategory(nature);
    row.setQtyPerTop(new BigDecimal(qty));
    row.setUnit("kg");
    row.setPath(path);
    return row;
  }

  private QuoteBomSourceLineDto sourceLine(
      Long id, int level, String parent, String code, String name, String nature,
      String qty, String path) {
    return new QuoteBomSourceLineDto(
        id, id.intValue(), level, "P-1", parent, code, name, "S-" + code, "M-" + code,
        null, nature, null, null, "kg", nature, null, "主制造", "V1",
        new BigDecimal(qty), new BigDecimal(qty), BigDecimal.ONE, path, id.intValue(),
        id, id, 0, "210", "COMMERCIAL", null, null);
  }

  private QuoteBomPackageReferenceDetail packageLine(
      Long id, int lineNo, String parent, String code, String name, String qty, String path) {
    QuoteBomPackageReferenceDetail row = new QuoteBomPackageReferenceDetail();
    row.setId(id);
    row.setLineNo(lineNo);
    row.setPackageParentCode(parent);
    row.setPackageMaterialCode(code);
    row.setPackageMaterialName(name);
    row.setPackageMaterialSpec("S-" + code);
    row.setPackageMaterialModel("M-" + code);
    row.setAdjustedChildQtyPerTop(new BigDecimal(qty));
    row.setUnit("卷");
    row.setPackageMaterialUnit("卷");
    row.setSourcePath(path);
    row.setSelectedFlag(1);
    return row;
  }
}
