package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageCopyRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageDraftRequest;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomPackageReferenceDetail;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationProductTaskMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("QCBP-12 裸品包装草稿真实MySQL持久化")
class TechnicalPackageDraftPersistenceIntegrationTest extends BomMapperTestBase {

  private static final CollaborationPrincipal TECHNICIAN = new CollaborationPrincipal(
      601L, "王工", Set.of(CollaborationRole.TECHNICIAN));

  @Autowired private QuoteCollaborationTaskRepository repository;
  @Autowired private QuoteBomPreparationRecordMapper preparationMapper;
  @Autowired private QuoteBomPackageReferenceMapper referenceMapper;
  @Autowired private QuoteBomPackageReferenceDetailMapper detailMapper;
  @Autowired private PackageComponentSnapshotMapper snapshotMapper;
  @Autowired private PackageComponentSnapshotDetailMapper snapshotDetailMapper;
  @Autowired private OaFormMapper formMapper;
  @Autowired private OaFormItemMapper itemMapper;
  @Autowired private QuoteCollaborationProductTaskMapper productTaskMapper;
  @Autowired private CollaborationProductStateService stateService;
  @Autowired private JdbcTemplate jdbc;

  private final String marker = "Q12" + UUID.randomUUID().toString().replace("-", "")
      .substring(0, 10).toUpperCase();
  private final List<Long> referenceIds = new ArrayList<>();
  private TechnicalPackageDraftApplicationService service;
  private QuoteProductBomPreparationService preparationService;
  private TechnicalRealPriceGapScanService priceScanService;
  private Long formId;
  private Long itemId;
  private Long preparationId;
  private Long masterId;
  private Long productTaskId;

