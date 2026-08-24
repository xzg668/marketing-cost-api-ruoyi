package com.sanhua.marketingcost.service.bomalternative;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.service.impl.QuoteBomAlternativeRebuildServiceImpl;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomRuleMaterialAttributes;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import com.sanhua.marketingcost.service.settlement.BomSettlementBuildRequest;
import com.sanhua.marketingcost.service.settlement.BomSettlementByproduct;
import com.sanhua.marketingcost.service.settlement.BomSettlementNode;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildEngine;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildResult;
import com.sanhua.marketingcost.service.settlement.BomSettlementSourceRef;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * QBA-12真实料号隔离测试支撑。
 *
 * <p>结构字段来自2026-07-30对本地真实业务库的只读查询：正式层共79个节点，
 * 201850659标准子树和201850522替代子树各23个节点。选择记录只写内存仓储或
 * Testcontainers，不写真实业务库。
 */
final class QuoteBomAlternativeRealDataTestSupport {

  static final String OA_NO = "FI-SC-006-20260106-082";
  static final long ITEM_ID = 326L;
  static final String TOP = "1145900000302";
  static final String PARENT = "101850644";
  static final String STANDARD = "201850659";
  static final String ALTERNATIVE = "201850522";
  static final String PERIOD = "2026-07";
  static final String PRICE_ORG = "210";
  static final String MATERIAL_ORG = "COMMERCIAL";
  static final String BUSINESS_UNIT = "COMMERCIAL";
  static final String BOM_PURPOSE = "主制造";
  static final String IMPORT_BATCH = "u9_bom_2026-07-06";
  static final String BUILD_BATCH = "h_20260706";
  static final LocalDate QUOTE_DATE = LocalDate.of(2026, 7, 30);

  private static final String ROOT_PATH = "/" + TOP + "/";
  private static final String PARENT_PATH =
      ROOT_PATH + PARENT + "@10@030/";
  private static final Clock CLOCK =
      Clock.fixed(
          Instant.parse("2026-07-30T02:00:00Z"),
          ZoneId.of("Asia/Shanghai"));

  private final List<BomRawHierarchy> sourceRows;
  private final BomAlternativeGroup group;
  private final QuoteBomAlternativeSelectionTestSupport.InMemoryRepository
      selectionRepository =
          new QuoteBomAlternativeSelectionTestSupport.InMemoryRepository();
  private final QuoteBomAlternativeSelectionServiceImpl selectionService;
  private final BomAlternativeBranchPruner branchPruner =
      new BomAlternativeBranchPrunerImpl();
  private final BomSettlementRowBuildEngine settlementEngine;
  private final List<BomCostingRow> persistedCostingRows =
      new ArrayList<>();
  private final QuoteBomAlternativeRebuildService rebuildService;

  private BomAlternativePruneResult lastPruned;
  private int lastSourceRefCount;
  private long costingRowSequence = 1L;

  QuoteBomAlternativeRealDataTestSupport() {
    initTableInfo();
    ObjectMapper objectMapper =
        new ObjectMapper().findAndRegisterModules();
    BomAlternativeGroupKeyGenerator keyGenerator =
        new BomAlternativeGroupKeyGeneratorImpl();
    sourceRows = realSourceRows(keyGenerator);
    BomAlternativeGroupResolution resolution =
        new BomAlternativeGroupResolverImpl(keyGenerator)
            .resolve(sourceRows);
    if (!resolution.issues().isEmpty()
        || resolution.groups().size() != 1) {
      throw new IllegalStateException(
          "真实结构替代组解析失败，groups="
              + resolution.groups().size()
              + ", issues="
              + resolution.issues());
    }
    group = resolution.groups().getFirst();
    selectionService =
        new QuoteBomAlternativeSelectionServiceImpl(
            selectionRepository, objectMapper, CLOCK);
    settlementEngine =
        new BomSettlementRowBuildEngine(
            new BomSettlementRuleMatcher(
                new BomSettlementRuleConditionEvaluator(
                    objectMapper)),
            new BomByproductCostRuleMatcher(
                new BomByproductCostRuleConditionEvaluator(
                    objectMapper)),
            (codes, ignoredOrganization) ->
                codes.stream()
                    .filter(
                        QuoteBomAlternativeRealDataTestSupport
                            ::financeExcludedMaterial)
                    .collect(
                        java.util.stream.Collectors.toMap(
                            code -> code,
                            ignored ->
                                new BomRuleMaterialAttributes(
                                    "181841442", null))));
    rebuildService = rebuildService();
  }

  List<BomRawHierarchy> sourceRows() {
    return sourceRows;
  }

  QuoteBomAlternativeSelectionScope scope() {
    return new QuoteBomAlternativeSelectionScope(
        OA_NO,
        ITEM_ID,
        TOP,
        PERIOD,
        PRICE_ORG,
        BUSINESS_UNIT);
  }

