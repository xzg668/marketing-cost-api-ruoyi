package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSourceRef;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.BomSupplementTask;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomPackageReferenceDetail;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSourceRefMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BomByproductCostRuleQueryService;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteProductBomCostingBuildServiceImpl
    implements QuoteProductBomCostingBuildService {

  private static final String PREPARATION_READY = "READY";
  private static final String REVIEW_APPROVED = "APPROVED";
  private static final String TASK_APPROVED = "APPROVED";
  private static final String VERSION_APPROVED = "APPROVED";
  private static final String REFERENCE_APPROVED = "APPROVED";
  private static final String PRODUCT_TYPE_NON_BARE = "NON_BARE";
  private static final String SCOPE_NON_BARE_FULL_BOM = "NON_BARE_FULL_BOM";
  private static final String SCOPE_BARE_BODY_BOM = "BARE_BODY_BOM";
  private static final String SOURCE_RAW_PRODUCT_BOM = "RAW_PRODUCT_BOM";
  private static final String SOURCE_BARE_PRODUCT_BOM = "BARE_PRODUCT_BOM";
  private static final String SOURCE_REFERENCED_PACKAGE = "REFERENCED_PACKAGE";
  private static final String SOURCE_MANUAL_SUPPLEMENT = "MANUAL_SUPPLEMENT";
  private static final String SOURCE_EFFECTIVE_BOM = "EFFECTIVE_BOM";
  private static final int ACTIVE = 1;

  private final QuoteProductBomPreparationService preparationService;
  private final FormalBomReadService formalBomReadService;
  private final BomSettlementRuleQueryService settlementRuleQueryService;
  private final BomByproductCostRuleQueryService byproductRuleQueryService;
  private final BomByproductSettlementAdapter byproductSettlementAdapter;
  private final BomSettlementRowBuildEngine buildEngine;
  private final QuoteBomPreparationRecordMapper preparationRecordMapper;
  private final QuoteBomStatusMapper statusMapper;
  private final BomSupplementTaskMapper taskMapper;
  private final QuoteBomSupplementVersionMapper supplementVersionMapper;
  private final QuoteBomSupplementDetailMapper supplementDetailMapper;
  private final QuoteBomPackageReferenceMapper packageReferenceMapper;
  private final QuoteBomPackageReferenceDetailMapper packageReferenceDetailMapper;
  private final BomCostingRowMapper costingRowMapper;
  private final BomCostingRowSourceRefMapper sourceRefMapper;
  private final BomCostingRowSubRefMapper subRefMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteEffectiveBomRepository effectiveBomRepository;
  private final BomRawHierarchyMapper rawHierarchyMapper;

  @Autowired
  public QuoteProductBomCostingBuildServiceImpl(
      QuoteProductBomPreparationService preparationService,
      FormalBomReadService formalBomReadService,
      BomSettlementRuleQueryService settlementRuleQueryService,
      BomByproductCostRuleQueryService byproductRuleQueryService,
      BomByproductSettlementAdapter byproductSettlementAdapter,
      BomSettlementRowBuildEngine buildEngine,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      QuoteBomStatusMapper statusMapper,
      BomSupplementTaskMapper taskMapper,
      QuoteBomSupplementVersionMapper supplementVersionMapper,
      QuoteBomSupplementDetailMapper supplementDetailMapper,
      QuoteBomPackageReferenceMapper packageReferenceMapper,
      QuoteBomPackageReferenceDetailMapper packageReferenceDetailMapper,
      BomCostingRowMapper costingRowMapper,
      BomCostingRowSourceRefMapper sourceRefMapper,
      BomCostingRowSubRefMapper subRefMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteEffectiveBomRepository effectiveBomRepository,
      BomRawHierarchyMapper rawHierarchyMapper) {
    this.preparationService = preparationService;
    this.formalBomReadService = formalBomReadService;
    this.settlementRuleQueryService = settlementRuleQueryService;
    this.byproductRuleQueryService = byproductRuleQueryService;
    this.byproductSettlementAdapter = byproductSettlementAdapter;
    this.buildEngine = buildEngine;
    this.preparationRecordMapper = preparationRecordMapper;
    this.statusMapper = statusMapper;
    this.taskMapper = taskMapper;
    this.supplementVersionMapper = supplementVersionMapper;
    this.supplementDetailMapper = supplementDetailMapper;
    this.packageReferenceMapper = packageReferenceMapper;
    this.packageReferenceDetailMapper = packageReferenceDetailMapper;
    this.costingRowMapper = costingRowMapper;
    this.sourceRefMapper = sourceRefMapper;
    this.subRefMapper = subRefMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.effectiveBomRepository = effectiveBomRepository;
    this.rawHierarchyMapper = rawHierarchyMapper;
  }

  /** 保留旧单元测试和非Spring调用的构造方式；effective专用入口要求完整依赖。 */
  public QuoteProductBomCostingBuildServiceImpl(
      QuoteProductBomPreparationService preparationService,
      FormalBomReadService formalBomReadService,
      BomSettlementRuleQueryService settlementRuleQueryService,
      BomByproductCostRuleQueryService byproductRuleQueryService,
      BomByproductSettlementAdapter byproductSettlementAdapter,
      BomSettlementRowBuildEngine buildEngine,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      QuoteBomStatusMapper statusMapper,
      BomSupplementTaskMapper taskMapper,
      QuoteBomSupplementVersionMapper supplementVersionMapper,
      QuoteBomSupplementDetailMapper supplementDetailMapper,
      QuoteBomPackageReferenceMapper packageReferenceMapper,
      QuoteBomPackageReferenceDetailMapper packageReferenceDetailMapper,
      BomCostingRowMapper costingRowMapper,
      BomCostingRowSourceRefMapper sourceRefMapper,
      BomCostingRowSubRefMapper subRefMapper,
      OaFormItemMapper oaFormItemMapper) {
    this(
        preparationService,
        formalBomReadService,
        settlementRuleQueryService,
        byproductRuleQueryService,
        byproductSettlementAdapter,
        buildEngine,
        preparationRecordMapper,
        statusMapper,
        taskMapper,
        supplementVersionMapper,
        supplementDetailMapper,
        packageReferenceMapper,
        packageReferenceDetailMapper,
        costingRowMapper,
        sourceRefMapper,
        subRefMapper,
        oaFormItemMapper,
        null,
        null);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomCostingBuildResponse buildByOaFormItem(Long oaFormItemId) {
    return buildByOaFormItem(oaFormItemId, null);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomCostingBuildResponse buildByOaFormItem(Long oaFormItemId, String periodMonth) {
    return buildByOaFormItem(oaFormItemId, periodMonth, LocalDate.now());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomCostingBuildResponse buildByOaFormItem(
      Long oaFormItemId, String periodMonth, LocalDate quoteDate) {
    if (oaFormItemId == null) {
      throw new QuoteIngestException("报价产品行 ID 不能为空");
    }
    QuoteBomPreparationRecord record = loadActiveRecordByItem(oaFormItemId);
    LocalDate buildQuoteDate = resolveQuoteDate(quoteDate);
    if (record == null
        || !PREPARATION_READY.equals(record.getPreparationStatus())
        || missingOrganization(record)) {
      preparationService.prepareByOaFormItem(oaFormItemId, buildQuoteDate);
      record = loadActiveRecordByItem(oaFormItemId);
    }
    if (record == null) {
      throw new QuoteIngestException("报价产品行尚未完成 BOM 准备");
    }
    return build(record, null, false, periodMonth, buildQuoteDate);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomCostingBuildResponse buildByTask(Long taskId) {
    BomSupplementTask task = loadTask(taskId);
    QuoteBomPreparationRecord record = loadActiveRecordByTask(taskId);
    if (record == null) {
      throw new QuoteIngestException("补录任务未关联 BOM 准备记录");
    }
    return build(record, task, true, null, LocalDate.now());
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
    requireBuildable(record, null, false);
    String periodMonth = requiredText(record.getCostPeriodMonth(), "BOM准备记录核算月份");
    List<QuoteEffectiveBomNode> nodes =
        effectiveBomRepository.findNodesByBuildBatchId(buildBatchId);
    if (nodes == null || nodes.isEmpty()) {
      throw new QuoteIngestException("最终有效BOM不存在: " + buildBatchId);
    }
    validateEffectiveNodes(record, buildBatchId, periodMonth, nodes);
    requireNoManualRows(record, periodMonth);
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

  private QuoteBomCostingBuildResponse build(
      QuoteBomPreparationRecord record,
      BomSupplementTask task,
      boolean requireApprovedTask,
      String requestedPeriodMonth,
      LocalDate requestedQuoteDate) {
    requireBuildable(record, task, requireApprovedTask);
    LocalDate quoteDate = resolveQuoteDate(requestedQuoteDate);
    String periodMonth = resolveBuildPeriod(record, requestedPeriodMonth, quoteDate).toString();
    QuoteBomSupplementVersion approvedVersion = latestApprovedSupplementVersion(record);
    QuoteBomPackageReference approvedReference = latestApprovedPackageReference(record);
    if (PRODUCT_TYPE_NON_BARE.equals(record.getProductType())
        && approvedVersion == null
        && approvedReference == null) {
      return buildFormalOnly(record, quoteDate, periodMonth, SOURCE_RAW_PRODUCT_BOM);
    }
    return buildPreparedRows(record, quoteDate, periodMonth, approvedVersion, approvedReference);
  }

  private QuoteBomCostingBuildResponse buildFormalOnly(
      QuoteBomPreparationRecord record,
      LocalDate quoteDate,
      String periodMonth,
      String sourceType) {
    List<PreparedLine> lines =
        loadFormalLines(record, sourceType, periodMonth, quoteDate);
    if (lines.isEmpty()) {
      throw new QuoteIngestException(
          "正式 BOM 准备结果为空，不能生成结算行");
    }
    cleanupExisting(record, periodMonth);
    DirectBuildResult built =
        applyRulesAndWrite(record, quoteDate, periodMonth, lines);
    updateBuildBatch(record, built.buildBatchId(), periodMonth);
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

  private QuoteBomCostingBuildResponse buildPreparedRows(
      QuoteBomPreparationRecord record,
      LocalDate quoteDate,
      String periodMonth,
      QuoteBomSupplementVersion approvedVersion,
      QuoteBomPackageReference approvedReference) {
    List<PreparedLine> lines = new ArrayList<>();
    if (approvedVersion != null) {
      lines.addAll(loadSupplementLines(record, approvedVersion));
    } else {
      String bodySourceType =
          PRODUCT_TYPE_NON_BARE.equals(record.getProductType())
              ? SOURCE_RAW_PRODUCT_BOM
              : SOURCE_BARE_PRODUCT_BOM;
      lines.addAll(loadFormalLines(record, bodySourceType, periodMonth, quoteDate));
    }
    if (!PRODUCT_TYPE_NON_BARE.equals(record.getProductType())) {
      if (approvedReference == null) {
        throw new QuoteIngestException("裸品生成结算行需要已审核包装参考");
      }
      lines.addAll(loadPackageLines(record, approvedReference));
    }
    if (lines.isEmpty()) {
      throw new QuoteIngestException("完整 BOM 准备结果为空，不能生成结算行");
    }
    cleanupExisting(record, periodMonth);
    DirectBuildResult built = applyRulesAndWrite(record, quoteDate, periodMonth, lines);
    updateBuildBatch(record, built.buildBatchId(), periodMonth);
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

  private List<PreparedLine> loadFormalLines(
      QuoteBomPreparationRecord record, String sourceType, String periodMonth, LocalDate quoteDate) {
    String formalProductCode = formalProductCode(record);
    QuoteDataOrganization organization = requiredOrganization(record);
    FormalBomReadResult formal =
        formalBomReadService.read(
            new QuoteBomReadContext(
                record.getOaNo(),
                record.getOaFormItemId(),
                formalProductCode,
                periodMonth,
                organization.priceOrgCode(),
                organization.materialOrganizationCode(),
                resolveBusinessUnitType(record.getOaFormItemId()),
                "主制造",
                quoteDate));
    if (formal == null || !formal.found()) {
      throw new QuoteIngestException(
          "正式 BOM 不可用: " + (formal == null ? formalProductCode + " 未返回读取结果" : formal.gapMessage()));
    }
    List<PreparedLine> lines = new ArrayList<>();
    for (QuoteBomSourceLineDto line : formal.lines()) {
      lines.add(
          new PreparedLine(
              sourceType,
              line.lineNo(),
              line.level(),
              line.parentCode(),
              line.materialCode(),
              line.materialName(),
              line.materialSpec(),
              line.shapeAttr(),
              line.mainCategoryCode(),
              line.mainCategoryName(),
              line.sourceCategory(),
              line.costElementCode(),
              line.bomPurpose(),
              line.bomVersion(),
              line.qtyPerParent(),
              line.qtyPerTop(),
              normalizePath(record.getQuoteProductCode(), line.materialCode(), line.path(), line.lineNo()),
              line.sourceRawHierarchyId(),
              null,
              null,
              null,
              null,
              null,
              null,
              line.topProductCode(),
              null,
              null,
              line.sourceU9BomId(),
              line.path(),
              line.priceOrgCode(),
              line.materialOrganizationCode()));
    }
    return lines;
  }

  private String formalProductCode(QuoteBomPreparationRecord record) {
    if (record == null) {
      return null;
    }
    if (PRODUCT_TYPE_NON_BARE.equals(record.getProductType())) {
      return trimToNull(record.getQuoteProductCode());
    }
    return firstText(
        firstText(record.getSourceTopProductCode(), record.getReferenceFinishedCode()),
        record.getQuoteProductCode());
  }

  private String resolveProductName(Long oaFormItemId) {
    if (oaFormItemId == null) {
      return null;
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    return item == null ? null : item.getProductName();
  }

  private String resolveBusinessUnitType(Long oaFormItemId) {
    OaFormItem item = oaFormItemId == null ? null : oaFormItemMapper.selectById(oaFormItemId);
    String businessUnitType =
        firstText(
            item == null ? null : item.getBusinessUnitType(),
            BusinessUnitContext.getCurrentBusinessUnitType());
    if (!StringUtils.hasText(businessUnitType)) {
      throw new QuoteIngestException("报价产品行缺少业务单元，不能生成结算行");
    }
    return businessUnitType;
  }

  private List<PreparedLine> loadSupplementLines(
      QuoteBomPreparationRecord record, QuoteBomSupplementVersion version) {
    String expectedScope =
        PRODUCT_TYPE_NON_BARE.equals(record.getProductType())
            ? SCOPE_NON_BARE_FULL_BOM
            : SCOPE_BARE_BODY_BOM;
    if (!expectedScope.equals(version.getSupplementScope())) {
      throw new QuoteIngestException("补录 BOM 类型与当前报价产品不匹配");
    }
    List<QuoteBomSupplementDetail> details =
        supplementDetailMapper.selectList(
            Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
                .eq(QuoteBomSupplementDetail::getSupplementVersionId, version.getId())
                .orderByAsc(QuoteBomSupplementDetail::getLineNo)
                .orderByAsc(QuoteBomSupplementDetail::getId));
    List<PreparedLine> lines = new ArrayList<>();
    for (QuoteBomSupplementDetail detail : details == null ? List.<QuoteBomSupplementDetail>of() : details) {
      lines.add(
          new PreparedLine(
              SOURCE_MANUAL_SUPPLEMENT,
              detail.getLineNo(),
              detail.getLevel(),
              detail.getParentCode(),
              detail.getMaterialCode(),
              detail.getMaterialName(),
              detail.getMaterialSpec(),
              detail.getShapeAttr(),
              detail.getMainCategoryCode(),
              detail.getMainCategoryCode(),
              detail.getSourceCategory(),
              detail.getCostElementCode(),
              detail.getBomPurpose(),
              detail.getBomVersion(),
              detail.getQtyPerParent(),
              detail.getQtyPerTop(),
              normalizePath(record.getQuoteProductCode(), detail.getMaterialCode(), detail.getPath(), detail.getLineNo()),
              detail.getSourceRawHierarchyId(),
              version.getTaskId(),
              version.getId(),
              detail.getId(),
              null,
              null,
              null,
              null,
              null,
              null,
              detail.getSourceU9BomId(),
              detail.getPath(),
              record.getPriceOrgCode(),
              record.getMaterialOrganizationCode()));
    }
    return lines;
  }

  private List<PreparedLine> loadPackageLines(
      QuoteBomPreparationRecord record, QuoteBomPackageReference reference) {
    List<QuoteBomPackageReferenceDetail> details =
        packageReferenceDetailMapper.selectList(
            Wrappers.<QuoteBomPackageReferenceDetail>lambdaQuery()
                .eq(QuoteBomPackageReferenceDetail::getPackageReferenceId, reference.getId())
                .eq(QuoteBomPackageReferenceDetail::getSelectedFlag, 1)
                .orderByAsc(QuoteBomPackageReferenceDetail::getLineNo)
                .orderByAsc(QuoteBomPackageReferenceDetail::getId));
    List<PreparedLine> lines = new ArrayList<>();
    int fallback = 1;
    for (QuoteBomPackageReferenceDetail detail : details == null ? List.<QuoteBomPackageReferenceDetail>of() : details) {
      Integer lineNo = detail.getLineNo() == null ? fallback : detail.getLineNo();
      lines.add(
          new PreparedLine(
              SOURCE_REFERENCED_PACKAGE,
              lineNo,
              1,
              firstText(detail.getPackageParentCode(), record.getQuoteProductCode()),
              detail.getPackageMaterialCode(),
              detail.getPackageMaterialName(),
              detail.getPackageMaterialSpec(),
              detail.getPackageMaterialShapeAttr(),
              detail.getPackageMaterialMainCategoryCode(),
              detail.getPackageMaterialMainCategoryCode(),
              null,
              null,
              null,
              null,
              firstNonNull(detail.getAdjustedChildQtyPerParent(), detail.getChildQtyPerParent()),
              firstNonNull(detail.getAdjustedChildQtyPerTop(), detail.getQtyPerTop()),
              packagePath(record.getQuoteProductCode(), detail, fallback),
              detail.getSourceRawHierarchyId(),
              reference.getTaskId(),
              null,
              null,
              reference.getId(),
              detail.getId(),
              reference.getReferenceFinishedCode(),
              reference.getSourceTopProductCode(),
              detail.getSnapshotId(),
              detail.getSnapshotDetailId(),
              detail.getSourceU9BomId(),
              detail.getSourcePath(),
              record.getPriceOrgCode(),
              record.getMaterialOrganizationCode()));
      fallback++;
    }
    return lines;
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
              null,
              node.getTopProductCode(),
              null,
              null,
              raw == null ? null : raw.getSourceU9RowId(),
              node.getSourceNodePath(),
              node.getPriceOrgCode(),
              record.getMaterialOrganizationCode()));
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
      if (!buildBatchId.equals(trimToNull(node.getBuildBatchId()))
          || !expectedProduct.equals(trimToNull(node.getTopProductCode()))
          || !periodMonth.equals(trimToNull(node.getCostPeriodMonth()))
          || !expectedOrg.equals(trimToNull(node.getPriceOrgCode()))) {
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

  private void requireNoManualRows(
      QuoteBomPreparationRecord record, String periodMonth) {
    Long manualCount =
        costingRowMapper.selectCount(
            Wrappers.<BomCostingRow>lambdaQuery()
                .eq(BomCostingRow::getOaNo, record.getOaNo())
                .eq(BomCostingRow::getOaFormItemId, record.getOaFormItemId())
                .eq(BomCostingRow::getTopProductCode, record.getQuoteProductCode())
                .eq(BomCostingRow::getPeriodMonth, periodMonth)
                .eq(BomCostingRow::getManualModified, 1));
    if (manualCount != null && manualCount > 0) {
      throw new QuoteIngestException("当前产品存在人工修改的结算行，不能自动重建覆盖");
    }
  }

  private DirectBuildResult applyRulesAndWrite(
      QuoteBomPreparationRecord record,
      LocalDate quoteDate,
      String periodMonth,
      List<PreparedLine> inputLines) {
    return applyRulesAndWrite(record, quoteDate, periodMonth, inputLines, null);
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

    String buildBatchId =
        requiredBuildBatchId == null
            ? generateBuildBatchId()
            : requiredText(requiredBuildBatchId, "最终有效BOM构建编号");
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

  private static boolean missingOrganization(QuoteBomPreparationRecord record) {
    return record != null
        && (!StringUtils.hasText(record.getPriceOrgCode())
            || !StringUtils.hasText(record.getMaterialOrganizationCode()));
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
        line.sourceTaskId(),
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

  private void requireBuildable(
      QuoteBomPreparationRecord record, BomSupplementTask task, boolean requireApprovedTask) {
    if (!PREPARATION_READY.equals(record.getPreparationStatus())) {
      throw new QuoteIngestException("BOM 准备结果尚未就绪，不能生成结算行");
    }
    if (requireApprovedTask) {
      if (task == null || !TASK_APPROVED.equals(task.getTaskStatus())) {
        throw new QuoteIngestException("仅审核通过的补录任务允许生成结算行");
      }
      if (!REVIEW_APPROVED.equals(record.getReviewStatus())) {
        throw new QuoteIngestException("补录任务未审核通过，不能生成结算行");
      }
      return;
    }
    if (record.getTaskId() != null && !REVIEW_APPROVED.equals(record.getReviewStatus())) {
      throw new QuoteIngestException("补录任务未审核通过，不能生成结算行");
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

  private QuoteBomPreparationRecord loadActiveRecordByTask(Long taskId) {
    return preparationRecordMapper.selectOne(
        Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
            .eq(QuoteBomPreparationRecord::getTaskId, taskId)
            .eq(QuoteBomPreparationRecord::getActiveFlag, ACTIVE)
            .orderByDesc(QuoteBomPreparationRecord::getUpdatedAt)
            .orderByDesc(QuoteBomPreparationRecord::getId)
            .last("LIMIT 1"));
  }

  private BomSupplementTask loadTask(Long taskId) {
    if (taskId == null) {
      throw new QuoteIngestException("任务 ID 不能为空");
    }
    BomSupplementTask task = taskMapper.selectById(taskId);
    if (task == null) {
      throw new QuoteIngestException("BOM 补录任务不存在: " + taskId);
    }
    return task;
  }

  private QuoteBomSupplementVersion latestApprovedSupplementVersion(QuoteBomPreparationRecord record) {
    Long taskId = firstNonNull(record.getTaskId(), record.getReusedFromTaskId());
    if (taskId == null) {
      return null;
    }
    return supplementVersionMapper.selectOne(
        Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
            .eq(QuoteBomSupplementVersion::getTaskId, taskId)
            .eq(QuoteBomSupplementVersion::getVersionStatus, VERSION_APPROVED)
            .eq(QuoteBomSupplementVersion::getActiveFlag, ACTIVE)
            .orderByDesc(QuoteBomSupplementVersion::getUpdatedAt)
            .orderByDesc(QuoteBomSupplementVersion::getId)
            .last("LIMIT 1"));
  }

  private QuoteBomPackageReference latestApprovedPackageReference(QuoteBomPreparationRecord record) {
    Long taskId = firstNonNull(record.getTaskId(), record.getReusedFromTaskId());
    if (taskId == null) {
      return null;
    }
    return packageReferenceMapper.selectOne(
        Wrappers.<QuoteBomPackageReference>lambdaQuery()
            .eq(QuoteBomPackageReference::getTaskId, taskId)
            .eq(QuoteBomPackageReference::getReferenceStatus, REFERENCE_APPROVED)
            .eq(QuoteBomPackageReference::getActiveFlag, ACTIVE)
            .orderByDesc(QuoteBomPackageReference::getUpdatedAt)
            .orderByDesc(QuoteBomPackageReference::getId)
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
        record.getTaskId(),
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

  private YearMonth resolveBuildPeriod(
      QuoteBomPreparationRecord record, String requestedPeriodMonth, LocalDate quoteDate) {
    String value = trimToNull(requestedPeriodMonth);
    if (value == null && record != null) {
      value = trimToNull(record.getCostPeriodMonth());
    }
    if (value == null) {
      return YearMonth.from(quoteDate);
    }
    return YearMonth.parse(value);
  }

  private LocalDate resolveQuoteDate(LocalDate requestedQuoteDate) {
    return requestedQuoteDate == null ? LocalDate.now() : requestedQuoteDate;
  }

  private Map<String, Integer> countSourceTypes(String sourceType, int count) {
    if (count <= 0) {
      return Map.of();
    }
    return Map.of(sourceType, count);
  }

  private static String normalizePath(
      String quoteProductCode, String materialCode, String path, Integer lineNo) {
    String normalized = trimToNull(path);
    if (normalized != null) {
      return normalized.endsWith("/") ? normalized : normalized + "/";
    }
    return "/" + trimToNull(quoteProductCode) + "/" + trimToNull(materialCode) + "-" + (lineNo == null ? 0 : lineNo) + "/";
  }

  private static String packagePath(
      String quoteProductCode, QuoteBomPackageReferenceDetail detail, int fallback) {
    return "/"
        + trimToNull(quoteProductCode)
        + "/__PACKAGE__/"
        + trimToNull(detail.getPackageMaterialCode())
        + "-"
        + (detail.getId() == null ? fallback : detail.getId())
        + "/";
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

  private static String generateBuildBatchId() {
    return "qbp_" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private static <T> T firstNonNull(T first, T second) {
    return first == null ? second : first;
  }

  private static BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
    return first == null ? second : first;
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
      Long sourceTaskId,
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
