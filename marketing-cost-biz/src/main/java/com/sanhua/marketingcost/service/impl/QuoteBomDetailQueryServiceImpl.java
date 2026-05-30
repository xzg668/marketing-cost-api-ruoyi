package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingProductDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingProductPageResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingRowDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingRowPageResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingRowSourceRefDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomPackageStructurePageResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSourceRef;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSourceRefMapper;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import com.sanhua.marketingcost.service.PackageComponentStructureReadService;
import com.sanhua.marketingcost.service.QuoteBomDetailQueryService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuoteBomDetailQueryServiceImpl implements QuoteBomDetailQueryService {

  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 200;

  private final PackageComponentStructureReadService packageStructureReadService;
  private final BomCostingRowMapper costingRowMapper;
  private final BomCostingRowSourceRefMapper sourceRefMapper;
  private final BomSettlementRuleMapper settlementRuleMapper;
  private final JdbcTemplate jdbcTemplate;

  public QuoteBomDetailQueryServiceImpl(
      PackageComponentStructureReadService packageStructureReadService,
      BomCostingRowMapper costingRowMapper,
      BomCostingRowSourceRefMapper sourceRefMapper,
      BomSettlementRuleMapper settlementRuleMapper,
      JdbcTemplate jdbcTemplate) {
    this.packageStructureReadService = packageStructureReadService;
    this.costingRowMapper = costingRowMapper;
    this.sourceRefMapper = sourceRefMapper;
    this.settlementRuleMapper = settlementRuleMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public QuoteBomPackageStructurePageResponse pagePackageStructures(
      String referenceFinishedCode,
      String sourceTopProductCode,
      String packageParentCode,
      String periodMonth,
      Integer page,
      Integer pageSize) {
    PackageComponentStructureReadResult result =
        packageStructureReadService.readByReference(
            trimToNull(referenceFinishedCode), trimToNull(sourceTopProductCode), periodMonth);
    String parentCode = trimToNull(packageParentCode);
    List<PackageComponentStructureLineDto> filtered =
        result.lines() == null
            ? List.of()
            : result.lines().stream()
                .filter(line -> parentCode == null || parentCode.equals(line.packageParentCode()))
                .toList();
    int current = normalizePage(page);
    int size = normalizePageSize(pageSize);
    List<PackageComponentStructureLineDto> slice = slice(filtered, current, size);
    return new QuoteBomPackageStructurePageResponse(
        result.referenceFinishedCode(),
        result.sourceTopProductCode(),
        result.periodMonth(),
        result.packageReferenceId(),
        result.found(),
        filtered.size(),
        slice,
        result.gaps() == null ? List.of() : result.gaps());
  }

  @Override
  public QuoteBomCostingRowPageResponse pageCostingRows(
      String oaNo,
      String topProductCode,
      String materialCode,
      String periodMonth,
      Integer page,
      Integer pageSize) {
    QueryWrapper<BomCostingRow> query =
        new QueryWrapper<BomCostingRow>()
            .select(costingRowSelectColumns().toArray(String[]::new))
            .eq(StringUtils.hasText(oaNo), "oa_no", trimToNull(oaNo))
            .eq(StringUtils.hasText(topProductCode), "top_product_code", trimToNull(topProductCode))
            .eq(StringUtils.hasText(materialCode), "material_code", trimToNull(materialCode))
            .eq(StringUtils.hasText(periodMonth), "period_month", trimToNull(periodMonth))
            .orderByDesc("built_at")
            .orderByAsc("top_product_code")
            .orderByAsc("path")
            .orderByAsc("id");
    Page<BomCostingRow> pager =
        costingRowMapper.selectPage(
            new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
    List<BomCostingRow> records = pager.getRecords() == null ? List.of() : pager.getRecords();
    if (records.isEmpty()) {
      return new QuoteBomCostingRowPageResponse(pager.getTotal(), List.of());
    }
    Map<Long, List<BomCostingRowSourceRef>> refsByRowId = selectSourceRefs(records);
    Map<Long, BomSettlementRule> rulesById = selectRules(records);
    List<QuoteBomCostingRowDto> list =
        records.stream()
            .map(row -> toCostingRowDto(row, sourceRefsFor(row, refsByRowId), ruleFor(row, rulesById)))
            .toList();
    return new QuoteBomCostingRowPageResponse(pager.getTotal(), list);
  }

  @Override
  public QuoteBomCostingProductPageResponse pageCostingProducts(
      String oaNo,
      String topProductCode,
      String materialCode,
      String periodMonth,
      Integer page,
      Integer pageSize) {
    String ruleHitExpression =
        costingRowColumnExists("matched_settlement_rule_id")
            ? "SUM(CASE WHEN matched_settlement_rule_id IS NULL THEN 0 ELSE 1 END) AS ruleHitCount"
            : "0 AS ruleHitCount";
    QueryWrapper<BomCostingRow> query =
        new QueryWrapper<BomCostingRow>()
            .select(
                "oa_no AS oaNo",
                "top_product_code AS topProductCode",
                "period_month AS periodMonth",
                "COUNT(*) AS rowCount",
                ruleHitExpression,
                "SUM(CASE WHEN subtree_cost_required = 1 THEN 1 ELSE 0 END) AS subtreeCostRequiredCount",
                "MAX(build_batch_id) AS buildBatchId",
                "MAX(built_at) AS latestBuiltAt")
            .eq(StringUtils.hasText(oaNo), "oa_no", trimToNull(oaNo))
            .eq(StringUtils.hasText(topProductCode), "top_product_code", trimToNull(topProductCode))
            .eq(StringUtils.hasText(materialCode), "material_code", trimToNull(materialCode))
            .eq(StringUtils.hasText(periodMonth), "period_month", trimToNull(periodMonth))
            .groupBy("oa_no", "top_product_code", "period_month")
            .orderByDesc("MAX(built_at)")
            .orderByAsc("oa_no")
            .orderByAsc("top_product_code");
    Page<Map<String, Object>> pager =
        costingRowMapper.selectMapsPage(
            new Page<>(normalizePage(page), normalizePageSize(pageSize)), query);
    List<Map<String, Object>> records = pager.getRecords() == null ? List.of() : pager.getRecords();
    List<QuoteBomCostingProductDto> list = records.stream().map(this::toCostingProductDto).toList();
    return new QuoteBomCostingProductPageResponse(pager.getTotal(), list);
  }

  private QuoteBomCostingProductDto toCostingProductDto(Map<String, Object> row) {
    return new QuoteBomCostingProductDto(
        stringValue(row, "oaNo", "oa_no"),
        stringValue(row, "topProductCode", "top_product_code"),
        stringValue(row, "periodMonth", "period_month"),
        longValue(row, "rowCount", "row_count"),
        longValue(row, "ruleHitCount", "rule_hit_count"),
        longValue(row, "subtreeCostRequiredCount", "subtree_cost_required_count"),
        stringValue(row, "buildBatchId", "build_batch_id"),
        localDateTimeValue(row, "latestBuiltAt", "latest_built_at"));
  }

  private List<BomCostingRowSourceRef> sourceRefsFor(
      BomCostingRow row, Map<Long, List<BomCostingRowSourceRef>> refsByRowId) {
    Long rowId = row.getId();
    return rowId == null ? List.of() : refsByRowId.get(rowId);
  }

  private BomSettlementRule ruleFor(BomCostingRow row, Map<Long, BomSettlementRule> rulesById) {
    Long ruleId = row.getMatchedSettlementRuleId();
    return ruleId == null ? null : rulesById.get(ruleId);
  }

  private Map<Long, List<BomCostingRowSourceRef>> selectSourceRefs(List<BomCostingRow> records) {
    List<Long> rowIds =
        records.stream().map(BomCostingRow::getId).filter(Objects::nonNull).distinct().toList();
    if (rowIds.isEmpty()) {
      return Map.of();
    }
    List<BomCostingRowSourceRef> refs =
        sourceRefMapper.selectList(
            Wrappers.<BomCostingRowSourceRef>lambdaQuery()
                .in(BomCostingRowSourceRef::getCostingRowId, rowIds)
                .orderByAsc(BomCostingRowSourceRef::getCostingRowId)
                .orderByAsc(BomCostingRowSourceRef::getId));
    if (refs == null || refs.isEmpty()) {
      return Map.of();
    }
    return refs.stream().collect(Collectors.groupingBy(BomCostingRowSourceRef::getCostingRowId));
  }

  private Map<Long, BomSettlementRule> selectRules(List<BomCostingRow> records) {
    List<Long> ruleIds =
        records.stream()
            .map(BomCostingRow::getMatchedSettlementRuleId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (ruleIds.isEmpty()) {
      return Map.of();
    }
    List<BomSettlementRule> rules =
        settlementRuleMapper.selectList(
            Wrappers.<BomSettlementRule>lambdaQuery().in(BomSettlementRule::getId, ruleIds));
    if (rules == null || rules.isEmpty()) {
      return Map.of();
    }
    return rules.stream()
        .filter(rule -> rule.getId() != null)
        .collect(
            Collectors.toMap(BomSettlementRule::getId, rule -> rule, (first, ignored) -> first));
  }

  private QuoteBomCostingRowDto toCostingRowDto(
      BomCostingRow row, List<BomCostingRowSourceRef> refs, BomSettlementRule rule) {
    List<QuoteBomCostingRowSourceRefDto> refDtos =
        refs == null ? List.of() : refs.stream().map(this::toSourceRefDto).toList();
    return new QuoteBomCostingRowDto(
        row.getId(),
        row.getOaNo(),
        row.getTopProductCode(),
        row.getParentCode(),
        row.getMaterialCode(),
        row.getMaterialName(),
        row.getMaterialSpec(),
        row.getShapeAttr(),
        row.getSourceCategory(),
        row.getCostElementCode(),
        row.getBomPurpose(),
        row.getBomVersion(),
        row.getLevel(),
        row.getPath(),
        row.getQtyPerParent(),
        row.getQtyPerTop(),
        row.getIsCostingRow(),
        row.getSubtreeCostRequired(),
        row.getRawHierarchyNodeId(),
        row.getMatchedSettlementRuleId(),
        row.getSettlementRowType(),
        rule == null ? null : rule.getRuleName(),
        rule == null ? null : rule.getSettlementAction(),
        rule == null ? null : rule.getRemark(),
        rule == null ? null : rule.getRuleCategory(),
        rule == null ? null : rule.getRuleCode(),
        summarizeSourceTypes(refDtos),
        refDtos,
        row.getBuildBatchId(),
        row.getBuiltAt(),
        row.getPeriodMonth(),
        row.getAsOfDate(),
        row.getRawVersionEffectiveFrom(),
        row.getBusinessUnitType(),
        row.getUpdatedAt());
  }

  private QuoteBomCostingRowSourceRefDto toSourceRefDto(BomCostingRowSourceRef ref) {
    return new QuoteBomCostingRowSourceRefDto(
        ref.getId(),
        ref.getCostingRowId(),
        ref.getSourcePartType(),
        ref.getSourceRawHierarchyId(),
        ref.getSourceTaskId(),
        ref.getPreparationId(),
        ref.getSupplementVersionId(),
        ref.getSupplementDetailId(),
        ref.getPackageReferenceId(),
        ref.getPackageReferenceDetailId(),
        ref.getReferenceFinishedCode(),
        ref.getSourceTopProductCode(),
        ref.getSourceSnapshotId(),
        ref.getSourceSnapshotDetailId(),
        ref.getSourceU9BomId(),
        ref.getSourcePath(),
        ref.getCreatedAt());
  }

  private String summarizeSourceTypes(List<QuoteBomCostingRowSourceRefDto> refs) {
    if (refs == null || refs.isEmpty()) {
      return "未写入来源追溯";
    }
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (QuoteBomCostingRowSourceRefDto ref : refs) {
      String type = trimToNull(ref.sourcePartType());
      counts.merge(type == null ? "UNKNOWN" : type, 1, Integer::sum);
    }
    return counts.entrySet().stream()
        .map(entry -> entry.getKey() + " x" + entry.getValue())
        .collect(Collectors.joining(", "));
  }

  private String stringValue(Map<String, Object> row, String camelKey, String snakeKey) {
    Object value = mapValue(row, camelKey, snakeKey);
    return value == null ? null : String.valueOf(value);
  }

  private Long longValue(Map<String, Object> row, String camelKey, String snakeKey) {
    Object value = mapValue(row, camelKey, snakeKey);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    return Long.parseLong(String.valueOf(value));
  }

  private LocalDateTime localDateTimeValue(Map<String, Object> row, String camelKey, String snakeKey) {
    Object value = mapValue(row, camelKey, snakeKey);
    if (value instanceof LocalDateTime dateTime) {
      return dateTime;
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    return null;
  }

  private Object mapValue(Map<String, Object> row, String camelKey, String snakeKey) {
    if (row == null || row.isEmpty()) {
      return null;
    }
    if (row.containsKey(camelKey)) {
      return row.get(camelKey);
    }
    if (row.containsKey(snakeKey)) {
      return row.get(snakeKey);
    }
    String upperCamelKey = camelKey.toUpperCase();
    if (row.containsKey(upperCamelKey)) {
      return row.get(upperCamelKey);
    }
    String upperSnakeKey = snakeKey.toUpperCase();
    if (row.containsKey(upperSnakeKey)) {
      return row.get(upperSnakeKey);
    }
    return null;
  }

  private List<String> costingRowSelectColumns() {
    List<String> columns =
        new java.util.ArrayList<>(
            List.of(
                "id",
                "oa_no",
                "top_product_code",
                "parent_code",
                "material_code",
                "material_name",
                "material_spec",
                "shape_attr",
                "source_category",
                "cost_element_code",
                "bom_purpose",
                "bom_version",
                "level",
                "path",
                "qty_per_parent",
                "qty_per_top",
                "is_costing_row",
                "subtree_cost_required",
                "raw_hierarchy_node_id",
                "build_batch_id",
                "built_at",
                "period_month",
                "as_of_date",
                "raw_version_effective_from",
                "business_unit_type",
                "updated_at"));
    if (costingRowColumnExists("matched_settlement_rule_id")) {
      columns.add("matched_settlement_rule_id");
    }
    if (costingRowColumnExists("settlement_row_type")) {
      columns.add("settlement_row_type");
    }
    return columns;
  }

  private boolean costingRowColumnExists(String columnName) {
    try {
      Integer count =
          jdbcTemplate.queryForObject(
              """
              SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = ?
                 AND COLUMN_NAME = ?
              """,
              Integer.class,
              "lp_bom_costing_row",
              columnName);
      return count != null && count > 0;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private <T> List<T> slice(List<T> list, int page, int pageSize) {
    if (list == null || list.isEmpty()) {
      return List.of();
    }
    int from = Math.min((page - 1) * pageSize, list.size());
    int to = Math.min(from + pageSize, list.size());
    return Collections.unmodifiableList(list.subList(from, to));
  }

  private int normalizePage(Integer page) {
    return page == null || page < 1 ? DEFAULT_PAGE : page;
  }

  private int normalizePageSize(Integer pageSize) {
    if (pageSize == null || pageSize < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(pageSize, MAX_PAGE_SIZE);
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