  BomAlternativeGroup group() {
    return group;
  }

  QuoteBomAlternativeSelectionCommand command(
      String selectedMaterialCode, int expectedVersion) {
    return new QuoteBomAlternativeSelectionCommand(
        scope(),
        group.alternativeGroupKey(),
        selectedMaterialCode,
        expectedVersion,
        BUILD_BATCH,
        "quote-user",
        "QBA-12真实料号三轮验证");
  }

  RoundSnapshot defaultStandard() {
    QuoteBomAlternativeSelectionResult selection =
        selectionService.ensureDefault(scope(), group);
    rebuildCostingRows(selection.selectedMaterialCode());
    return snapshot(selection, null);
  }

  RoundSnapshot selectAlternative() {
    return rebuild(ALTERNATIVE, 1, "选择真实替代件");
  }

  RoundSnapshot restoreStandard() {
    return rebuild(STANDARD, 2, "恢复真实标准件");
  }

  List<QuoteBomAlternativeSelectionResult> history() {
    return selectionService.history(
        scope(), group.alternativeGroupKey());
  }

  /**
   * QBA-13 等价性基线：模拟改造前已经明确只把某一棵候选子树交给结算引擎。
   *
   * <p>这里不调用新分支裁剪器，而是按路径直接移除另一候选子树。它与
   * {@link #selectedEngineSnapshot(String)} 使用完全相同的现有结算引擎，
   * 用于证明新逻辑只改变“进入引擎的分支”，不会改变引擎输出。
   */
  EngineSnapshot legacyDirectEngineSnapshot(
      String selectedMaterialCode) {
    String excludedMaterialCode =
        STANDARD.equals(selectedMaterialCode)
            ? ALTERNATIVE
            : STANDARD;
    String excludedPath =
        "/" + excludedMaterialCode + "@10@010/";
    List<BomRawHierarchy> directRows =
        sourceRows.stream()
            .filter(
                row ->
                    row.getPath() == null
                        || !row.getPath().contains(excludedPath))
            .toList();
    return engineSnapshot(directRows);
  }

  /** QBA-13 新链路：先按报价选择裁剪，再交给原结算引擎。 */
  EngineSnapshot selectedEngineSnapshot(
      String selectedMaterialCode) {
    BomAlternativePruneResult pruned =
        branchPruner.prune(
            new BomAlternativePruneRequest(
                sourceRows,
                List.of(group),
                Map.of(
                    group.alternativeGroupKey(),
                    selectedMaterialCode)));
    return engineSnapshot(pruned.nodes());
  }

  private RoundSnapshot rebuild(
      String selectedMaterialCode,
      int expectedVersion,
      String remark) {
    QuoteBomAlternativeRebuildResult result =
        rebuildService.rebuild(
            new QuoteBomAlternativeRebuildCommand(
                OA_NO,
                ITEM_ID,
                TOP,
                PERIOD,
                PRICE_ORG,
                MATERIAL_ORG,
                BUSINESS_UNIT,
                BOM_PURPOSE,
                QUOTE_DATE,
                group.alternativeGroupKey(),
                selectedMaterialCode,
                expectedVersion,
                BUILD_BATCH,
                "quote-user",
                remark));
    // 用户显式重新核算后，才按刚保存的选择重建当前 BOM 工作区。
    rebuildCostingRows(result.selection().selectedMaterialCode());
    return snapshot(result.selection(), result);
  }

  private RoundSnapshot snapshot(
      QuoteBomAlternativeSelectionResult selection,
      QuoteBomAlternativeRebuildResult rebuild) {
    List<BomCostingRow> rows =
        persistedCostingRows.stream()
            .sorted(
                Comparator.comparing(BomCostingRow::getPath)
                    .thenComparing(
                        BomCostingRow::getMaterialCode))
            .toList();
    int replaceCount =
        com.sanhua.marketingcost.entity
                .QuoteBomAlternativeSelection
                .SOURCE_MANUAL_ALTERNATIVE
                .equals(selection.selectionSource())
            ? 1
            : 0;
    List<String> costingRowKeys =
        rows.stream()
            .map(
                row ->
                    row.getMaterialCode()
                        + "|"
                        + row.getQtyPerTop()
                        + "|"
                        + row.getSettlementRowType()
                        + "|"
                        + row.getPath())
            .toList();
    return new RoundSnapshot(
        selection,
        lastPruned,
        rows,
        costingRowKeys,
        replaceCount,
        lastSourceRefCount,
        rebuild);
  }

