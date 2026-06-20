package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FlattenRequest;
import com.sanhua.marketingcost.dto.FlattenResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BomByproductCostRuleQueryService;
import com.sanhua.marketingcost.service.BomFlattenService;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import com.sanhua.marketingcost.service.settlement.BomByproductSettlementAdapter;
import com.sanhua.marketingcost.service.settlement.BomByproductSettlementReadResult;
import com.sanhua.marketingcost.service.settlement.BomSettlementBuildRequest;
import com.sanhua.marketingcost.service.settlement.BomSettlementNode;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildEngine;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildResult;
import com.sanhua.marketingcost.service.settlement.BomSettlementSubRefCandidate;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * BOM 阶段 C 拍平实现。
 *
 * <p>BSR-07 后，本入口只负责读取 raw_hierarchy、补齐必要上下文并落库；
 * 停止下钻、上卷、辅料排除、委外加工费和副产品附加行全部交给
 * {@link BomSettlementRowBuildEngine}，避免正式 BOM 拍平和报价 BOM 生成两套规则分叉。
 */
@Service
public class BomFlattenServiceImpl implements BomFlattenService {

  private static final Logger log = LoggerFactory.getLogger(BomFlattenServiceImpl.class);
  private static final int INSERT_BATCH_SIZE = 500;
  private static final String SYNTHETIC_PACKAGE_CATEGORY_CODE = "15155__PACKAGE__";

  private final BomRawHierarchyMapper rawMapper;
  private final BomCostingRowMapper costingMapper;
  private final BomCostingRowSubRefMapper subRefMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final PackageComponentIdentifyService packageComponentIdentifyService;
  private final BomSettlementRuleQueryService settlementRuleQueryService;
  private final BomByproductCostRuleQueryService byproductRuleQueryService;
  private final BomByproductSettlementAdapter byproductSettlementAdapter;
  private final BomSettlementRowBuildEngine buildEngine;