  @BeforeAll
  static void ensureSchemas() throws Exception {
    int index = 0;
    for (String resource : List.of(
        "/db/V142__quote_bom_preparation_schema.sql",
        "/db/V180__quote_bom_preparation_record_org_scope.sql",
        "/db/V206__quote_bom_price_collaboration_schema.sql",
        "/db/V210__quote_collaboration_gap_trace_fields.sql")) {
      String target = "/tmp/QCBP12-" + (++index) + ".sql";
      MYSQL.copyFileToContainer(MountableFile.forClasspathResource(resource), target);
      ExecResult result = MYSQL.execInContainer(
          "sh", "-c", "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
              + " " + MYSQL.getDatabaseName() + " < " + target);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(resource + " 执行失败：" + result.getStderr());
      }
    }
  }

  @BeforeEach
  void setUp() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("q12-tech", null, List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    CollaborationCurrentPrincipalProvider principalProvider =
        mock(CollaborationCurrentPrincipalProvider.class);
    when(principalProvider.currentTechnician()).thenReturn(TECHNICIAN);
    FormalBomReadService formalBom = mock(FormalBomReadService.class);
    when(formalBom.read(any(com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext.class)))
        .thenReturn(bodyResult());
    preparationService = mock(QuoteProductBomPreparationService.class);
    priceScanService = mock(TechnicalRealPriceGapScanService.class);
    service = new TechnicalPackageDraftApplicationService(
        repository, principalProvider, formalBom, preparationService, preparationMapper,
        referenceMapper, detailMapper, snapshotMapper, snapshotDetailMapper,
        itemMapper, productTaskMapper,
        priceScanService, stateService, new CollaborationPortalAccessPolicy());
  }

  @AfterEach
  void clean() {
    SecurityContextHolder.clearContext();
    if (productTaskId != null) {
      jdbc.update("DELETE FROM lp_business_change_log WHERE biz_domain='QUOTE_COLLABORATION' "
          + "AND biz_type='PRODUCT_TASK_EVENT' AND biz_id=?", productTaskId);
      jdbc.update("DELETE FROM lp_quote_collaboration_gap WHERE product_task_id=?", productTaskId);
      jdbc.update("DELETE FROM lp_quote_collaboration_quote_link WHERE product_task_id=?", productTaskId);
      jdbc.update("DELETE FROM lp_quote_collaboration_product_task WHERE id=?", productTaskId);
    }
    for (Long id : referenceIds) {
      jdbc.update("DELETE FROM lp_quote_bom_package_reference_detail WHERE package_reference_id=?", id);
      jdbc.update("DELETE FROM lp_quote_bom_package_reference WHERE id=?", id);
    }
    if (masterId != null) jdbc.update("DELETE FROM lp_quote_collaboration_task WHERE id=?", masterId);
    if (preparationId != null) jdbc.update("DELETE FROM lp_quote_bom_preparation_record WHERE id=?", preparationId);
    if (itemId != null) jdbc.update("DELETE FROM oa_form_item WHERE id=?", itemId);
    if (formId != null) jdbc.update("DELETE FROM oa_form WHERE id=?", formId);
  }

  @Test
  @DisplayName("U9本体不落包装表，零缺价仍待审；新增胶带只生成一个真实缺口")
  void persistsIndependentPackageAndOnlyMissingNewLeafGap() {
    Fixture fixture = createFixture();
    long u9CountBefore = count("lp_bom_u9_source");
    long hierarchyCountBefore = count("lp_bom_raw_hierarchy");

    var copied = service.copy(productTaskId,
        new TechnicalPackageCopyRequest(1, "QUOTED_PRODUCT", fixture.sourceReferenceId()));
    Long targetReferenceId = copied.draft().packageReferenceId();
    referenceIds.add(targetReferenceId);
    assertThat(targetReferenceId).isNotEqualTo(fixture.sourceReferenceId());
    assertThat(copied.combinedBom().lines()).filteredOn(line -> "PACKAGE_DRAFT".equals(line.source()))
        .extracting(line -> line.parentCode() + "->" + line.materialCode())
        .containsExactly(marker + "->" + marker + "-PACK", marker + "-PACK->" + marker + "-BOX");

    when(priceScanService.scan(any(), any()))
        .thenReturn(CollaborationPriceScanResult.ready(2));
    var noGap = service.checkPrice(productTaskId, copied.taskVersion());
    assertThat(noGap.priceGapCount()).isZero();
    assertThat(noGap.status()).isEqualTo("READY_FOR_REVIEW");
    assertThat(repository.findProductTaskById(productTaskId,
        new CollaborationScope("COMMERCIAL", "210")).orElseThrow().getTaskStatus())
        .isEqualTo("PACKAGE_IN_PROGRESS");

    var current = service.workspace(productTaskId);
    List<TechnicalPackageDraftRequest.Line> changed = new ArrayList<>();
    for (var line : current.draft().lines()) {
      changed.add(new TechnicalPackageDraftRequest.Line(
          line.draftLineId(), line.packageParentCode(), line.packageParentName(),
          line.packageParentSpec(), line.packageParentModel(), line.packageParentUnit(),
          line.packageQtyPerTop(), line.packageMaterialCode(), line.packageMaterialName(),
          line.packageMaterialSpec(), line.packageMaterialModel(), line.packageMaterialUnit(),
          line.childQtyPerParent(), null));
    }
    changed.add(new TechnicalPackageDraftRequest.Line(
        null, marker + "-PACK", "包装总成", null, null, "件", BigDecimal.ONE,
        marker + "-TAPE", "封箱胶带", null, null, "卷", BigDecimal.ONE, "本次新增"));
    var saved = service.save(productTaskId,
        new TechnicalPackageDraftRequest(current.taskVersion(), changed));
    when(priceScanService.scan(any(), any())).thenReturn(
        CollaborationPriceScanResult.gaps(2, List.of(
            new CollaborationPriceScanResult.PriceGap(
                marker + "-TAPE", "MISSING_PRICE", "MAINTAIN_PRICE",
                "封箱胶带当前没有有效价格", "lp_material_price_type", null,
                "PACKAGE_REFERENCE", null, null,
                "/" + marker + "/" + marker + "-PACK/" + marker + "-TAPE/",
                "封箱胶带", null, null, "PACKAGE_MATERIAL", BigDecimal.ONE, "卷",
                "2026-08", "210"))));
    var oneGap = service.checkPrice(productTaskId, saved.taskVersion());

    assertThat(oneGap.priceGapCount()).isOne();
    assertThat(repository.findGaps(productTaskId, new CollaborationScope("COMMERCIAL", "210")))
        .singleElement().satisfies(gap -> {
          assertThat(gap.getMaterialCode()).isEqualTo(marker + "-TAPE");
          assertThat(gap.getMaterialRole()).isEqualTo("PACKAGE_MATERIAL");
          assertThat(gap.getGapStatus()).isEqualTo("OPEN");
        });
    assertThat(count("lp_bom_u9_source")).isEqualTo(u9CountBefore);
    assertThat(count("lp_bom_raw_hierarchy")).isEqualTo(hierarchyCountBefore);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_bom_package_reference_detail WHERE package_reference_id=?",
        Integer.class, targetReferenceId)).isEqualTo(2);
  }

  private Fixture createFixture() {
    OaForm form = new OaForm();
    form.setSourceType("OA");
    form.setSourceSystem("OA");
    form.setExternalFormNo(marker + "-FORM");
    form.setProcessCode("Q12");
    form.setProcessName("Q12验收");
    form.setOaNo(marker + "-OA");
    form.setFormType("标准品/批量品");
    form.setApplyDate(LocalDate.of(2026, 8, 13));
    form.setCustomer("Q12测试客户");
    form.setBusinessUnitType("COMMERCIAL");
    form.setAccountingPeriodMonth("2026-08");
    assertThat(formMapper.insert(form)).isOne();
    formId = form.getId();

    OaFormItem item = new OaFormItem();
    item.setOaFormId(formId);
    item.setExternalLineId(marker + "-LINE");
    item.setSeq(1);
    item.setProductName("裸品阀");
    item.setMaterialNo(marker);
    item.setBusinessUnitType("COMMERCIAL");
    assertThat(itemMapper.insert(item)).isOne();
    itemId = item.getId();

    QuoteBomPreparationRecord preparation = new QuoteBomPreparationRecord();
    preparation.setOaFormId(formId);
    preparation.setOaFormItemId(itemId);
    preparation.setOaNo(form.getOaNo());
    preparation.setQuoteProductCode(marker);
    preparation.setProductType("BARE");
    preparation.setBareProductCode(marker);
    preparation.setNeedPackage(1);
    preparation.setCostPeriodMonth("2026-08");
    preparation.setPreparationStatus("NEED_TECH");
    preparation.setReviewStatus("NOT_SUBMITTED");
    preparation.setTechnicianUserId(TECHNICIAN.userId());
    preparation.setTechnicianName(TECHNICIAN.userName());
    preparation.setPriceOrgCode("210");
    preparation.setMaterialOrganizationCode("COMMERCIAL");
    preparation.setActiveFlag(1);
    assertThat(preparationMapper.insert(preparation)).isOne();
    preparationId = preparation.getId();

    QuoteBomPackageReference source = reference(preparation, "APPROVED", marker + "-REF");
    assertThat(referenceMapper.insert(source)).isOne();
    referenceIds.add(source.getId());
    QuoteBomPackageReferenceDetail detail = detail(source, marker + "-PACK", marker + "-BOX");
    assertThat(detailMapper.insert(detail)).isOne();

    QuoteCollaborationTask master = new QuoteCollaborationTask();
    master.setOaFormId(formId);
    master.setOaNo(form.getOaNo());
    master.setBusinessUnitType("COMMERCIAL");
    master.setAccountingMonth("2026-08");
    master.setSourceSystem("QUOTE_SYSTEM");
    master.setMasterStatus("WAIT_TECH");
    master.setFinanceReviewerUserId(701L);
    master.setFinanceReviewerName("财务审核员");
    master.setOwnedProductCount(1);
    master = repository.saveTask(master);
    masterId = master.getId();

    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setOriginCollaborationId(masterId);
    task.setAccountingMonth("2026-08");
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setMaterialOrgCode("COMMERCIAL");
    task.setPriceOrgCode("210");
    task.setProductCode(marker);
    task.setProductName("裸品阀");
    task.setProductForm("BARE");
    task.setPrimaryScope("BARE_PACKAGE");
    task.setNeedBom(0);
    task.setNeedPackage(1);
    task.setNeedPrice(0);
    task.setOpenGapCount(0);
    task.setTaskStatus("PACKAGE_IN_PROGRESS");
    task.setOriginalTechnicianUserId(TECHNICIAN.userId());
    task.setOriginalTechnicianName(TECHNICIAN.userName());
    task.setCurrentAssigneeUserId(TECHNICIAN.userId());
    task.setCurrentAssigneeName(TECHNICIAN.userName());
    task.setActiveLockKey("COMMERCIAL:210:2026-08:BARE_PACKAGE:" + marker);
    task.setPreparationId(preparationId);
    task = repository.saveProductTask(task);
    productTaskId = task.getId();

    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setProductTaskId(productTaskId);
    link.setCollaborationId(masterId);
    link.setOaFormId(formId);
    link.setOaFormItemId(itemId);
    link.setOaNo(form.getOaNo());
    link.setProductCode(marker);
    link.setAccountingMonth("2026-08");
    link.setApplicableOrgCode("210");
    link.setLinkType("OWNER");
    link.setLinkStatus("WAIT_SOURCE");
    link.setActiveLinkKey("OA_ITEM:" + itemId);
    repository.saveQuoteLink(link);

    when(preparationService.prepareByOaFormItem(eq(itemId), any()))
        .thenReturn(readyPreparation());
    return new Fixture(source.getId());
  }

  private QuoteBomPackageReference reference(
      QuoteBomPreparationRecord preparation, String status, String referenceProduct) {
    QuoteBomPackageReference value = new QuoteBomPackageReference();
    value.setPreparationId(preparation.getId());
    value.setOaNo(preparation.getOaNo());
    value.setOaFormItemId(preparation.getOaFormItemId());
    value.setQuoteProductCode(referenceProduct);
    value.setBareProductCode(marker);
    value.setReferenceFinishedCode(referenceProduct);
    value.setSourceTopProductCode(referenceProduct);
    value.setPeriodMonth("2026-08");
    value.setReferenceStatus(status);
    value.setSelectedLineCount(1);
    value.setEditedFlag(0);
    value.setActiveFlag(1);
    value.setRemark("SOURCE_MODE=QUOTED_PRODUCT");
    return value;
  }

  private QuoteBomPackageReferenceDetail detail(
      QuoteBomPackageReference reference, String parent, String child) {
    QuoteBomPackageReferenceDetail value = new QuoteBomPackageReferenceDetail();
    value.setPackageReferenceId(reference.getId());
    value.setPreparationId(preparationId);
    value.setOaNo(marker + "-OA");
    value.setOaFormItemId(itemId);
    value.setBareProductCode(marker);
    value.setReferenceFinishedCode(reference.getReferenceFinishedCode());
    value.setSourceTopProductCode(reference.getSourceTopProductCode());
    value.setLineNo(1);
    value.setPackageParentCode(parent);
    value.setPackageParentName("包装总成");
    value.setPackageParentUnit("件");
    value.setPackageQtyPerParent(BigDecimal.ONE);
    value.setPackageQtyPerTop(BigDecimal.ONE);
    value.setPackageParentBaseQty(BigDecimal.ONE);
    value.setPackageMaterialCode(child);
    value.setPackageMaterialName("包装纸箱");
    value.setPackageMaterialUnit("件");
    value.setChildQtyPerParent(BigDecimal.ONE);
    value.setChildQtyPerTop(BigDecimal.ONE);
    value.setChildParentBaseQty(BigDecimal.ONE);
    value.setQtyPerTop(BigDecimal.ONE);
    value.setUnit("件");
    value.setSelectedFlag(1);
    value.setEditedFlag(0);
    return value;
  }

  private QuoteProductBomPreparationPreview readyPreparation() {
    return new QuoteProductBomPreparationPreview(
        preparationId, 1L, formId, itemId, marker + "-OA", marker, "BARE", marker,
        true, "2026-08", "READY", "NOT_SUBMITTED", true, false, false,
        "U9", true, 1, null, null, false, 0, null, null, null, null,
        List.of(), List.of(), null, bodyResult().lines(), List.of());
  }

  private FormalBomReadResult bodyResult() {
    return new FormalBomReadResult(marker, "2026-08", "主制造", true,
        List.of(new QuoteBomSourceLineDto(
            1L, 1, 1, marker, marker, marker + "-BODY", "裸品本体", null, null,
            null, null, null, null, "件", "U9", null, "主制造", "V1",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
            "/" + marker + "/" + marker + "-BODY/", 1, 1L, 1L, 0,
            "210", "COMMERCIAL", "MANUFACTURED", null)), null);
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
  }

  private record Fixture(Long sourceReferenceId) {}
}