  private QuoteBomAlternativeRebuildService rebuildService() {
    BomRawHierarchyMapper rawMapper =
        mock(BomRawHierarchyMapper.class);
    BomAlternativeGroupResolver resolver =
        new BomAlternativeGroupResolverImpl(
            new BomAlternativeGroupKeyGeneratorImpl());
    QuoteBomPreparationRecordMapper preparationMapper =
        mock(QuoteBomPreparationRecordMapper.class);
    QuoteBomAlternativeWorkflowInvalidationService invalidationService =
        mock(
            QuoteBomAlternativeWorkflowInvalidationService.class);

    when(rawMapper.selectList(any())).thenReturn(sourceRows);
    when(preparationMapper.selectOne(any()))
        .thenReturn(preparationRecord());
    when(invalidationService.invalidate(
            any(), any(), any(), any()))
        .thenReturn(
            new QuoteBomAlternativeWorkflowInvalidationResult(
                2, 3, 4));
    return new QuoteBomAlternativeRebuildServiceImpl(
        rawMapper,
        resolver,
        selectionService,
        preparationMapper,
        invalidationService);
  }

  private void rebuildCostingRows(
      String selectedMaterialCode) {
    lastPruned =
        branchPruner.prune(
            new BomAlternativePruneRequest(
                sourceRows,
                List.of(group),
                Map.of(
                    group.alternativeGroupKey(),
                    selectedMaterialCode)));
    List<BomSettlementNode> nodes =
        toSettlementNodes(lastPruned.nodes());
    var built =
        settlementEngine.build(
            new BomSettlementBuildRequest(
                OA_NO,
                TOP,
                QUOTE_DATE,
                PERIOD,
                "qba12-"
                    + selectedMaterialCode,
                LocalDateTime.now(CLOCK),
                BUSINESS_UNIT,
                BOM_PURPOSE,
                nodes,
                List.of(
                    financeAuxiliaryExcludeRule(),
                    drawnCopperTubeRollupRule()),
                realByproducts(),
                List.of(),
                List.of(byproductRule())));
    List<BomCostingRow> aggregated =
        aggregateRepeatedWeldingRing(
            built.costingRows());
    persistedCostingRows.clear();
    for (BomCostingRow row : aggregated) {
      row.setId(costingRowSequence++);
      row.setOaFormItemId(ITEM_ID);
      row.setPriceOrgCode(PRICE_ORG);
      row.setMaterialOrganizationCode(MATERIAL_ORG);
      row.setBusinessUnitType(BUSINESS_UNIT);
      row.setManualModified(0);
      persistedCostingRows.add(row);
    }
    lastSourceRefCount = built.sourceRefs().size();
  }

  private EngineSnapshot engineSnapshot(
      List<BomRawHierarchy> selectedRows) {
    BomSettlementRowBuildResult built =
        settlementEngine.build(
            new BomSettlementBuildRequest(
                OA_NO,
                TOP,
                QUOTE_DATE,
                PERIOD,
                "qba13-equivalence",
                LocalDateTime.now(CLOCK),
                BUSINESS_UNIT,
                BOM_PURPOSE,
                toSettlementNodes(selectedRows),
                List.of(
                    financeAuxiliaryExcludeRule(),
                    drawnCopperTubeRollupRule()),
                realByproducts(),
                List.of(),
                List.of(byproductRule())));
    List<BomCostingRow> rows =
        aggregateRepeatedWeldingRing(
            built.costingRows());
    List<String> rowFingerprints =
        rows.stream()
            .map(
                row ->
                    String.join(
                        "|",
                        text(row.getParentCode()),
                        text(row.getMaterialCode()),
                        text(row.getMaterialName()),
                        text(row.getMaterialSpec()),
                        decimal(row.getQtyPerParent()),
                        decimal(row.getQtyPerTop()),
                        text(row.getLevel()),
                        text(row.getPath()),
                        text(row.getSettlementRowType()),
                        text(row.getSubtreeCostRequired()),
                        text(row.getShapeAttr()),
                        text(row.getSourceCategory()),
                        text(row.getCostElementCode()),
                        text(row.getBomPurpose()),
                        text(row.getBomVersion()),
                        text(row.getPriceOrgCode()),
                        text(row.getMaterialOrganizationCode()),
                        text(row.getBusinessUnitType())))
            .sorted()
            .toList();
    List<String> subRefFingerprints =
        built.subRefs().stream()
            .map(
                candidate -> {
                  var ref = candidate.subRef();
                  return String.join(
                      "|",
                      text(candidate.costingRowPath()),
                      text(ref.getRefType()),
                      text(ref.getSubMaterialCode()),
                      text(ref.getSubMaterialName()),
                      text(ref.getSubMaterialCategory()),
                      decimal(ref.getSubQtyPerParent()),
                      decimal(ref.getSubQtyPerTop()),
                      text(ref.getSubPath()),
                      text(ref.getBusinessUnitType()));
                })
            .sorted()
            .toList();
    List<String> sourceRefFingerprints =
        built.sourceRefs().stream()
            .map(
                candidate -> {
                  var ref = candidate.sourceRef();
                  return String.join(
                      "|",
                      text(candidate.costingRowPath()),
                      text(ref.getOaNo()),
                      text(ref.getOaFormItemId()),
                      text(ref.getQuoteProductCode()),
                      text(ref.getSourcePartType()),
                      text(ref.getSourceRawHierarchyId()),
                      text(ref.getSourceTopProductCode()),
                      text(ref.getSourceU9BomId()),
                      text(ref.getSourcePath()));
                })
            .sorted()
            .toList();
    return new EngineSnapshot(
        selectedRows.size(),
        rowFingerprints,
        subRefFingerprints,
        sourceRefFingerprints,
        built.warnings());
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString();
  }