  public BomFlattenServiceImpl(
      BomRawHierarchyMapper rawMapper,
      BomCostingRowMapper costingMapper,
      BomCostingRowSubRefMapper subRefMapper,
      OaFormItemMapper oaFormItemMapper,
      PackageComponentIdentifyService packageComponentIdentifyService,
      BomSettlementRuleQueryService settlementRuleQueryService,
      BomByproductCostRuleQueryService byproductRuleQueryService,
      BomByproductSettlementAdapter byproductSettlementAdapter,
      BomSettlementRowBuildEngine buildEngine) {
    this.rawMapper = rawMapper;
    this.costingMapper = costingMapper;
    this.subRefMapper = subRefMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.packageComponentIdentifyService = packageComponentIdentifyService;
    this.settlementRuleQueryService = settlementRuleQueryService;
    this.byproductRuleQueryService = byproductRuleQueryService;
    this.byproductSettlementAdapter = byproductSettlementAdapter;
    this.buildEngine = buildEngine;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FlattenResult flatten(FlattenRequest request) {
    validate(request);
    String mode = request.getMode() == null ? "BY_OA" : request.getMode().toUpperCase();
    if (!"BY_OA".equals(mode)) {
      throw new UnsupportedOperationException(
          "flatten 本期只支持 mode=BY_OA；ALL / BY_PRODUCT 预留给 T5.5/后续");
    }

    FlattenResult result = new FlattenResult();
    LocalDate asOf = request.getAsOfDate();
    String periodMonth = resolvePeriodMonth(request.getPeriodMonth(), asOf);
    String purpose = request.getBomPurpose();
    List<BomRawHierarchy> rawRows = loadRawRows(request, asOf, purpose);
    if (rawRows.isEmpty()) {
      result.getWarnings().add(
          "产品 " + request.getTopProductCode() + " 在 asOfDate=" + asOf + " 无 BOM 版本");
      return result;
    }

    rawRows.sort(Comparator.comparing(BomRawHierarchy::getPath));
    String buType = BusinessUnitContext.getCurrentBusinessUnitType();
    Map<String, Boolean> packageFlags =
        identifyPackageComponents(
            rawRows,
            MaterialOrganization.forQuoteProcess(
                null, request.getOaNo(), resolveProductName(request.getOaFormItemId())));
    List<BomSettlementNode> nodes = rawRows.stream()
        .map(row -> toSettlementNode(row, buType, packageFlags))
        .toList();
    BomByproductSettlementReadResult byproductRead =
        byproductSettlementAdapter.read(nodes, asOf, buType, purpose);

    String buildBatchId = generateFlattenBatchId();
    LocalDateTime builtAt = LocalDateTime.now();
    BomSettlementRowBuildResult built = buildEngine.build(
        new BomSettlementBuildRequest(
            request.getOaNo(),
            request.getTopProductCode(),
            asOf,
            periodMonth,
            buildBatchId,
            builtAt,
            buType,
            purpose,
            nodes,
            settlementRuleQueryService.listEnabledCandidates(),
            byproductRead.byproducts(),
            byproductRead.scrapRefs(),
            byproductRuleQueryService.listEnabledCandidates()));
    stampRowsForQuoteItem(request, built.costingRows());
    BomCostingRowAggregation.Result aggregatedRows =
        BomCostingRowAggregation.aggregate(built.costingRows());

    result.getWarnings().addAll(byproductRead.warnings());
    result.getWarnings().addAll(built.warnings());

    // 写库前删除本次 OA + 产品 + 月份旧快照，防止规则变更后不再生成的旧 path 残留为幽灵行。
    costingMapper.delete(
        Wrappers.<BomCostingRow>lambdaQuery()
            .eq(BomCostingRow::getOaNo, request.getOaNo())
            .eq(
                request.getOaFormItemId() != null,
                BomCostingRow::getOaFormItemId,
                request.getOaFormItemId())
            .eq(BomCostingRow::getTopProductCode, request.getTopProductCode())
            .eq(BomCostingRow::getPeriodMonth, periodMonth));
    int written = writeInBatches(aggregatedRows.rows());
    int subRefCount = writeSubRefs(request, built.subRefs(), aggregatedRows.pathAliases());

    result.setCostingRowsWritten(written);
    result.setSubtreeRequiredCount(countSubtreeRequired(aggregatedRows.rows()));
    log.info(
        "flatten 完成: oa={} top={} asOf={} written={} subRefs={} warnings={} stats={}",
        request.getOaNo(), request.getTopProductCode(), asOf,
        written, subRefCount, result.getWarnings().size(), built.stats());
    return result;
  }

  private List<BomRawHierarchy> loadRawRows(
      FlattenRequest request, LocalDate asOf, String purpose) {
    List<BomRawHierarchy> rows = rawMapper.selectList(
        Wrappers.<BomRawHierarchy>lambdaQuery()
            .eq(BomRawHierarchy::getTopProductCode, request.getTopProductCode())
            .eq(BomRawHierarchy::getSourceType, "U9")
            .eq(StringUtils.hasText(purpose), BomRawHierarchy::getBomPurpose, purpose)
            .le(BomRawHierarchy::getEffectiveFrom, asOf)
            .and(w -> w.ge(BomRawHierarchy::getEffectiveTo, asOf)
                .or()
                .isNull(BomRawHierarchy::getEffectiveTo)));
    return BomEffectiveTreePruner.prune(rows, request.getTopProductCode());
  }

  private Map<String, Boolean> identifyPackageComponents(
      List<BomRawHierarchy> rows, String organizationCode) {
    Set<String> materialCodes = new LinkedHashSet<>();
    for (BomRawHierarchy row : rows) {
      if (StringUtils.hasText(row.getMaterialCode())) {
        materialCodes.add(row.getMaterialCode().trim());
      }
    }
    return materialCodes.isEmpty()
        ? Map.of()
        : packageComponentIdentifyService.batchIdentify(materialCodes, organizationCode);
  }

  private static BomSettlementNode toSettlementNode(
      BomRawHierarchy row, String buType, Map<String, Boolean> packageFlags) {
    String materialCode = trimToNull(row.getMaterialCode());
    String materialCategoryCode = firstText(row.getMaterialCategory1(), row.getMaterialCategory2());
    if (Boolean.TRUE.equals(packageFlags.get(materialCode))
        && !startsWithPackagePrefix(materialCategoryCode)) {
      materialCategoryCode = SYNTHETIC_PACKAGE_CATEGORY_CODE;
    }
    return new BomSettlementNode(
        row.getId(),
        row.getTopProductCode(),
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
        materialCategoryCode,
        firstText(row.getMaterialCategory1(), row.getMaterialCategory2()),
        firstText(row.getMaterialCategory1(), row.getMaterialCategory2()),
        row.getBomPurpose(),
        row.getBomVersion(),
        row.getU9IsCostFlag(),
        row.getIsLeaf(),
        row.getEffectiveFrom(),
        row.getEffectiveTo(),
        row.getEffectiveFrom(),
        buType,
        null);
  }

  private int writeInBatches(List<BomCostingRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return 0;
    }
    int total = 0;
    for (int start = 0; start < rows.size(); start += INSERT_BATCH_SIZE) {
      int end = Math.min(start + INSERT_BATCH_SIZE, rows.size());
      total += costingMapper.batchUpsert(rows.subList(start, end));
    }
    return total;
  }

