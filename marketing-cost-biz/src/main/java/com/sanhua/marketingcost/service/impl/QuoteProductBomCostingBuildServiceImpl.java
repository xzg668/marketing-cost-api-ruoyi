package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSourceRef;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSourceRefMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BomByproductCostRuleQueryService;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomRepository;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.settlement.BomByproductSettlementAdapter;
import com.sanhua.marketingcost.service.settlement.BomByproductSettlementReadResult;
import com.sanhua.marketingcost.service.settlement.BomSettlementBuildRequest;
import com.sanhua.marketingcost.service.settlement.BomSettlementNode;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildEngine;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildResult;
import com.sanhua.marketingcost.service.settlement.BomSettlementSourceRef;
import com.sanhua.marketingcost.service.settlement.BomSettlementSourceRefCandidate;
import com.sanhua.marketingcost.service.settlement.BomSettlementSubRefCandidate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteProductBomCostingBuildServiceImpl
    implements QuoteProductBomCostingBuildService {

  private static final String PREPARATION_READY = "READY";
  private static final String PRODUCT_TYPE_NON_BARE = "NON_BARE";
  private static final String SOURCE_EFFECTIVE_BOM = "EFFECTIVE_BOM";
  private static final int ACTIVE = 1;

  private final BomSettlementRuleQueryService settlementRuleQueryService;
  private final BomByproductCostRuleQueryService byproductRuleQueryService;
  private final BomByproductSettlementAdapter byproductSettlementAdapter;
  private final BomSettlementRowBuildEngine buildEngine;
  private final QuoteBomPreparationRecordMapper preparationRecordMapper;
  private final QuoteBomStatusMapper statusMapper;
  private final BomCostingRowMapper costingRowMapper;
  private final BomCostingRowSourceRefMapper sourceRefMapper;
  private final BomCostingRowSubRefMapper subRefMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteEffectiveBomRepository effectiveBomRepository;
  private final BomRawHierarchyMapper rawHierarchyMapper;

  public QuoteProductBomCostingBuildServiceImpl(
      BomSettlementRuleQueryService settlementRuleQueryService,
      BomByproductCostRuleQueryService byproductRuleQueryService,
      BomByproductSettlementAdapter byproductSettlementAdapter,
      BomSettlementRowBuildEngine buildEngine,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      QuoteBomStatusMapper statusMapper,
      BomCostingRowMapper costingRowMapper,
      BomCostingRowSourceRefMapper sourceRefMapper,
      BomCostingRowSubRefMapper subRefMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteEffectiveBomRepository effectiveBomRepository,
      BomRawHierarchyMapper rawHierarchyMapper) {
    this.settlementRuleQueryService = settlementRuleQueryService;
    this.byproductRuleQueryService = byproductRuleQueryService;
    this.byproductSettlementAdapter = byproductSettlementAdapter;
    this.buildEngine = buildEngine;
    this.preparationRecordMapper = preparationRecordMapper;
    this.statusMapper = statusMapper;
    this.costingRowMapper = costingRowMapper;
    this.sourceRefMapper = sourceRefMapper;
    this.subRefMapper = subRefMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.effectiveBomRepository = effectiveBomRepository;
    this.rawHierarchyMapper = rawHierarchyMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomCostingBuildResponse buildFromEffectiveBom(
      Long oaFormItemId, String effectiveBuildBatchId) {
    if (oaFormItemId == null) {
      throw new QuoteIngestException("报价产品行 ID 不能为空");
    }
    String buildBatchId = trimToNull(effectiveBuildBatchId);
    if (buildBatchId == null) {
      throw new QuoteIngestException("最终有效BOM构建编号不能为空");
    }
    if (effectiveBomRepository == null) {
      throw new IllegalStateException("最终有效BOM仓储未配置");
    }
    QuoteBomPreparationRecord record = loadActiveRecordByItem(oaFormItemId);
    if (record == null) {
      throw new QuoteIngestException("报价产品行尚未完成 BOM 准备");
    }
    requireBuildable(record);
    List<QuoteEffectiveBomNode> nodes =
        effectiveBomRepository.findNodesByBuildBatchId(buildBatchId);
    if (nodes == null || nodes.isEmpty()) {
      throw new QuoteIngestException("最终有效BOM不存在: " + buildBatchId);
    }
    // 最终有效 BOM 是本次显式重算刚生成的权威输入。准备记录可能仍保存上一次
    // 核算月份，必须以本次构建节点的月份为准，并在成功后回写准备记录。
    String periodMonth =
        requiredText(nodes.getFirst().getCostPeriodMonth(), "最终有效BOM核算月份");
    validateEffectiveNodes(record, buildBatchId, periodMonth, nodes);
    List<PreparedLine> lines = effectiveLines(record, nodes);
    cleanupExisting(record, periodMonth);
    DirectBuildResult built =
        applyRulesAndWrite(
            record,
            YearMonth.parse(periodMonth).atDay(1),
            periodMonth,
            lines,
            buildBatchId);
    updateBuildBatch(record, buildBatchId, periodMonth);
    return response(
        record,
        built.buildBatchId(),
        built.rowsWritten(),
        built.sourceRefsWritten(),
        built.subtreeRequiredCount(),
        built.sourceTypeCounts(),
        built.warnings(),
        periodMonth);
  }

  private List<PreparedLine> effectiveLines(
      QuoteBomPreparationRecord record, List<QuoteEffectiveBomNode> nodes) {
    Map<Long, com.sanhua.marketingcost.entity.BomRawHierarchy> rawById = new HashMap<>();
    if (rawHierarchyMapper != null) {
      List<Long> sourceIds =
          nodes.stream()
              .map(QuoteEffectiveBomNode::getSourceHierarchyId)
              .filter(java.util.Objects::nonNull)
              .distinct()
              .toList();
      if (!sourceIds.isEmpty()) {
        List<com.sanhua.marketingcost.entity.BomRawHierarchy> sourceRows =
            rawHierarchyMapper.selectBatchIds(sourceIds);
        for (com.sanhua.marketingcost.entity.BomRawHierarchy row
            : sourceRows == null
                ? List.<com.sanhua.marketingcost.entity.BomRawHierarchy>of()
                : sourceRows) {
          if (row.getId() != null) {
            rawById.put(row.getId(), row);
          }
        }
      }
    }

    Map<String, QuoteEffectiveBomNode> nodeByKey = new HashMap<>();
    for (QuoteEffectiveBomNode node : nodes) {
      QuoteEffectiveBomNode duplicate = nodeByKey.put(node.getNodeKey(), node);
      if (duplicate != null) {
        throw new QuoteIngestException("最终有效BOM节点键重复: " + node.getNodeKey());
      }
    }
    List<PreparedLine> result = new ArrayList<>();
    for (QuoteEffectiveBomNode node : nodes.stream()
        .sorted(
            Comparator.comparing(
                    QuoteEffectiveBomNode::getNodePath,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                    QuoteEffectiveBomNode::getSortSeq,
                    Comparator.nullsLast(Comparator.naturalOrder())))
        .toList()) {
      com.sanhua.marketingcost.entity.BomRawHierarchy raw =
          rawById.get(node.getSourceHierarchyId());
      if (raw != null
          && (!java.util.Objects.equals(
                  trimToNull(raw.getMaterialCode()),
                  trimToNull(node.getMaterialCode()))
              || !java.util.Objects.equals(
                  trimToNull(raw.getBuildBatchId()),
                  trimToNull(node.getSourceBomBatchId())))) {
        throw new QuoteIngestException(
            "最终有效BOM节点与冻结来源层级证据不一致: " + node.getNodeKey());
      }
      QuoteEffectiveBomNode parent = nodeByKey.get(node.getParentNodeKey());
      result.add(
          new PreparedLine(
              SOURCE_EFFECTIVE_BOM,
              node.getSortSeq(),
              node.getNodeLevel(),
              parent == null ? null : parent.getMaterialCode(),
              node.getMaterialCode(),
              node.getMaterialName(),
              node.getMaterialSpec(),
              settlementShapeLabel(node),
              raw == null ? null : raw.getMaterialCategory1(),
              raw == null ? null : raw.getMaterialCategory2(),
              raw == null ? null : raw.getSourceCategory(),
              raw == null ? null : raw.getCostElementCode(),
              raw == null ? null : raw.getBomPurpose(),
              raw == null ? null : raw.getBomVersion(),
              node.getQtyPerParent(),
              node.getQtyPerTop(),
              node.getNodePath(),
              node.getSourceHierarchyId(),
              null,
              null,
              null,
              null,
              null,
              node.getTopProductCode(),
              null,
              null,
              raw == null ? null : raw.getSourceU9RowId(),
              node.getSourceNodePath(),
              node.getPriceOrgCode(),
              materialOrganizationForPriceOrg(node.getPriceOrgCode(), node.getMaterialCode())));
    }
    return result;
  }

  /**
   * 最终有效 BOM 内部使用稳定英文形态编码，但现有结算引擎及其下游快照沿用 U9 中文形态契约。
   * 在唯一接入边界完成转换，保证无形态覆盖时最终 BOM 替换原始 BOM 后的结算行为完全一致。
   */
  private static String settlementShapeLabel(QuoteEffectiveBomNode node) {
    String effectiveShape = node == null ? null : trimToNull(node.getEffectiveMaterialShape());
    if (effectiveShape == null) {
      throw new QuoteIngestException(
          "最终有效BOM节点缺少最终形态: " + (node == null ? "<空节点>" : node.getMaterialCode()));
    }
    try {
      return QuoteMaterialShape.fromU9(effectiveShape).getLabel();
    } catch (IllegalArgumentException exception) {
      throw new QuoteIngestException(
          "最终有效BOM节点形态非法: " + node.getMaterialCode() + " / " + effectiveShape);
    }
  }

  private void validateEffectiveNodes(
      QuoteBomPreparationRecord record,
      String buildBatchId,
      String periodMonth,
      List<QuoteEffectiveBomNode> nodes) {
    String expectedProduct = requiredText(formalProductCode(record), "最终BOM顶层产品");
    String expectedOrg = requiredOrganization(record).priceOrgCode();
    for (QuoteEffectiveBomNode node : nodes) {
      String nodeOrg = trimToNull(node.getPriceOrgCode());
      boolean organizationMatches =
          expectedOrg.equals(nodeOrg)
              || (MaterialOrganization.PLATE.getPriceOrgCode().equals(expectedOrg)
                  && MaterialOrganization.COMMERCIAL.getPriceOrgCode().equals(nodeOrg));
      if (!buildBatchId.equals(trimToNull(node.getBuildBatchId()))
          || !expectedProduct.equals(trimToNull(node.getTopProductCode()))
          || !periodMonth.equals(trimToNull(node.getCostPeriodMonth()))
          || !organizationMatches) {
        throw new QuoteIngestException(
            "最终有效BOM节点与当前产品、月份、组织或构建编号不一致");
      }
      if (trimToNull(node.getNodeKey()) == null
          || trimToNull(node.getNodePath()) == null
          || trimToNull(node.getMaterialCode()) == null) {
        throw new QuoteIngestException("最终有效BOM存在缺少节点键、路径或料号的记录");
      }
    }
  }

  private static String formalProductCode(QuoteBomPreparationRecord record) {
    if (PRODUCT_TYPE_NON_BARE.equals(record.getProductType())) {
      return trimToNull(record.getQuoteProductCode());
    }
    return firstText(
        firstText(record.getSourceTopProductCode(), record.getReferenceFinishedCode()),
        record.getQuoteProductCode());
  }

  private static String materialOrganizationForPriceOrg(
      String priceOrgCode, String materialCode) {
    try {
      return MaterialOrganization.fromPriceOrgCode(priceOrgCode).getCode();
    } catch (IllegalArgumentException exception) {
      throw new QuoteIngestException(
          "最终有效BOM节点报价组织非法: " + firstText(materialCode, "<空料号>"));
    }
  }

  private DirectBuildResult applyRulesAndWrite(
      QuoteBomPreparationRecord record,
      LocalDate quoteDate,
      String periodMonth,
      List<PreparedLine> inputLines,
      String requiredBuildBatchId) {
    List<PreparedLine> lines =
        inputLines.stream()
            .filter(line -> trimToNull(line.materialCode()) != null)
            .sorted(Comparator.comparing(PreparedLine::path))
            .toList();
    Map<String, List<PreparedLine>> childrenByParentPath = new HashMap<>();
    for (PreparedLine line : lines) {
      String parentPath = parentPathOf(line.path());
      if (parentPath != null) {
        childrenByParentPath.computeIfAbsent(parentPath, ignored -> new ArrayList<>()).add(line);
      }
    }

    String buildBatchId = requiredText(requiredBuildBatchId, "最终有效BOM构建编号");
    LocalDateTime builtAt = LocalDateTime.now();
    QuoteDataOrganization organization = requiredOrganization(record);
    String settlementScope = resolveBusinessUnitType(record.getOaFormItemId());

    // 报价 BOM 入口只负责把正式 BOM / 补录 BOM / 包装参考归一成标准节点；
    // 结算行取舍、上卷、附加行等业务规则统一交给 BomSettlementRowBuildEngine。
    List<BomSettlementNode> nodes = new ArrayList<>();
    for (PreparedLine line : lines) {
      nodes.add(toSettlementNode(record, line, settlementScope,
          childrenByParentPath.getOrDefault(line.path(), List.of()).isEmpty()));
    }
    BomByproductSettlementReadResult byproductRead =
        byproductSettlementAdapter.read(
            nodes, quoteDate, organization.priceOrgCode(), settlementScope, "主制造");
    BomSettlementRowBuildResult built = buildEngine.build(
        new BomSettlementBuildRequest(
            record.getOaNo(),
            record.getQuoteProductCode(),
            quoteDate,
            periodMonth,
            buildBatchId,
            builtAt,
            settlementScope,
            "主制造",
            nodes,
            settlementRuleQueryService.listEnabledCandidates(),
            byproductRead.byproducts(),
            byproductRead.scrapRefs(),
            byproductRuleQueryService.listEnabledCandidates()));

    List<BomCostingRow> costingRows = stampRowsForQuoteItem(record, built.costingRows());
    BomCostingRowAggregation.Result aggregatedRows = BomCostingRowAggregation.aggregate(costingRows);
    Map<String, Long> costingRowIdByPath = new HashMap<>();
    int rowsWritten = writeBuiltRows(aggregatedRows.rows(), costingRowIdByPath);
    aliasCostingRowIds(aggregatedRows.pathAliases(), costingRowIdByPath);
    int subRefsWritten = writeBuiltSubRefs(built.subRefs(), costingRowIdByPath);
    SourceRefWriteResult sourceRefResult =
        writeBuiltSourceRefs(built.sourceRefs(), costingRowIdByPath);

    List<String> warnings = new ArrayList<>();
    warnings.addAll(byproductRead.warnings());
    warnings.addAll(built.warnings());
    return new DirectBuildResult(
        buildBatchId,
        rowsWritten,
        sourceRefResult.sourceRefsWritten(),
        countSubtreeRequired(aggregatedRows.rows()),
        sourceRefResult.sourceTypeCounts(),
        warnings);
  }

  private List<BomCostingRow> stampRowsForQuoteItem(
      QuoteBomPreparationRecord record, List<BomCostingRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    QuoteDataOrganization organization = requiredOrganization(record);
    for (BomCostingRow row : rows) {
      row.setOaFormItemId(record.getOaFormItemId());
      if (!StringUtils.hasText(row.getPriceOrgCode())) {
        row.setPriceOrgCode(organization.priceOrgCode());
      }
      if (!StringUtils.hasText(row.getMaterialOrganizationCode())) {
        row.setMaterialOrganizationCode(organization.materialOrganizationCode());
      }
      if (row.getManualModified() == null) {
        row.setManualModified(0);
      }
    }
    return rows;
  }

  private QuoteDataOrganization requiredOrganization(QuoteBomPreparationRecord record) {
    String priceOrgCode = record == null ? null : trimToNull(record.getPriceOrgCode());
    String materialOrganizationCode =
        record == null ? null : trimToNull(record.getMaterialOrganizationCode());
    if (!StringUtils.hasText(priceOrgCode) || !StringUtils.hasText(materialOrganizationCode)) {
      throw new QuoteIngestException("BOM 准备记录缺少上游组织，不能生成结算行");
    }
    return MaterialOrganization.normalizeQuoteDataOrganization(
        new QuoteDataOrganization(priceOrgCode, materialOrganizationCode));
  }

  private String resolveBusinessUnitType(Long oaFormItemId) {
    OaFormItem item = oaFormItemId == null ? null : oaFormItemMapper.selectById(oaFormItemId);
    String businessUnitType = firstText(
        item == null ? null : item.getBusinessUnitType(),
        BusinessUnitContext.getCurrentBusinessUnitType());
    if (!StringUtils.hasText(businessUnitType)) {
      throw new QuoteIngestException("报价产品行缺少业务单元，不能生成结算行");
    }
    return businessUnitType;
  }

  private BomSettlementNode toSettlementNode(
      QuoteBomPreparationRecord record, PreparedLine line, String buType, boolean leaf) {
    return new BomSettlementNode(
        line.sourceRawHierarchyId(),
        record.getQuoteProductCode(),
        line.parentCode(),
        line.materialCode(),
        line.level(),
        line.path(),
        line.qtyPerParent(),
        line.qtyPerTop(),
        line.materialName(),
        line.materialSpec(),
        line.shapeAttr(),
        line.sourceCategory(),
        line.costElementCode(),
        line.mainCategoryCode(),
        firstText(line.mainCategoryName(), line.mainCategoryCode()),
        line.mainCategoryCode(),
        firstText(line.bomPurpose(), "主制造"),
        line.bomVersion(),
        null,
        leaf ? 1 : 0,
        null,
        null,
        null,
        firstText(line.priceOrgCode(), record.getPriceOrgCode()),
        firstText(line.materialOrganizationCode(), record.getMaterialOrganizationCode()),
        buType,
        settlementSourceRef(record, line));
  }

  private BomSettlementSourceRef settlementSourceRef(
      QuoteBomPreparationRecord record, PreparedLine line) {
    return new BomSettlementSourceRef(
        record.getOaNo(),
        record.getOaFormItemId(),
        record.getQuoteProductCode(),
        line.sourceType(),
        line.sourceRawHierarchyId(),
        record.getId(),
        line.supplementVersionId(),
        line.supplementDetailId(),
        line.packageReferenceId(),
        line.packageReferenceDetailId(),
        line.referenceFinishedCode(),
        firstText(line.sourceTopProductCode(), record.getSourceTopProductCode()),
        line.sourceSnapshotId(),
        line.sourceSnapshotDetailId(),
        line.sourceU9BomId(),
        firstText(line.sourcePath(), line.path()));
  }

  private int writeBuiltRows(List<BomCostingRow> rows, Map<String, Long> costingRowIdByPath) {
    int rowsWritten = 0;
    for (BomCostingRow row : rows == null ? List.<BomCostingRow>of() : rows) {
      if (costingRowMapper.insert(row) <= 0) {
        continue;
      }
      rowsWritten++;
      costingRowIdByPath.put(row.getPath(), row.getId());
    }
    return rowsWritten;
  }

  private static void aliasCostingRowIds(
      Map<String, String> pathAliases, Map<String, Long> costingRowIdByPath) {
    if (pathAliases == null || pathAliases.isEmpty() || costingRowIdByPath == null) {
      return;
    }
    for (Map.Entry<String, String> alias : pathAliases.entrySet()) {
      Long id = costingRowIdByPath.get(alias.getValue());
      if (id != null) {
        costingRowIdByPath.put(alias.getKey(), id);
      }
    }
  }

  private int writeBuiltSubRefs(
      List<BomSettlementSubRefCandidate> candidates, Map<String, Long> costingRowIdByPath) {
    int written = 0;
    for (BomSettlementSubRefCandidate candidate
        : candidates == null ? List.<BomSettlementSubRefCandidate>of() : candidates) {
      Long costingRowId = costingRowIdByPath.get(candidate.costingRowPath());
      if (costingRowId == null || candidate.subRef() == null) {
        continue;
      }
      candidate.subRef().setCostingRowId(costingRowId);
      subRefMapper.insert(candidate.subRef());
      written++;
    }
    return written;
  }

  private SourceRefWriteResult writeBuiltSourceRefs(
      List<BomSettlementSourceRefCandidate> candidates, Map<String, Long> costingRowIdByPath) {
    int written = 0;
    Map<String, Integer> sourceTypeCounts = new LinkedHashMap<>();
    for (BomSettlementSourceRefCandidate candidate
        : candidates == null ? List.<BomSettlementSourceRefCandidate>of() : candidates) {
      Long costingRowId = costingRowIdByPath.get(candidate.costingRowPath());
      if (costingRowId == null || candidate.sourceRef() == null) {
        continue;
      }
      candidate.sourceRef().setCostingRowId(costingRowId);
      sourceRefMapper.insert(candidate.sourceRef());
      written++;
      increment(sourceTypeCounts, candidate.sourceRef().getSourcePartType(), 1);
    }
    return new SourceRefWriteResult(written, sourceTypeCounts);
  }

  private static int countSubtreeRequired(List<BomCostingRow> rows) {
    int count = 0;
    for (BomCostingRow row : rows == null ? List.<BomCostingRow>of() : rows) {
      if (Integer.valueOf(1).equals(row.getSubtreeCostRequired())) {
        count++;
      }
    }
    return count;
  }

  private void requireBuildable(QuoteBomPreparationRecord record) {
    if (!PREPARATION_READY.equals(record.getPreparationStatus())) {
      throw new QuoteIngestException("BOM 准备结果尚未就绪，不能生成结算行");
    }
  }

  private void cleanupExisting(QuoteBomPreparationRecord record, String periodMonth) {
    List<BomCostingRow> existingRows =
        costingRowMapper.selectList(
            Wrappers.<BomCostingRow>lambdaQuery()
                .select(BomCostingRow::getId)
                .eq(BomCostingRow::getOaNo, record.getOaNo())
                .eq(BomCostingRow::getOaFormItemId, record.getOaFormItemId())
                .eq(BomCostingRow::getTopProductCode, record.getQuoteProductCode())
                .eq(BomCostingRow::getPeriodMonth, periodMonth));
    List<Long> existingRowIds = new ArrayList<>();
    for (BomCostingRow row : existingRows == null ? List.<BomCostingRow>of() : existingRows) {
      if (row.getId() != null) {
        existingRowIds.add(row.getId());
      }
    }
    if (!existingRowIds.isEmpty()) {
      subRefMapper.delete(
          Wrappers.<BomCostingRowSubRef>lambdaQuery()
              .in(BomCostingRowSubRef::getCostingRowId, existingRowIds));
    }
    if (!existingRowIds.isEmpty()) {
      sourceRefMapper.delete(
          Wrappers.<BomCostingRowSourceRef>lambdaQuery()
              .in(
                  BomCostingRowSourceRef::getCostingRowId,
                  existingRowIds));
    }
    costingRowMapper.delete(
        Wrappers.<BomCostingRow>lambdaQuery()
            .eq(BomCostingRow::getOaNo, record.getOaNo())
            .eq(BomCostingRow::getOaFormItemId, record.getOaFormItemId())
            .eq(BomCostingRow::getTopProductCode, record.getQuoteProductCode())
            .eq(BomCostingRow::getPeriodMonth, periodMonth));
  }

  private QuoteBomPreparationRecord loadActiveRecordByItem(Long oaFormItemId) {
    return preparationRecordMapper.selectOne(
        Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
            .eq(QuoteBomPreparationRecord::getOaFormItemId, oaFormItemId)
            .eq(QuoteBomPreparationRecord::getActiveFlag, ACTIVE)
            .orderByDesc(QuoteBomPreparationRecord::getUpdatedAt)
            .orderByDesc(QuoteBomPreparationRecord::getId)
            .last("LIMIT 1"));
  }

  private void updateBuildBatch(
      QuoteBomPreparationRecord record, String buildBatchId, String periodMonth) {
    record.setCostingBuildBatchId(buildBatchId);
    record.setCostPeriodMonth(periodMonth);
    record.setUpdatedAt(LocalDateTime.now());
    preparationRecordMapper.updateById(record);
    if (record.getQuoteBomStatusId() != null) {
      QuoteBomStatus status = statusMapper.selectById(record.getQuoteBomStatusId());
      if (status != null) {
        status.setCostingBuildBatchId(buildBatchId);
        status.setCostPeriodMonth(periodMonth);
        status.setUpdatedAt(LocalDateTime.now());
        statusMapper.updateById(status);
      }
    }
  }

  private QuoteBomCostingBuildResponse response(
      QuoteBomPreparationRecord record,
      String buildBatchId,
      int rowsWritten,
      int sourceRefsWritten,
      int subtreeRequiredCount,
      Map<String, Integer> sourceTypeCounts,
      List<String> warnings,
      String periodMonth) {
    return new QuoteBomCostingBuildResponse(
        record.getId(),
        record.getOaFormItemId(),
        record.getOaNo(),
        record.getQuoteProductCode(),
        record.getProductType(),
        periodMonth,
        buildBatchId,
        rowsWritten,
        sourceRefsWritten,
        subtreeRequiredCount,
        sourceTypeCounts == null ? Map.of() : Map.copyOf(sourceTypeCounts),
        warnings == null ? List.of() : List.copyOf(warnings),
        LocalDateTime.now());
  }

  private static String parentPathOf(String path) {
    if (path == null || path.length() < 2) {
      return null;
    }
    String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    int lastSlash = trimmed.lastIndexOf('/');
    if (lastSlash <= 0) {
      return null;
    }
    return trimmed.substring(0, lastSlash + 1);
  }

  private static void increment(Map<String, Integer> counts, String sourceType, int delta) {
    if (delta <= 0) {
      return;
    }
    counts.put(sourceType, counts.getOrDefault(sourceType, 0) + delta);
  }

  private static String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String requiredText(String value, String field) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new QuoteIngestException(field + "不能为空");
    }
    return normalized;
  }

  private record PreparedLine(
      String sourceType,
      Integer lineNo,
      Integer level,
      String parentCode,
      String materialCode,
      String materialName,
      String materialSpec,
      String shapeAttr,
      String mainCategoryCode,
      String mainCategoryName,
      String sourceCategory,
      String costElementCode,
      String bomPurpose,
      String bomVersion,
      BigDecimal qtyPerParent,
      BigDecimal qtyPerTop,
      String path,
      Long sourceRawHierarchyId,
      Long supplementVersionId,
      Long supplementDetailId,
      Long packageReferenceId,
      Long packageReferenceDetailId,
      String referenceFinishedCode,
      String sourceTopProductCode,
      Long sourceSnapshotId,
      Long sourceSnapshotDetailId,
      Long sourceU9BomId,
      String sourcePath,
      String priceOrgCode,
      String materialOrganizationCode) {}

  private record DirectBuildResult(
      String buildBatchId,
      int rowsWritten,
      int sourceRefsWritten,
      int subtreeRequiredCount,
      Map<String, Integer> sourceTypeCounts,
      List<String> warnings) {}

  private record SourceRefWriteResult(
      int sourceRefsWritten,
      Map<String, Integer> sourceTypeCounts) {}

}