  private static String decimal(BigDecimal value) {
    return value == null
        ? ""
        : value.stripTrailingZeros().toPlainString();
  }

  private List<BomCostingRow> aggregateRepeatedWeldingRing(
      List<BomCostingRow> rows) {
    List<BomCostingRow> result = new ArrayList<>();
    BomCostingRow weldingRing = null;
    for (BomCostingRow row : rows) {
      if (!"337101105".equals(row.getMaterialCode())) {
        result.add(row);
        continue;
      }
      if (weldingRing == null) {
        weldingRing = row;
        result.add(row);
        continue;
      }
      weldingRing.setQtyPerParent(
          weldingRing
              .getQtyPerParent()
              .add(row.getQtyPerParent()));
      weldingRing.setQtyPerTop(
          weldingRing.getQtyPerTop().add(row.getQtyPerTop()));
    }
    return result;
  }

  private List<BomSettlementNode> toSettlementNodes(
      List<BomRawHierarchy> selectedRows) {
    return selectedRows.stream()
        .map(
            row -> {
              boolean leaf =
                  selectedRows.stream()
                      .filter(
                          other ->
                              other != row
                                  && other.getPath() != null)
                      .noneMatch(
                          other ->
                              other
                                  .getPath()
                                  .startsWith(row.getPath()));
              return new BomSettlementNode(
                  row.getId(),
                  TOP,
                  row.getParentCode(),
                  row.getMaterialCode(),
                  row.getLevel(),
                  row.getPath(),
                  row.getQtyPerParent(),
                  row.getQtyPerTop(),
                  row.getMaterialName(),
                  row.getMaterialSpec(),
                  row.getShapeAttr(),
                  row.getSourceCategory(),
                  row.getCostElementCode(),
                  row.getMaterialCategory1(),
                  row.getMaterialCategory2(),
                  null,
                  row.getBomPurpose(),
                  row.getBomVersion(),
                  row.getU9IsCostFlag(),
                  leaf ? 1 : 0,
                  row.getEffectiveFrom(),
                  row.getEffectiveTo(),
                  row.getEffectiveFrom(),
                  PRICE_ORG,
                  MATERIAL_ORG,
                  BUSINESS_UNIT,
                  new BomSettlementSourceRef(
                      OA_NO,
                      ITEM_ID,
                      TOP,
                      "RAW_PRODUCT_BOM",
                      row.getId(),
                      null,
                      20L,
                      null,
                      null,
                      null,
                      null,
                      null,
                      TOP,
                      null,
                      null,
                      row.getSourceU9RowId(),
                      row.getPath()));
            })
        .toList();
  }

  private QuoteBomPreparationRecord preparationRecord() {
    QuoteBomPreparationRecord record =
        new QuoteBomPreparationRecord();
    record.setId(20L);
    record.setOaNo(OA_NO);
    record.setOaFormItemId(ITEM_ID);
    record.setQuoteProductCode(TOP);
    record.setProductType("NON_BARE");
    record.setPreparationStatus("READY");
    record.setPriceOrgCode(PRICE_ORG);
    record.setMaterialOrganizationCode(MATERIAL_ORG);
    record.setCostPeriodMonth(PERIOD);
    record.setActiveFlag(1);
    return record;
  }

  private static BomSettlementRule
      drawnCopperTubeRollupRule() {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(5L);
    rule.setRuleCode(
        "SPECIAL_PURCHASE_ROLLUP_DRAWN_COPPER_TUBE");
    rule.setRuleName("特殊子项品名上卷：拉制铜管");
    rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");
    rule.setSettlementRowType(
        "SPECIAL_ROLLUP_PARENT");
    rule.setSubRefType("SPECIAL_ROLLUP_CHILD");
    rule.setMatchConditionJson(
        """
        {"nodeConditions":[{"op":"LIKE","field":"material_name","value":"拉制铜管"}]}
        """);
    rule.setMarkSubtreeCostRequired(1);
    rule.setPriority(14);
    rule.setEnabled(1);
    return rule;
  }