  private void stampRowsForQuoteItem(FlattenRequest request, List<BomCostingRow> rows) {
    if (request.getOaFormItemId() == null || rows == null || rows.isEmpty()) {
      return;
    }
    for (BomCostingRow row : rows) {
      row.setOaFormItemId(request.getOaFormItemId());
      if (row.getManualModified() == null) {
        row.setManualModified(0);
      }
    }
  }

  private int writeSubRefs(
      FlattenRequest request,
      List<BomSettlementSubRefCandidate> candidates,
      Map<String, String> pathAliases) {
    if (candidates == null || candidates.isEmpty()) {
      return 0;
    }
    Set<String> parentPaths = new LinkedHashSet<>();
    for (BomSettlementSubRefCandidate candidate : candidates) {
      String costingRowPath =
          BomCostingRowAggregation.resolvePath(pathAliases, candidate.costingRowPath());
      if (StringUtils.hasText(costingRowPath)) {
        parentPaths.add(costingRowPath);
      }
    }
    if (parentPaths.isEmpty()) {
      return 0;
    }

    List<BomCostingRow> parentRows = costingMapper.selectList(
        Wrappers.<BomCostingRow>lambdaQuery()
            .eq(BomCostingRow::getOaNo, request.getOaNo())
            .eq(
                request.getOaFormItemId() != null,
                BomCostingRow::getOaFormItemId,
                request.getOaFormItemId())
            .eq(BomCostingRow::getTopProductCode, request.getTopProductCode())
            .eq(BomCostingRow::getAsOfDate, request.getAsOfDate())
            .in(BomCostingRow::getPath, parentPaths));
    Map<String, Long> parentPathToId = new HashMap<>();
    for (BomCostingRow row : parentRows) {
      parentPathToId.put(row.getPath(), row.getId());
    }

    List<Long> parentIds = new ArrayList<>(parentPathToId.values());
    if (!parentIds.isEmpty()) {
      subRefMapper.delete(
          Wrappers.<BomCostingRowSubRef>lambdaQuery()
              .in(BomCostingRowSubRef::getCostingRowId, parentIds));
    }

    int inserted = 0;
    for (BomSettlementSubRefCandidate candidate : candidates) {
      String costingRowPath =
          BomCostingRowAggregation.resolvePath(pathAliases, candidate.costingRowPath());
      Long costingRowId = parentPathToId.get(costingRowPath);
      if (costingRowId == null || candidate.subRef() == null) {
        log.warn("sub_ref 写入跳过：父件 costing_row 未反查到 id，parentPath={}", costingRowPath);
        continue;
      }
      candidate.subRef().setCostingRowId(costingRowId);
      subRefMapper.insert(candidate.subRef());
      inserted++;
    }
    return inserted;
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

  private static void validate(FlattenRequest req) {
    if (req == null) {
      throw new IllegalArgumentException("request 必填");
    }
    if (req.getAsOfDate() == null) {
      throw new IllegalArgumentException("asOfDate 必填（锁月复现核心）");
    }
    if (!StringUtils.hasText(req.getOaNo())) {
      throw new IllegalArgumentException("oaNo 必填");
    }
    if (!StringUtils.hasText(req.getTopProductCode())) {
      throw new IllegalArgumentException("topProductCode 必填");
    }
  }

  private static String resolvePeriodMonth(String requestedPeriodMonth, LocalDate asOf) {
    String normalized = trimToNull(requestedPeriodMonth);
    return normalized == null
        ? YearMonth.from(asOf).toString()
        : YearMonth.parse(normalized).toString();
  }

  private String resolveProductName(Long oaFormItemId) {
    if (oaFormItemId == null) {
      return null;
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    return item == null ? null : item.getProductName();
  }

  private static boolean startsWithPackagePrefix(String value) {
    return StringUtils.hasText(value) && value.startsWith("15155");
  }

  private static String firstText(String first, String second) {
    return StringUtils.hasText(first) ? first : (StringUtils.hasText(second) ? second : null);
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String generateFlattenBatchId() {
    return "f_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
  }
}
