package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import com.sanhua.marketingcost.dto.quotebom.SupplementBomReadResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTaskQuoteLinkMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.PackageComponentStructureReadService;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import com.sanhua.marketingcost.service.SupplementBomReadService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteProductBomPreparationServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), QuoteBomPreparationRecord.class);
  }

  private OaFormItemMapper itemMapper;
  private OaFormMapper formMapper;
  private QuoteBomStatusMapper statusMapper;
  private QuoteBomPreparationRecordMapper preparationRecordMapper;
  private QuoteProductTypeResolveService productTypeResolveService;
  private FormalBomReadService formalBomReadService;
  private SupplementBomReadService supplementBomReadService;
  private PackageComponentStructureReadService packageReadService;
  private QuoteProductBomPreparationServiceImpl service;

  @BeforeEach
  void setUp() {
    itemMapper = mock(OaFormItemMapper.class);
    formMapper = mock(OaFormMapper.class);
    statusMapper = mock(QuoteBomStatusMapper.class);
    preparationRecordMapper = mock(QuoteBomPreparationRecordMapper.class);
    productTypeResolveService = mock(QuoteProductTypeResolveService.class);
    formalBomReadService = mock(FormalBomReadService.class);
    supplementBomReadService = mock(SupplementBomReadService.class);
    packageReadService = mock(PackageComponentStructureReadService.class);
    service =
        new QuoteProductBomPreparationServiceImpl(
            itemMapper,
            formMapper,
            statusMapper,
            preparationRecordMapper,
            mock(BomSupplementTaskMapper.class),
            mock(BomSupplementTaskQuoteLinkMapper.class),
            productTypeResolveService,
            formalBomReadService,
            supplementBomReadService,
            packageReadService);

    when(statusMapper.selectOne(any())).thenReturn(null);
    when(preparationRecordMapper.selectOne(any())).thenReturn(null);
    doAnswer(
            invocation -> {
              invocation.getArgument(0, QuoteBomStatus.class).setId(101L);
              return 1;
            })
        .when(statusMapper)
        .insert(any(QuoteBomStatus.class));
    doAnswer(
            invocation -> {
              invocation.getArgument(0, QuoteBomPreparationRecord.class).setId(201L);
              return 1;
            })
        .when(preparationRecordMapper)
        .insert(any(QuoteBomPreparationRecord.class));
  }

  @Test
  @DisplayName("非裸品正式 BOM 存在：直接准备完成")
  void prepareNonBareWhenFormalBomExists() {
    stubQuoteLine("FIN-001", "2026-05");
    when(productTypeResolveService.resolve("FIN-001", "COMMERCIAL"))
        .thenReturn(type("FIN-001", QuoteProductType.NON_BARE));
    stubFormalRead("FIN-001", "2026-05", formalFound("FIN-001", "2026-05"));

    QuoteProductBomPreparationPreview preview = service.prepareByOaFormItem(10L);

    assertThat(preview.ready()).isTrue();
    assertThat(preview.needTechnicianTask()).isFalse();
    assertThat(preview.productType()).isEqualTo("NON_BARE");
    assertThat(preview.bodyBomSource()).isEqualTo("FORMAL_BOM");
    assertThat(preview.bodyBomLineCount()).isEqualTo(1);
    verify(supplementBomReadService, never()).readApproved(any(), any(), any(), any());
  }

  @Test
  @DisplayName("非裸品正式 BOM 缺失且无可复用补录：需要补录")
  void prepareNonBareWhenBomMissingNeedsSupplement() {
    stubQuoteLine("FIN-MISSING", "2026-05");
    when(productTypeResolveService.resolve("FIN-MISSING", "COMMERCIAL"))
        .thenReturn(type("FIN-MISSING", QuoteProductType.NON_BARE));
    stubFormalRead("FIN-MISSING", "2026-05", formalMissing("FIN-MISSING", "2026-05"));
    when(supplementBomReadService.readApproved(
            "FIN-MISSING", "NON_BARE", "NON_BARE_FULL_BOM", "2026-05"))
        .thenReturn(supplementMissing("FIN-MISSING", "NON_BARE", "NON_BARE_FULL_BOM", "2026-05"));

    QuoteProductBomPreparationPreview preview = service.prepareByOaFormItem(10L);

    assertThat(preview.ready()).isFalse();
    assertThat(preview.needTechnicianTask()).isTrue();
    assertThat(preview.missingScopes()).containsExactly("NON_BARE_FULL_BOM");
    assertThat(preview.gapMessages()).anySatisfy(message -> assertThat(message).contains("需技术员补录"));
  }

  @Test
  @DisplayName("非裸品跨月优先读取正式 BOM：有正式 BOM 时不复用补录")
  void prepareNonBareCrossMonthPrefersFormalBom() {
    stubQuoteLine("FIN-CROSS", "2026-06");
    when(productTypeResolveService.resolve("FIN-CROSS", "COMMERCIAL"))
        .thenReturn(type("FIN-CROSS", QuoteProductType.NON_BARE));
    stubFormalRead("FIN-CROSS", "2026-06", formalFound("FIN-CROSS", "2026-06"));

    QuoteProductBomPreparationPreview preview = service.prepareByOaFormItem(10L);

    assertThat(preview.ready()).isTrue();
    assertThat(preview.bodyBomSource()).isEqualTo("FORMAL_BOM");
    verify(supplementBomReadService, never()).readApproved(any(), any(), any(), any());
  }

  @Test
  @DisplayName("非裸品补录 6 个月过期：不能复用，进入技术补录缺口")
  void prepareNonBareExpiredSupplementNeedsTask() {
    stubQuoteLine("FIN-EXPIRED", "2026-05");
    when(productTypeResolveService.resolve("FIN-EXPIRED", "COMMERCIAL"))
        .thenReturn(type("FIN-EXPIRED", QuoteProductType.NON_BARE));
    stubFormalRead("FIN-EXPIRED", "2026-05", formalMissing("FIN-EXPIRED", "2026-05"));
    when(supplementBomReadService.readApproved(
            "FIN-EXPIRED", "NON_BARE", "NON_BARE_FULL_BOM", "2026-05"))
        .thenReturn(
            new SupplementBomReadResult(
                "FIN-EXPIRED",
                "NON_BARE",
                "NON_BARE_FULL_BOM",
                "2026-05",
                false,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                "未找到 6 个月有效期内的已审核补录 BOM"));

    QuoteProductBomPreparationPreview preview = service.prepareByOaFormItem(10L);

    assertThat(preview.needTechnicianTask()).isTrue();
    assertThat(preview.gapMessages())
        .contains("未找到 6 个月有效期内的已审核补录 BOM");
  }

  @Test
  @DisplayName("裸品本体正式 BOM 存在但缺包装参考：只生成包装参考缺口")
  void prepareBareWithBodyBomButMissingPackageReference() {
    stubQuoteLine("BARE-001", "2026-05");
    when(productTypeResolveService.resolve("BARE-001", "COMMERCIAL"))
        .thenReturn(type("BARE-001", QuoteProductType.BARE));
    stubFormalRead("BARE-001", "2026-05", formalFound("BARE-001", "2026-05"));
    when(packageReadService.readApprovedReferenceForBareProduct("BARE-001", "210", "COMMERCIAL"))
        .thenReturn(packageMissing("BARE-001"));

    QuoteProductBomPreparationPreview preview = service.prepareByOaFormItem(10L);

    assertThat(preview.bodyBomReady()).isTrue();
    assertThat(preview.packageReferenceReady()).isFalse();
    assertThat(preview.needTechnicianTask()).isTrue();
    assertThat(preview.missingScopes()).containsExactly("PACKAGE_REFERENCE");
    verify(supplementBomReadService, never()).readApproved(any(), any(), any(), any());
  }

  @Test
  @DisplayName("裸品本体 BOM 缺失且缺包装参考：同时生成两个缺口")
  void prepareBareMissingBodyBomAndPackageReference() {
    stubQuoteLine("BARE-MISSING", "2026-05");
    when(productTypeResolveService.resolve("BARE-MISSING", "COMMERCIAL"))
        .thenReturn(type("BARE-MISSING", QuoteProductType.BARE));
    stubFormalRead("BARE-MISSING", "2026-05", formalMissing("BARE-MISSING", "2026-05"));
    when(supplementBomReadService.readApproved("BARE-MISSING", "BARE", "BARE_BODY_BOM", "2026-05"))
        .thenReturn(supplementMissing("BARE-MISSING", "BARE", "BARE_BODY_BOM", "2026-05"));
    when(packageReadService.readApprovedReferenceForBareProduct("BARE-MISSING", "210", "COMMERCIAL"))
        .thenReturn(packageMissing("BARE-MISSING"));

    QuoteProductBomPreparationPreview preview = service.prepareByOaFormItem(10L);

    assertThat(preview.bodyBomReady()).isFalse();
    assertThat(preview.packageReferenceReady()).isFalse();
    assertThat(preview.missingScopes()).containsExactly("BARE_BODY_BOM", "PACKAGE_REFERENCE");
    assertThat(preview.needTechnicianTask()).isTrue();
  }

  @Test
  @DisplayName("裸品包装参考长期复用：不按 6 个月失效处理")
  void prepareBareReusesApprovedPackageReferenceLongTerm() {
    stubQuoteLine("BARE-PKG", "2026-05");
    when(productTypeResolveService.resolve("BARE-PKG", "COMMERCIAL"))
        .thenReturn(type("BARE-PKG", QuoteProductType.BARE));
    stubFormalRead("BARE-PKG", "2026-05", formalFound("BARE-PKG", "2026-05"));
    when(packageReadService.readApprovedReferenceForBareProduct("BARE-PKG", "210", "COMMERCIAL"))
        .thenReturn(packageFound("REF-2025", "2025-01"));

    QuoteProductBomPreparationPreview preview = service.prepareByOaFormItem(10L);

    assertThat(preview.ready()).isTrue();
    assertThat(preview.packageReferenceReady()).isTrue();
    assertThat(preview.referenceFinishedCode()).isEqualTo("REF-2025");
    assertThat(preview.packageLineCount()).isEqualTo(1);
    assertThat(preview.reuseType()).isEqualTo("PACKAGE_REFERENCE");
  }

  @Test
  @DisplayName("主档缺失：进入异常，不误判为缺 BOM")
  void prepareWhenMasterMissingReturnsAbnormal() {
    stubQuoteLine("MASTER-MISSING", "2026-05");
    when(productTypeResolveService.resolve("MASTER-MISSING", "COMMERCIAL"))
        .thenReturn(
            new QuoteProductTypeResolveResult(
                "MASTER-MISSING",
                QuoteProductType.DATA_MISSING,
                null,
                null,
                null,
                null,
                "料品主档缺失，无法判断裸品/非裸品"));

    QuoteProductBomPreparationPreview preview = service.prepareByOaFormItem(10L);

    assertThat(preview.abnormal()).isTrue();
    assertThat(preview.preparationStatus()).isEqualTo("ERROR");
    assertThat(preview.errorMessage()).contains("料品主档缺失");
    verifyNoInteractions(formalBomReadService, supplementBomReadService, packageReadService);
  }

  @Test
  @DisplayName("板换报价准备入口向正式 BOM 读取传递统一组织对象")
  void preparePassesQuoteDataOrganizationToFormalBomRead() {
    stubQuoteLine("PLATE-001", "2026-05", "FI-SC-020", "FI-SC-020-20260707-001", "板换组件");
    when(productTypeResolveService.resolve("PLATE-001", "PLATE"))
        .thenReturn(type("PLATE-001", QuoteProductType.NON_BARE));
    stubFormalRead("PLATE-001", "2026-05", formalFound("PLATE-001", "2026-05"));

    QuoteProductBomPreparationPreview preview = service.prepareByOaFormItem(10L);

    assertThat(preview.ready()).isTrue();
    ArgumentCaptor<QuoteDataOrganization> captor =
        ArgumentCaptor.forClass(QuoteDataOrganization.class);
    verify(formalBomReadService)
        .read(eq("PLATE-001"), eq("2026-05"), isNull(), any(LocalDate.class), captor.capture());
    assertThat(captor.getValue().priceOrgCode()).isEqualTo("220");
    assertThat(captor.getValue().materialOrganizationCode()).isEqualTo("PLATE");
    assertMonthlyLockLookupUsesOrganization("220", "PLATE");
    ArgumentCaptor<QuoteBomPreparationRecord> recordCaptor =
        ArgumentCaptor.forClass(QuoteBomPreparationRecord.class);
    verify(preparationRecordMapper).insert(recordCaptor.capture());
    assertThat(recordCaptor.getValue().getPriceOrgCode()).isEqualTo("220");
    assertThat(recordCaptor.getValue().getMaterialOrganizationCode()).isEqualTo("PLATE");
  }

  private void stubQuoteLine(String productCode, String periodMonth) {
    stubQuoteLine(productCode, periodMonth, null, "OA-QBP-05", "产品");
  }

  private void stubQuoteLine(
      String productCode, String periodMonth, String processCode, String oaNo, String productName) {
    OaFormItem item = new OaFormItem();
    item.setId(10L);
    item.setOaFormId(20L);
    item.setMaterialNo(productCode);
    item.setProductName(productName);
    item.setBusinessUnitType("COMMERCIAL");
    item.setSunlModel("MODEL");
    item.setCustomerCode("CUST");
    item.setPackageMethod("纸箱");
    item.setTechnicianName("技术员A");
    OaForm form = new OaForm();
    form.setId(20L);
    form.setOaNo(oaNo);
    form.setProcessCode(processCode);
    form.setApplyDate(LocalDate.parse(periodMonth + "-16"));
    form.setAccountingPeriodMonth(periodMonth);
    when(itemMapper.selectById(10L)).thenReturn(item);
    when(formMapper.selectById(20L)).thenReturn(form);
  }

  private void stubFormalRead(String productCode, String periodMonth, FormalBomReadResult result) {
    when(formalBomReadService.read(
            eq(productCode),
            eq(periodMonth),
            isNull(),
            any(LocalDate.class),
            any(QuoteDataOrganization.class)))
        .thenReturn(result);
  }

  private QuoteProductTypeResolveResult type(String code, QuoteProductType type) {
    return new QuoteProductTypeResolveResult(code, type, type == QuoteProductType.BARE ? "1101" : "1201", null, null, null, null);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void assertMonthlyLockLookupUsesOrganization(
      String expectedPriceOrgCode, String expectedMaterialOrganizationCode) {
    ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(preparationRecordMapper, atLeastOnce()).selectOne(captor.capture());
    List<Wrapper> monthlyLockQueries =
        captor.getAllValues().stream()
            .filter(wrapper -> wrapper.getSqlSegment().contains("quote_product_code"))
            .toList();
    assertThat(monthlyLockQueries).isNotEmpty();
    assertThat(monthlyLockQueries)
        .allSatisfy(
            wrapper -> {
              assertThat(wrapper.getSqlSegment())
                  .contains("price_org_code", "material_organization_code");
              assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs())
                  .containsValue(expectedPriceOrgCode)
                  .containsValue(expectedMaterialOrganizationCode);
            });
  }

  private FormalBomReadResult formalFound(String code, String periodMonth) {
    return new FormalBomReadResult(code, periodMonth, null, true, List.of(sourceLine(code, 0)), null);
  }

  private FormalBomReadResult formalMissing(String code, String periodMonth) {
    return new FormalBomReadResult(code, periodMonth, null, false, List.of(), "未在 lp_bom_raw_hierarchy 找到正式 BOM");
  }

  private SupplementBomReadResult supplementMissing(
      String code, String productType, String scope, String periodMonth) {
    return new SupplementBomReadResult(
        code,
        productType,
        scope,
        periodMonth,
        false,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        "未找到 6 个月有效期内的已审核补录 BOM");
  }

  private PackageComponentStructureReadResult packageFound(String referenceCode, String periodMonth) {
    return new PackageComponentStructureReadResult(
        referenceCode, referenceCode, periodMonth, 901L, true, List.of(packageLine(referenceCode)), List.of());
  }

  private PackageComponentStructureReadResult packageMissing(String bareCode) {
    return new PackageComponentStructureReadResult(
        null, null, null, null, false, List.of(), List.of("未找到已审核裸品包装参考"));
  }

  private QuoteBomSourceLineDto sourceLine(String topProductCode, int manualFlag) {
    return new QuoteBomSourceLineDto(
        1L,
        1,
        1,
        topProductCode,
        topProductCode,
        "MAT-001",
        "子件",
        "规格",
        "型号",
        "图号",
        "实体",
        "1201",
        "PCS",
        "U9",
        null,
        null,
        null,
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        "/" + topProductCode + "/MAT-001/",
        1,
        1L,
        null,
        manualFlag,
        null,
        null);
  }

  private PackageComponentStructureLineDto packageLine(String referenceCode) {
    return new PackageComponentStructureLineDto(
        901L,
        902L,
        referenceCode,
        referenceCode,
        "2025-01",
        1,
        "PKG-PARENT",
        "包装父件",
        "规格",
        "型号",
        "图号",
        "虚拟",
        "15155",
        "PCS",
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        1L,
        "/" + referenceCode + "/PKG-PARENT/",
        "PKG-CHILD",
        "纸箱",
        "规格",
        "型号",
        "图号",
        "实体",
        "15156",
        "PCS",
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        2L,
        "PKG-PARENT",
        "/" + referenceCode + "/PKG-PARENT/PKG-CHILD/",
        1);
  }
}