  private static BomSettlementRule
      financeAuxiliaryExcludeRule() {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(11L);
    rule.setRuleCode(
        "AUXILIARY_EXCLUDE_FINANCE_MAIN_CATEGORIES");
    rule.setRuleName("辅料排除：财务主分类清单");
    rule.setRuleCategory("AUXILIARY_EXCLUDE");
    rule.setSettlementAction("EXCLUDE");
    rule.setSettlementRowType("EXCLUDED");
    rule.setMatchConditionJson(
        """
        {"nodeConditions":[{"op":"EQ","field":"main_category_code","value":"181841442"}]}
        """);
    rule.setMarkSubtreeCostRequired(0);
    rule.setPriority(40);
    rule.setEnabled(1);
    return rule;
  }

  private static List<BomSettlementByproduct>
      realByproducts() {
    return List.of(
        new BomSettlementByproduct(
            1L,
            "7218501041211",
            "301991066",
            "基座/YCQB02-020004，清洗后 废料",
            null,
            new BigDecimal("0.0108"),
            "千克",
            BOM_PURPOSE,
            "F001",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(9999, 12, 31),
            BUSINESS_UNIT),
        new BomSettlementByproduct(
            2L,
            "721850074",
            "301991071",
            "膜片/YCQB02-023001 废料",
            null,
            new BigDecimal("0.000088"),
            "千克",
            BOM_PURPOSE,
            "F001",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(9999, 12, 31),
            BUSINESS_UNIT));
  }

  private static BomByproductCostRule byproductRule() {
    BomByproductCostRule rule =
        new BomByproductCostRule();
    rule.setId(1L);
    rule.setRuleCode(
        "BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF");
    rule.setRuleName(
        "副产品未命中废料映射时输出结算行");
    rule.setRuleCategory("BYPRODUCT_EXTRA");
    rule.setAddConditionType("NO_SCRAP_REF_MATCH");
    rule.setSettlementRowType("BYPRODUCT_EXTRA");
    rule.setMatchConditionJson(
        """
        {"byproductConditions":[{"op":"EQ","field":"shape_attr","value":"制造件"}]}
        """);
    rule.setPriority(10);
    rule.setEnabled(1);
    return rule;
  }

  private static List<BomRawHierarchy> realSourceRows(
      BomAlternativeGroupKeyGenerator keyGenerator) {
    List<BomRawHierarchy> rows = new ArrayList<>();
    add(
        rows,
        0,
        TOP,
        TOP,
        null,
        null,
        "1",
        ROOT_PATH,
        null,
        null);
    add(
        rows,
        1,
        PARENT,
        TOP,
        10,
        "030",
        "1",
        PARENT_PATH,
        null,
        null);
    add(
        rows,
        1,
        "9830000025705",
        TOP,
        20,
        "030",
        "1",
        ROOT_PATH + "9830000025705@20@030/",
        null,
        null);

    addOutsideRows(rows);
    String groupKey =
        realGroupKey(keyGenerator);
    addBranch(rows, STANDARD, true, groupKey);
    addBranch(rows, ALTERNATIVE, false, groupKey);
    finishRows(rows);
    if (rows.size() != 79) {
      throw new IllegalStateException(
          "真实结构应为79个节点，实际=" + rows.size());
    }
    return List.copyOf(rows);
  }

