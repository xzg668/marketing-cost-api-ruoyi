package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSourceRef;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSourceRefMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.BomByproductCostRuleQueryService;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomRepository;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import com.sanhua.marketingcost.service.settlement.BomByproductSettlementAdapter;
import com.sanhua.marketingcost.service.settlement.BomByproductSettlementReadResult;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildEngine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteEffectiveBomToCostingRowsTest {

  private FormalBomReadService formalBomReadService;
  private QuoteBomPreparationRecordMapper preparationMapper;
  private QuoteBomStatusMapper statusMapper;
  private BomCostingRowMapper costingRowMapper;
  private BomCostingRowSourceRefMapper sourceRefMapper;
  private BomCostingRowSubRefMapper subRefMapper;
  private OaFormItemMapper itemMapper;
  private QuoteEffectiveBomRepository effectiveRepository;
  private BomRawHierarchyMapper rawMapper;
  private BomSettlementRuleQueryService settlementRules;
  private QuoteProductBomCostingBuildServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, BomCostingRow.class);
    TableInfoHelper.initTableInfo(assistant, BomCostingRowSourceRef.class);
    TableInfoHelper.initTableInfo(assistant, BomCostingRowSubRef.class);
  }

  @BeforeEach
  void setUp() {
    formalBomReadService = mock(FormalBomReadService.class);
    preparationMapper = mock(QuoteBomPreparationRecordMapper.class);
    statusMapper = mock(QuoteBomStatusMapper.class);
    costingRowMapper = mock(BomCostingRowMapper.class);
    sourceRefMapper = mock(BomCostingRowSourceRefMapper.class);
    subRefMapper = mock(BomCostingRowSubRefMapper.class);
    itemMapper = mock(OaFormItemMapper.class);
    effectiveRepository = mock(QuoteEffectiveBomRepository.class);
    rawMapper = mock(BomRawHierarchyMapper.class);
    settlementRules = mock(BomSettlementRuleQueryService.class);
    BomByproductCostRuleQueryService byproductRules =
        mock(BomByproductCostRuleQueryService.class);
    BomByproductSettlementAdapter byproductAdapter =
        mock(BomByproductSettlementAdapter.class);
    ObjectMapper objectMapper = new ObjectMapper();
    BomSettlementRowBuildEngine engine =
        new BomSettlementRowBuildEngine(
            new BomSettlementRuleMatcher(
                new BomSettlementRuleConditionEvaluator(objectMapper)),
            new BomByproductCostRuleMatcher(
                new BomByproductCostRuleConditionEvaluator(objectMapper)));
    service =
        new QuoteProductBomCostingBuildServiceImpl(
            mock(QuoteProductBomPreparationService.class),
            formalBomReadService,
            settlementRules,
            byproductRules,
            byproductAdapter,
            engine,
            preparationMapper,
            statusMapper,
            mock(BomSupplementTaskMapper.class),
            mock(QuoteBomSupplementVersionMapper.class),
            mock(QuoteBomSupplementDetailMapper.class),
            mock(QuoteBomPackageReferenceMapper.class),
            mock(QuoteBomPackageReferenceDetailMapper.class),
            costingRowMapper,
            sourceRefMapper,
            subRefMapper,
            itemMapper,
            effectiveRepository,
            rawMapper);
    when(settlementRules.listEnabledCandidates()).thenReturn(List.of());
    when(byproductRules.listEnabledCandidates()).thenReturn(List.of());
    when(byproductAdapter.read(any(), any(), any(), any(), any()))
        .thenReturn(new BomByproductSettlementReadResult(List.of(), List.of(), List.of()));
    when(preparationMapper.selectOne(any())).thenReturn(preparation());
    when(statusMapper.selectById(101L)).thenReturn(status());
    when(costingRowMapper.selectList(any())).thenReturn(List.of());
    OaFormItem item = new OaFormItem();
    item.setId(10L);
    item.setBusinessUnitType("COMMERCIAL");
    when(itemMapper.selectById(10L)).thenReturn(item);
    doAnswer(
            invocation -> {
              BomCostingRow row = invocation.getArgument(0);
              row.setId(900L);
              return 1;
            })
        .when(costingRowMapper)
        .insert(any(BomCostingRow.class));
  }

  @Test
  void usesOnlyFrozenEffectiveNodesAndKeepsSameBuildBatch() {
    when(effectiveRepository.findNodesByBuildBatchId("qeb_BUILD_1"))
        .thenReturn(List.of(root(), selectedAlternative()));
    when(rawMapper.selectBatchIds(any())).thenReturn(List.of(raw(1L, "P"), raw(2L, "T")));

    QuoteBomCostingBuildResponse response =
        service.buildFromEffectiveBom(10L, "qeb_BUILD_1");

    assertThat(response.buildBatchId()).isEqualTo("qeb_BUILD_1");
    assertThat(response.costingRowsWritten()).isOne();
    ArgumentCaptor<BomCostingRow> rows = ArgumentCaptor.forClass(BomCostingRow.class);
    verify(costingRowMapper).insert(rows.capture());
    assertThat(rows.getValue().getMaterialCode()).isEqualTo("T");
    assertThat(rows.getValue().getBuildBatchId()).isEqualTo("qeb_BUILD_1");
    assertThat(rows.getValue().getShapeAttr()).isEqualTo("采购件");
    verify(formalBomReadService, never()).read(any());
  }

  @Test
  void effectiveBomMonthOverridesStalePreparationMonthForCurrentWorkspaceRows() {
    QuoteBomPreparationRecord stalePreparation = preparation();
    stalePreparation.setCostPeriodMonth("2026-07");
    when(preparationMapper.selectOne(any())).thenReturn(stalePreparation);
    when(effectiveRepository.findNodesByBuildBatchId("qeb_BUILD_1"))
        .thenReturn(List.of(root(), selectedAlternative()));
    when(rawMapper.selectBatchIds(any())).thenReturn(List.of(raw(1L, "P"), raw(2L, "T")));

    QuoteBomCostingBuildResponse response =
        service.buildFromEffectiveBom(10L, "qeb_BUILD_1");

    assertThat(response.periodMonth()).isEqualTo("2026-08");
    assertThat(stalePreparation.getCostPeriodMonth()).isEqualTo("2026-08");
    assertThat(stalePreparation.getCostingBuildBatchId()).isEqualTo("qeb_BUILD_1");
    verify(preparationMapper).updateById(stalePreparation);
    ArgumentCaptor<BomCostingRow> rows = ArgumentCaptor.forClass(BomCostingRow.class);
    verify(costingRowMapper).insert(rows.capture());
    assertThat(rows.getValue().getPeriodMonth()).isEqualTo("2026-08");
  }

  @Test
  void plateEffectiveBomKeepsCommercialOrganizationOnExpandedNodes() {
    QuoteBomPreparationRecord platePreparation = preparation();
    platePreparation.setPriceOrgCode("220");
    platePreparation.setMaterialOrganizationCode("PLATE");
    when(preparationMapper.selectOne(any())).thenReturn(platePreparation);
    OaFormItem plateItem = new OaFormItem();
    plateItem.setId(10L);
    plateItem.setBusinessUnitType("PLATE");
    when(itemMapper.selectById(10L)).thenReturn(plateItem);

    QuoteEffectiveBomNode plateRoot = root();
    plateRoot.setPriceOrgCode("220");
    QuoteEffectiveBomNode commercialParent =
        node(2L, "MAKE-210", "ROOT", 1, "/P/9990000050426/", "9990000050426", "MANUFACTURE");
    commercialParent.setPriceOrgCode("210");
    QuoteEffectiveBomNode commercialChild =
        node(
            3L,
            "RAW-210",
            "MAKE-210",
            2,
            "/P/9990000050426/301050013/",
            "301050013",
            "PURCHASE");
    commercialChild.setPriceOrgCode("210");
    when(effectiveRepository.findNodesByBuildBatchId("qeb_BUILD_1"))
        .thenReturn(List.of(plateRoot, commercialParent, commercialChild));
    when(rawMapper.selectBatchIds(any()))
        .thenReturn(
            List.of(
                raw(1L, "P"),
                raw(2L, "9990000050426"),
                raw(3L, "301050013")));

    service.buildFromEffectiveBom(10L, "qeb_BUILD_1");

    ArgumentCaptor<BomCostingRow> rows = ArgumentCaptor.forClass(BomCostingRow.class);
    verify(costingRowMapper).insert(rows.capture());
    assertThat(rows.getValue().getMaterialCode()).isEqualTo("301050013");
    assertThat(rows.getValue().getPriceOrgCode()).isEqualTo("210");
    assertThat(rows.getValue().getMaterialOrganizationCode()).isEqualTo("COMMERCIAL");
  }

  @Test
  void effectiveStableShapeCodesKeepLegacyDrawnCopperTubeRollup() {
    QuoteEffectiveBomNode root = node(1L, "ROOT", null, 0, "/P/", "P", "MANUFACTURE");
    QuoteEffectiveBomNode parent =
        node(
            2L,
            "TUBE-PARENT",
            "ROOT",
            1,
            "/P/721850051/",
            "721850051",
            "MANUFACTURE");
    parent.setMaterialName("接管");
    QuoteEffectiveBomNode child =
        node(
            3L,
            "TUBE-CHILD",
            "TUBE-PARENT",
            2,
            "/P/721850051/301060256/",
            "301060256",
            "PURCHASE");
    child.setMaterialName("拉制铜管");
    child.setQtyPerParent(new BigDecimal("0.00692983"));
    child.setQtyPerTop(new BigDecimal("0.00692983"));
    when(effectiveRepository.findNodesByBuildBatchId("qeb_BUILD_1"))
        .thenReturn(List.of(root, parent, child));
    when(rawMapper.selectBatchIds(any()))
        .thenReturn(
            List.of(
                raw(1L, "P"),
                raw(2L, "721850051"),
                raw(3L, "301060256")));
    when(settlementRules.listEnabledCandidates())
        .thenReturn(List.of(drawnCopperTubeRollupRule()));

    service.buildFromEffectiveBom(10L, "qeb_BUILD_1");

    ArgumentCaptor<BomCostingRow> rowCaptor =
        ArgumentCaptor.forClass(BomCostingRow.class);
    verify(costingRowMapper).insert(rowCaptor.capture());
    assertThat(rowCaptor.getValue().getMaterialCode()).isEqualTo("721850051");
    assertThat(rowCaptor.getValue().getMaterialName()).isEqualTo("接管");
    assertThat(rowCaptor.getValue().getShapeAttr()).isEqualTo("制造件");
    assertThat(rowCaptor.getValue().getSettlementRowType())
        .isEqualTo("SPECIAL_ROLLUP_PARENT");
    assertThat(rowCaptor.getValue().getMatchedSettlementRuleId()).isEqualTo(5L);

    ArgumentCaptor<BomCostingRowSubRef> subRefCaptor =
        ArgumentCaptor.forClass(BomCostingRowSubRef.class);
    verify(subRefMapper).insert(subRefCaptor.capture());
    assertThat(subRefCaptor.getValue().getSubMaterialCode()).isEqualTo("301060256");
    assertThat(subRefCaptor.getValue().getSubMaterialName()).isEqualTo("拉制铜管");
    assertThat(subRefCaptor.getValue().getSubQtyPerTop())
        .isEqualByComparingTo("0.00692983");
    assertThat(subRefCaptor.getValue().getRefType()).isEqualTo("SPECIAL_ROLLUP_CHILD");
  }

  @Test
  void effectiveStableShapeCodesKeepPackageAndOutsourceSemantics() {
    QuoteEffectiveBomNode root = node(1L, "ROOT", null, 0, "/P/", "P", "MANUFACTURE");
    QuoteEffectiveBomNode packageParent =
        node(
            2L,
            "PACKAGE",
            "ROOT",
            1,
            "/P/9830000025705/",
            "9830000025705",
            "VIRTUAL");
    packageParent.setMaterialName("包装组件");
    QuoteEffectiveBomNode packageChild =
        node(
            3L,
            "PACKAGE-CHILD",
            "PACKAGE",
            2,
            "/P/9830000025705/250011406/",
            "250011406",
            "PURCHASE");
    QuoteEffectiveBomNode outsourceParent =
        node(
            4L,
            "OUTSOURCE",
            "ROOT",
            1,
            "/P/OUTSOURCE/",
            "OUTSOURCE",
            "OUTSOURCE");
    outsourceParent.setMaterialName("委外部件");
    QuoteEffectiveBomNode outsourceChild =
        node(
            5L,
            "OUTSOURCE-CHILD",
            "OUTSOURCE",
            2,
            "/P/OUTSOURCE/RAW/",
            "RAW",
            "PURCHASE");
    BomRawHierarchy packageRaw = raw(2L, "9830000025705");
    packageRaw.setMaterialCategory1("1515501");
    when(effectiveRepository.findNodesByBuildBatchId("qeb_BUILD_1"))
        .thenReturn(List.of(root, packageParent, packageChild, outsourceParent, outsourceChild));
    when(rawMapper.selectBatchIds(any()))
        .thenReturn(
            List.of(
                raw(1L, "P"),
                packageRaw,
                raw(3L, "250011406"),
                raw(4L, "OUTSOURCE"),
                raw(5L, "RAW")));

    service.buildFromEffectiveBom(10L, "qeb_BUILD_1");

    ArgumentCaptor<BomCostingRow> rowCaptor =
        ArgumentCaptor.forClass(BomCostingRow.class);
    verify(costingRowMapper, org.mockito.Mockito.times(3)).insert(rowCaptor.capture());
    assertThat(rowCaptor.getAllValues())
        .filteredOn(row -> "PACKAGE_PARENT".equals(row.getSettlementRowType()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getMaterialCode()).isEqualTo("9830000025705");
              assertThat(row.getShapeAttr()).isEqualTo("虚拟");
            });
    assertThat(rowCaptor.getAllValues())
        .filteredOn(row -> "OUTSOURCED_PROCESS_FEE".equals(row.getSettlementRowType()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getMaterialCode()).isEqualTo("OUTSOURCE");
              assertThat(row.getShapeAttr()).isEqualTo("委外加工件");
            });
    assertThat(rowCaptor.getAllValues())
        .filteredOn(row -> "DEFAULT_LEAF".equals(row.getSettlementRowType()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.getMaterialCode()).isEqualTo("RAW");
              assertThat(row.getShapeAttr()).isEqualTo("采购件");
            });
    assertThat(rowCaptor.getAllValues())
        .noneMatch(row -> "250011406".equals(row.getMaterialCode()));
  }

  @Test
  void effectiveStablePurchaseShapeStillAllowsAuxiliaryExclusion() {
    QuoteEffectiveBomNode root = node(1L, "ROOT", null, 0, "/P/", "P", "MANUFACTURE");
    QuoteEffectiveBomNode auxiliary =
        node(2L, "AUX", "ROOT", 1, "/P/AUX/", "AUX", "PURCHASE");
    auxiliary.setMaterialName("环氧树脂");
    when(effectiveRepository.findNodesByBuildBatchId("qeb_BUILD_1"))
        .thenReturn(List.of(root, auxiliary));
    when(rawMapper.selectBatchIds(any()))
        .thenReturn(List.of(raw(1L, "P"), raw(2L, "AUX")));
    when(settlementRules.listEnabledCandidates())
        .thenReturn(List.of(auxiliaryExcludeRule()));

    QuoteBomCostingBuildResponse response =
        service.buildFromEffectiveBom(10L, "qeb_BUILD_1");

    assertThat(response.costingRowsWritten()).isZero();
    verify(costingRowMapper, never()).insert(any(BomCostingRow.class));
  }

  @Test
  void excludedStandardPurchaseDescendantsAndPolicyRemovedMaterialsCannotReappear() {
    when(effectiveRepository.findNodesByBuildBatchId("qeb_BUILD_1"))
        .thenReturn(List.of(root(), selectedAlternative()));
    when(rawMapper.selectBatchIds(any())).thenReturn(List.of(raw(1L, "P"), raw(2L, "T")));

    service.buildFromEffectiveBom(10L, "qeb_BUILD_1");

    ArgumentCaptor<BomCostingRow> rows = ArgumentCaptor.forClass(BomCostingRow.class);
    verify(costingRowMapper).insert(rows.capture());
    assertThat(rows.getAllValues())
        .extracting(BomCostingRow::getMaterialCode)
        .containsExactly("T")
        .doesNotContain("S", "T-CHILD", "GOLD");
  }

  @Test
  void explicitRebuildReplacesLegacyManuallyModifiedCurrentRows() {
    when(effectiveRepository.findNodesByBuildBatchId("qeb_BUILD_1"))
        .thenReturn(List.of(root(), selectedAlternative()));
    when(rawMapper.selectBatchIds(any())).thenReturn(List.of(raw(1L, "P"), raw(2L, "T")));
    BomCostingRow legacyCurrentRow = new BomCostingRow();
    legacyCurrentRow.setId(801L);
    legacyCurrentRow.setManualModified(1);
    when(costingRowMapper.selectList(any())).thenReturn(List.of(legacyCurrentRow));

    QuoteBomCostingBuildResponse response =
        service.buildFromEffectiveBom(10L, "qeb_BUILD_1");

    assertThat(response.costingRowsWritten()).isOne();
    verify(subRefMapper).delete(any());
    verify(sourceRefMapper).delete(any());
    verify(costingRowMapper).delete(any());
    verify(costingRowMapper).insert(any(BomCostingRow.class));
  }

  @Test
  void mismatchedMonthOrganizationOrBuildIsRejected() {
    QuoteEffectiveBomNode wrong = selectedAlternative();
    wrong.setCostPeriodMonth("2026-09");
    when(effectiveRepository.findNodesByBuildBatchId("qeb_BUILD_1"))
        .thenReturn(List.of(root(), wrong));

    assertThatThrownBy(() -> service.buildFromEffectiveBom(10L, "qeb_BUILD_1"))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("不一致");
    verify(costingRowMapper, never()).insert(any(BomCostingRow.class));
  }

  private QuoteBomPreparationRecord preparation() {
    QuoteBomPreparationRecord record = new QuoteBomPreparationRecord();
    record.setId(201L);
    record.setQuoteBomStatusId(101L);
    record.setOaFormId(20L);
    record.setOaFormItemId(10L);
    record.setOaNo("OA-1");
    record.setQuoteProductCode("P");
    record.setProductType("NON_BARE");
    record.setCostPeriodMonth("2026-08");
    record.setPriceOrgCode("210");
    record.setMaterialOrganizationCode("COMMERCIAL");
    record.setPreparationStatus("READY");
    record.setReviewStatus("NOT_SUBMITTED");
    record.setActiveFlag(1);
    return record;
  }

  private QuoteBomStatus status() {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setId(101L);
    status.setOaFormItemId(10L);
    return status;
  }

  private QuoteEffectiveBomNode root() {
    return node(1L, "ROOT", null, 0, "/P/", "P", "MANUFACTURE");
  }

  private QuoteEffectiveBomNode selectedAlternative() {
    QuoteEffectiveBomNode node =
        node(2L, "ALT-T", "ROOT", 1, "/P/T/", "T", "PURCHASE");
    node.setAlternativeGroupKey("ALT-1");
    node.setAlternativeChildType("ALTERNATIVE");
    return node;
  }

  private QuoteEffectiveBomNode node(
      Long id,
      String key,
      String parent,
      int level,
      String path,
      String material,
      String shape) {
    QuoteEffectiveBomNode node = new QuoteEffectiveBomNode();
    node.setId(id + 100L);
    node.setBuildBatchId("qeb_BUILD_1");
    node.setOriginMonthlySnapshotId(11L);
    node.setTopProductCode("P");
    node.setCostPeriodMonth("2026-08");
    node.setPriceOrgCode("210");
    node.setNodeKey(key);
    node.setParentNodeKey(parent);
    node.setNodeLevel(level);
    node.setSortSeq(level + 1);
    node.setNodePath(path);
    node.setMaterialCode(material);
    node.setMaterialName(material + " name");
    node.setQtyPerParent(BigDecimal.ONE);
    node.setQtyPerTop(BigDecimal.ONE);
    node.setEffectiveMaterialShape(shape);
    node.setSourceBomType("U9");
    node.setSourceBomBatchId("RAW-1");
    node.setSourceHierarchyId(id);
    node.setSourceNodePath(path);
    return node;
  }

  private BomRawHierarchy raw(Long id, String material) {
    BomRawHierarchy raw = new BomRawHierarchy();
    raw.setId(id);
    raw.setMaterialCode(material);
    raw.setSourceCategory("采购件");
    raw.setCostElementCode("CE");
    raw.setMaterialCategory1("1201");
    raw.setMaterialCategory2("零部件类");
    raw.setBomPurpose("主制造");
    raw.setBomVersion("V1");
    raw.setBuildBatchId("RAW-1");
    raw.setSourceU9RowId(id + 1000L);
    return raw;
  }

  private BomSettlementRule drawnCopperTubeRollupRule() {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(5L);
    rule.setRuleCode("SPECIAL_PURCHASE_ROLLUP_DRAWN_COPPER_TUBE");
    rule.setRuleName("特殊子项品名上卷：拉制铜管");
    rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");
    rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    rule.setSubRefType("SPECIAL_ROLLUP_CHILD");
    rule.setMatchConditionJson(
        "{\"nodeConditions\":[{\"field\":\"material_name\",\"op\":\"LIKE\",\"value\":\"拉制铜管\"}]}");
    rule.setPriority(14);
    rule.setEnabled(1);
    rule.setDeleted(0);
    return rule;
  }

  private BomSettlementRule auxiliaryExcludeRule() {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(6L);
    rule.setRuleCode("AUXILIARY_EXCLUDE_EPOXY");
    rule.setRuleName("财务辅料排除：环氧树脂");
    rule.setRuleCategory("AUXILIARY_EXCLUDE");
    rule.setSettlementAction("EXCLUDE");
    rule.setSettlementRowType("EXCLUDED");
    rule.setMatchConditionJson(
        "{\"nodeConditions\":[{\"field\":\"material_name\",\"op\":\"LIKE\",\"value\":\"环氧树脂\"}]}");
    rule.setPriority(40);
    rule.setEnabled(1);
    rule.setDeleted(0);
    return rule;
  }
}
