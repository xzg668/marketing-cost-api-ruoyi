package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FlattenRequest;
import com.sanhua.marketingcost.dto.FlattenResult;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
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
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.MaterialOrganization;
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
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BomFlattenService;
import com.sanhua.marketingcost.service.BomByproductCostRuleQueryService;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
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
  private static final int ACTIVE = 1;

  private final QuoteProductBomPreparationService preparationService;
  private final BomFlattenService flattenService;
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

  public QuoteProductBomCostingBuildServiceImpl(
      QuoteProductBomPreparationService preparationService,
      BomFlattenService flattenService,
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
    this.preparationService = preparationService;
    this.flattenService = flattenService;
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
    if (record == null || !PREPARATION_READY.equals(record.getPreparationStatus())) {
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
    cleanupExisting(record, periodMonth);
    FlattenRequest request = new FlattenRequest();
    request.setMode("BY_OA");
    request.setOaNo(record.getOaNo());
    request.setOaFormItemId(record.getOaFormItemId());
    request.setTopProductCode(record.getQuoteProductCode());
    request.setPeriodMonth(periodMonth);
    request.setAsOfDate(quoteDate);
    FlattenResult flattened = flattenService.flatten(request);
    List<BomCostingRow> rows = loadCurrentCostingRows(record, periodMonth);
    attachRowsToQuoteItem(record, rows);
    int refs = writeFormalSourceRefs(record, rows, sourceType);
    String batchId = rows.isEmpty() ? null : rows.get(0).getBuildBatchId();
    updateBuildBatch(record, batchId, periodMonth);
    return response(
        record,
        batchId,
        rows.size(),
        refs,
        flattened.getSubtreeRequiredCount(),
        countSourceTypes(sourceType, refs),
        flattened.getWarnings(),
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
    String organizationCode =
        MaterialOrganization.forQuoteProcess(
            null, record.getOaNo(), resolveProductName(record.getOaFormItemId()));
    FormalBomReadResult formal =
        MaterialOrganization.COMMERCIAL.getCode().equals(organizationCode)
            ? formalBomReadService.read(record.getQuoteProductCode(), periodMonth, null, quoteDate)
            : formalBomReadService.read(
                record.getQuoteProductCode(), periodMonth, null, quoteDate, organizationCode);
    if (!formal.found()) {
      throw new QuoteIngestException("正式 BOM 不可用: " + formal.gapMessage());
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
              line.path()));
    }
    return lines;
  }

  private String resolveProductName(Long oaFormItemId) {
    if (oaFormItemId == null) {
      return null;
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    return item == null ? null : item.getProductName();
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
              detail.getPath()));
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
              detail.getSourcePath()));
      fallback++;
    }
    return lines;
  }

  private DirectBuildResult applyRulesAndWrite(
      QuoteBomPreparationRecord record,
      LocalDate quoteDate,
      String periodMonth,
      List<PreparedLine> inputLines) {
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

    String buildBatchId = generateBuildBatchId();
    LocalDateTime builtAt = LocalDateTime.now();
    String buType = BusinessUnitContext.getCurrentBusinessUnitType();

    // 报价 BOM 入口只负责把正式 BOM / 补录 BOM / 包装参考归一成标准节点；
    // 结算行取舍、上卷、附加行等业务规则统一交给 BomSettlementRowBuildEngine。
    List<BomSettlementNode> nodes = new ArrayList<>();
    for (PreparedLine line : lines) {
      nodes.add(toSettlementNode(record, line, buType,
          childrenByParentPath.getOrDefault(line.path(), List.of()).isEmpty()));
    }
    BomByproductSettlementReadResult byproductRead =
        byproductSettlementAdapter.read(nodes, quoteDate, buType, "主制造");
    BomSettlementRowBuildResult built = buildEngine.build(
        new BomSettlementBuildRequest(
            record.getOaNo(),
            record.getQuoteProductCode(),
            quoteDate,
            periodMonth,
            buildBatchId,
            builtAt,
            buType,
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
    for (BomCostingRow row : rows) {
      row.setOaFormItemId(record.getOaFormItemId());
      if (row.getManualModified() == null) {
        row.setManualModified(0);
      }
    }
    return rows;
  }

  private void attachRowsToQuoteItem(QuoteBomPreparationRecord record, List<BomCostingRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return;
    }
    for (BomCostingRow row : rows) {
      row.setOaFormItemId(record.getOaFormItemId());
      if (row.getManualModified() == null) {
        row.setManualModified(0);
      }
      BomCostingRow patch = new BomCostingRow();
      patch.setId(row.getId());
      patch.setOaFormItemId(record.getOaFormItemId());
      patch.setManualModified(row.getManualModified());
      costingRowMapper.updateById(patch);
    }
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
        line.mainCategoryCode(),
        line.mainCategoryCode(),
        firstText(line.bomPurpose(), "主制造"),
        line.bomVersion(),
        null,
        leaf ? 1 : 0,
        null,
        null,
        null,
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

  private int writeFormalSourceRefs(
      QuoteBomPreparationRecord record, List<BomCostingRow> rows, String sourceType) {
    int count = 0;
    for (BomCostingRow row : rows) {
      BomCostingRowSourceRef ref = new BomCostingRowSourceRef();
      ref.setCostingRowId(row.getId());
      ref.setOaNo(record.getOaNo());
      ref.setOaFormItemId(record.getOaFormItemId());
      ref.setQuoteProductCode(record.getQuoteProductCode());
      ref.setSourcePartType(sourceType);
      ref.setSourceRawHierarchyId(row.getRawHierarchyNodeId());
      ref.setPreparationId(record.getId());
      ref.setSourceTaskId(record.getTaskId());
      ref.setSourcePath(row.getPath());
      sourceRefMapper.insert(ref);
      count++;
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
    sourceRefMapper.delete(
        Wrappers.<BomCostingRowSourceRef>lambdaQuery()
            .eq(BomCostingRowSourceRef::getOaNo, record.getOaNo())
            .eq(BomCostingRowSourceRef::getOaFormItemId, record.getOaFormItemId())
            .eq(BomCostingRowSourceRef::getQuoteProductCode, record.getQuoteProductCode()));
    costingRowMapper.delete(
        Wrappers.<BomCostingRow>lambdaQuery()
            .eq(BomCostingRow::getOaNo, record.getOaNo())
            .eq(BomCostingRow::getOaFormItemId, record.getOaFormItemId())
            .eq(BomCostingRow::getTopProductCode, record.getQuoteProductCode())
            .eq(BomCostingRow::getPeriodMonth, periodMonth));
  }

  private List<BomCostingRow> loadCurrentCostingRows(
      QuoteBomPreparationRecord record, String periodMonth) {
    return costingRowMapper.selectList(
        Wrappers.<BomCostingRow>lambdaQuery()
            .eq(BomCostingRow::getOaNo, record.getOaNo())
            .eq(BomCostingRow::getOaFormItemId, record.getOaFormItemId())
            .eq(BomCostingRow::getTopProductCode, record.getQuoteProductCode())
            .eq(BomCostingRow::getPeriodMonth, periodMonth)
            .orderByAsc(BomCostingRow::getPath)
            .orderByAsc(BomCostingRow::getId));
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
      String sourcePath) {}

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