  private static void addOutsideRows(
      List<BomRawHierarchy> rows) {
    addOutside(
        rows,
        2,
        "201850024",
        PARENT,
        90,
        "110",
        "1",
        PARENT_PATH + "201850024@90@110/");
    addOutside(
        rows,
        2,
        "201850122",
        PARENT,
        60,
        "040",
        "1",
        PARENT_PATH + "201850122@60@040/");
    addOutside(
        rows,
        2,
        "201850146",
        PARENT,
        30,
        "060",
        "1",
        PARENT_PATH + "201850146@30@060/");
    addOutside(
        rows,
        2,
        "201850386",
        PARENT,
        80,
        "080",
        "1",
        PARENT_PATH + "201850386@80@080/");
    addOutside(
        rows,
        2,
        "201850584",
        PARENT,
        40,
        "050",
        "1",
        PARENT_PATH + "201850584@40@050/");
    String connector =
        PARENT_PATH + "201850774@70@010/";
    addOutside(
        rows,
        2,
        "201850774",
        PARENT,
        70,
        "010",
        "1",
        connector);
    addOutside(
        rows,
        2,
        "301300030",
        PARENT,
        150,
        "130",
        "0.0008",
        PARENT_PATH + "301300030@150@130/");
    addOutside(
        rows,
        2,
        "301300031",
        PARENT,
        160,
        "130",
        "0.0002",
        PARENT_PATH + "301300031@160@130/");
    addOutside(
        rows,
        2,
        "301300203",
        PARENT,
        130,
        "120",
        "0.00173",
        PARENT_PATH + "301300203@130@120/");
    addOutside(
        rows,
        2,
        "311990114",
        PARENT,
        120,
        "100",
        "0.00015",
        PARENT_PATH + "311990114@120@100/");
    addOutside(
        rows,
        2,
        "332020616",
        PARENT,
        50,
        "100",
        "3",
        PARENT_PATH + "332020616@50@100/");
    addOutside(
        rows,
        2,
        "337101105",
        PARENT,
        110,
        "040",
        "0.00005",
        PARENT_PATH + "337101105@110@040/");
    addOutside(
        rows,
        2,
        "337101105",
        PARENT,
        180,
        "060",
        "0.0001",
        PARENT_PATH + "337101105@180@060/");
    addOutside(
        rows,
        2,
        "339996284",
        PARENT,
        170,
        "100",
        "0.008",
        PARENT_PATH + "339996284@170@100/");
    String circuit =
        PARENT_PATH + "721850100@100@090/";
    addOutside(
        rows,
        2,
        "721850100",
        PARENT,
        100,
        "090",
        "1",
        circuit);
    addOutside(
        rows,
        2,
        "741300023",
        PARENT,
        140,
        "120",
        "0.00347",
        PARENT_PATH + "741300023@140@120/");

    String packagePath =
        ROOT_PATH + "9830000025705@20@030/";
    addOutside(
        rows,
        2,
        "250011406",
        "9830000025705",
        10,
        null,
        "1",
        packagePath + "250011406@10/");
    addOutside(
        rows,
        2,
        "250021655",
        "9830000025705",
        20,
        null,
        "2",
        packagePath + "250021655@20/");
    addOutside(
        rows,
        2,
        "250030549",
        "9830000025705",
        30,
        null,
        "3",
        packagePath + "250030549@30/");
    addOutside(
        rows,
        2,
        "250091045",
        "9830000025705",
        40,
        null,
        "160",
        packagePath + "250091045@40/");

    addOutside(
        rows,
        3,
        "201850704",
        "201850774",
        80,
        "010",
        "1",
        connector + "201850704@80@010/");
    addOutside(
        rows,
        3,
        "301020124",
        "201850774",
        90,
        "010",
        "0.0000822",
        connector + "301020124@90@010/");
    String tubeParent =
        connector + "721850051@70@010/";
    addOutside(
        rows,
        3,
        "721850051",
        "201850774",
        70,
        "010",
        "1",
        tubeParent);
    addOutside(
        rows,
        3,
        "201350081",
        "721850100",
        80,
        "220",
        "3",
        circuit + "201350081@80@220/");
    addOutside(
        rows,
        3,
        "201850132",
        "721850100",
        120,
        "240",
        "1.9",
        circuit + "201850132@120@240/");
    addOutside(
        rows,
        3,
        "201850160",
        "721850100",
        110,
        "210",
        "2.02",
        circuit + "201850160@110@210/");
    addOutside(
        rows,
        3,
        "201850161",
        "721850100",
        90,
        "210",
        "2.02",
        circuit + "201850161@90@210/");
    addOutside(
        rows,
        3,
        "201850162",
        "721850100",
        100,
        "210",
        "2.02",
        circuit + "201850162@100@210/");
    addOutside(
        rows,
        3,
        "332030135",
        "721850100",
        70,
        "230",
        "1",
        circuit + "332030135@70@230/");
    addOutside(
        rows,
        4,
        "301060256",
        "721850051",
        20,
        "110",
        "0.00692983",
        tubeParent + "301060256@20@110/");
  }

  private static void addBranch(
      List<BomRawHierarchy> rows,
      String branchCode,
      boolean standard,
      String groupKey) {
    String branchPath =
        PARENT_PATH + branchCode + "@10@010/";
    add(
        rows,
        2,
        branchCode,
        PARENT,
        10,
        "010",
        "1",
        branchPath,
        groupKey,
        standard ? "STANDARD" : "ALTERNATIVE");
    int offset = standard ? 0 : 10;
    String corePath =
        branchPath + "201850113@" + (150 + offset) + "@210/";
    addBranchRow(
        rows,
        3,
        "201850113",
        branchCode,
        150 + offset,
        "210",
        "1",
        corePath);
    addBranchRow(
        rows,
        3,
        "201850115",
        branchCode,
        130 + offset,
        "240",
        "1",
        branchPath
            + "201850115@"
            + (130 + offset)
            + "@240/");
    addBranchRow(
        rows,
        3,
        "201850117",
        branchCode,
        160 + offset,
        "290",
        "1",
        branchPath
            + "201850117@"
            + (160 + offset)
            + "@290/");
    String unique =
        standard ? "201850547" : "201850347";
    int uniqueSeq = standard ? 140 : 150;
    addBranchRow(
        rows,
        3,
        unique,
        branchCode,
        uniqueSeq,
        "230",
        "1",
        branchPath + unique + "@" + uniqueSeq + "@230/");
    addBranchRow(
        rows,
        3,
        "301340017",
        branchCode,
        200,
        "260",
        "0.000037",
        branchPath + "301340017@200@260/");
    addBranchRow(
        rows,
        3,
        "311034725",
        branchCode,
        190,
        "240",
        "0.00002857",
        branchPath + "311034725@190@240/");
    int ballSeq = standard ? 170 : 110;
    addBranchRow(
        rows,
        3,
        "332010025",
        branchCode,
        ballSeq,
        "320",
        "1",
        branchPath + "332010025@" + ballSeq + "@320/");
    addBranchRow(
        rows,
        3,
        "721850071",
        branchCode,
        110 + offset,
        "300",
        "1",
        branchPath
            + "721850071@"
            + (110 + offset)
            + "@300/");
    String platePath =
        branchPath
            + "721850074@"
            + (120 + offset)
            + "@300/";
    addBranchRow(
        rows,
        3,
        "721850074",
        branchCode,
        120 + offset,
        "300",
        "1",
        platePath);
    addBranchRow(
        rows,
        3,
        "751020005",
        branchCode,
        180,
        "310",
        "0.00067",
        branchPath + "751020005@180@310/");

    String baseRingPath =
        corePath + "201850157@20@210/";
    addBranchRow(
        rows,
        4,
        "201850157",
        "201850113",
        20,
        "210",
        "1",
        baseRingPath);
    addBranchRow(
        rows,
        4,
        "311034930",
        "201850113",
        30,
        "320",
        "0.0036",
        corePath + "311034930@30@320/");
    String plateRawPath =
        platePath + "3012402681163@10@010/";
    addBranchRow(
        rows,
        4,
        "3012402681163",
        "721850074",
        10,
        "010",
        "1",
        plateRawPath);

    String needleA =
        baseRingPath + "1145000300485@60@010/";
    addBranchRow(
        rows,
        5,
        "1145000300485",
        "201850157",
        60,
        "010",
        "0.000095",
        needleA);
    String needleB =
        baseRingPath + "1145000300486@50@010/";
    addBranchRow(
        rows,
        5,
        "1145000300486",
        "201850157",
        50,
        "010",
        "0.00013",
        needleB);
    addBranchRow(
        rows,
        5,
        "201850130",
        "201850157",
        10,
        "010",
        "1",
        baseRingPath + "201850130@10@010/");
    String capillaryPath =
        baseRingPath + "721850104@40@020/";
    addBranchRow(
        rows,
        5,
        "721850104",
        "201850157",
        40,
        "020",
        "1",
        capillaryPath);
    addBranchRow(
        rows,
        5,
        "301240268",
        "3012402681163",
        10,
        "020",
        "0.00013",
        plateRawPath + "301240268@10@020/");

    addBranchRow(
        rows,
        6,
        "201850123",
        "1145000300485",
        10,
        null,
        "0.000095",
        needleA + "201850123@10/");
    addBranchRow(
        rows,
        6,
        "201850148",
        "1145000300486",
        10,
        null,
        "0.00013",
        needleB + "201850148@10/");
    String weldedBasePath =
        capillaryPath + "7218501041211@20@005/";
    addBranchRow(
        rows,
        6,
        "7218501041211",
        "721850104",
        20,
        "005",
        "1",
        weldedBasePath);
    addBranchRow(
        rows,
        7,
        "201850068",
        "7218501041211",
        10,
        "010",
        "1",
        weldedBasePath + "201850068@10@010/");
  }

  private static void addOutside(
      List<BomRawHierarchy> rows,
      int level,
      String code,
      String parent,
      Integer sort,
      String process,
      String qty,
      String path) {
    add(
        rows,
        level,
        code,
        parent,
        sort,
        process,
        qty,
        path,
        null,
        null);
  }

  private static void addBranchRow(
      List<BomRawHierarchy> rows,
      int level,
      String code,
      String parent,
      Integer sort,
      String process,
      String qty,
      String path) {
    add(
        rows,
        level,
        code,
        parent,
        sort,
        process,
        qty,
        path,
        null,
        null);
  }

  private static void add(
      List<BomRawHierarchy> rows,
      int level,
      String code,
      String parent,
      Integer sort,
      String process,
      String qty,
      String path,
      String groupKey,
      String childType) {
    BomRawHierarchy row = new BomRawHierarchy();
    long id = rows.size() + 1L;
    row.setId(id);
    row.setPriceOrgCode(PRICE_ORG);
    row.setTopProductCode(TOP);
    row.setParentCode(parent);
    row.setMaterialCode(code);
    row.setMaterialName(materialName(code));
    row.setMaterialSpec("真实结构-" + code);
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq(sort);
    row.setProcessSeq(process);
    row.setQtyPerParent(
        qty == null ? null : new BigDecimal(qty));
    row.setQtyPerTop(
        qty == null ? BigDecimal.ONE : new BigDecimal(qty));
    row.setBomPurpose(BOM_PURPOSE);
    row.setBomVersion(
        level == 2
                && (STANDARD.equals(code)
                    || ALTERNATIVE.equals(code))
            ? "F006"
            : "F001");
    row.setBomStatus("已核准");
    row.setEffectiveFrom(
        level == 2
                && (STANDARD.equals(code)
                    || ALTERNATIVE.equals(code))
            ? LocalDate.of(2026, 5, 21)
            : LocalDate.of(2026, 1, 1));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    row.setSourceType("U9");
    row.setSourceImportBatchId(IMPORT_BATCH);
    row.setBuildBatchId(BUILD_BATCH);
    row.setBuiltAt(LocalDateTime.of(2026, 7, 7, 13, 50, 44));
    row.setSourceU9RowId(id);
    row.setSourceLineKey(path);
    row.setBusinessUnitType(BUSINESS_UNIT);
    row.setAlternativeGroupKey(groupKey);
    row.setChildType(childType);
    rows.add(row);
  }

  private static void finishRows(
      List<BomRawHierarchy> rows) {
    for (BomRawHierarchy row : rows) {
      boolean hasChild =
          rows.stream()
              .filter(other -> other != row)
              .anyMatch(
                  other ->
                      other.getPath()
                          .startsWith(row.getPath()));
      row.setIsLeaf(hasChild ? 0 : 1);
      if ("9830000025705".equals(
          row.getMaterialCode())) {
        row.setShapeAttr("虚拟");
        row.setMaterialCategory1("1515501");
        row.setMaterialCategory2("包装组件");
      } else if ("1145000300485".equals(
              row.getMaterialCode())
          || "1145000300486".equals(
              row.getMaterialCode())) {
        row.setShapeAttr("委外加工件");
        row.setSourceCategory("其它(商用)");
      } else if (hasChild || row.getLevel() == 0) {
        row.setShapeAttr("制造件");
      } else {
        row.setShapeAttr("采购件");
      }
      row.setCostElementCode(
          "201850547".equals(row.getMaterialCode())
                  || "201850347".equals(
                      row.getMaterialCode())
              ? "No102"
              : "No101");
      if (financeExcludedMaterial(
          row.getMaterialCode())) {
        row.setMaterialCategory1("181841442");
        row.setMaterialCategory2("财务辅料");
      }
    }
  }

  private static boolean financeExcludedMaterial(
      String materialCode) {
    return Set.of(
            "301300030",
            "301300031",
            "301300203",
            "741300023",
            "311034725",
            "751020005",
            "311034930")
        .contains(materialCode);
  }

  private static String materialName(String code) {
    return switch (code) {
      case TOP -> "压力变送器";
      case PARENT -> "芯体组件";
      case STANDARD, ALTERNATIVE -> "芯体部件";
      case "201850547", "201850347" -> "敏感芯片";
      case "721850051" -> "接管";
      case "301060256" -> "拉制铜管";
      case "9830000025705" -> "包装组件";
      case "1145000300485", "1145000300486" -> "导针";
      default -> "真实物料-" + code;
    };
  }

  private static String realGroupKey(
      BomAlternativeGroupKeyGenerator keyGenerator) {
    return keyGenerator.generate(
        new BomAlternativeGroupIdentity(
            PRICE_ORG,
            TOP,
            keyGenerator.parentPathFingerprint(PARENT_PATH),
            PARENT,
            BOM_PURPOSE,
            "F006",
            LocalDate.of(2026, 5, 21),
            LocalDate.of(9999, 12, 31),
            10,
            "010"));
  }

  private static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(
            new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(
        assistant, BomRawHierarchy.class);
    TableInfoHelper.initTableInfo(
        assistant, QuoteBomPreparationRecord.class);
    TableInfoHelper.initTableInfo(
        assistant, BomCostingRow.class);
  }

  record RoundSnapshot(
      QuoteBomAlternativeSelectionResult selection,
      BomAlternativePruneResult pruned,
      List<BomCostingRow> costingRows,
      List<String> costingRowKeys,
      int replaceCount,
      int sourceRefCount,
      QuoteBomAlternativeRebuildResult rebuild) {}

  record EngineSnapshot(
      int inputNodeCount,
      List<String> rowFingerprints,
      List<String> subRefFingerprints,
      List<String> sourceRefFingerprints,
      List<String> warnings) {}
}
