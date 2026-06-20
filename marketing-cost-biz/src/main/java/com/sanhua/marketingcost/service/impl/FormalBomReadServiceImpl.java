package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FormalBomReadServiceImpl implements FormalBomReadService {

  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  private final MaterialMasterRawMapper materialMasterRawMapper;

  public FormalBomReadServiceImpl(
      BomRawHierarchyMapper bomRawHierarchyMapper, MaterialMasterRawMapper materialMasterRawMapper) {
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
  }

  @Override
  public FormalBomReadResult read(
      String productCode, String periodMonth, String bomPurpose, LocalDate quoteDate) {
    return read(
        productCode,
        periodMonth,
        bomPurpose,
        quoteDate,
        MaterialOrganization.COMMERCIAL.getCode());
  }

  @Override
  public FormalBomReadResult read(
      String productCode,
      String periodMonth,
      String bomPurpose,
      LocalDate quoteDate,
      String organizationCode) {
    String normalizedProductCode = trimToNull(productCode);
    String normalizedPeriodMonth = normalizePeriodMonth(periodMonth);
    String normalizedBomPurpose = trimToNull(bomPurpose);
    if (normalizedProductCode == null) {
      return new FormalBomReadResult(
          null, normalizedPeriodMonth, normalizedBomPurpose, false, List.of(), "产品料号为空");
    }
    LocalDate effectiveDate = quoteDate == null ? LocalDate.now() : quoteDate;

    List<BomRawHierarchy> rows =
        bomRawHierarchyMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, normalizedProductCode)
                .eq(
                    normalizedBomPurpose != null,
                    BomRawHierarchy::getBomPurpose,
                    normalizedBomPurpose)
                .le(BomRawHierarchy::getEffectiveFrom, effectiveDate)
                .and(
                    w ->
                        w.isNull(BomRawHierarchy::getEffectiveTo)
                            .or()
                            .ge(BomRawHierarchy::getEffectiveTo, effectiveDate))
                .orderByAsc(BomRawHierarchy::getLevel)
                .orderByAsc(BomRawHierarchy::getPath)
                .orderByAsc(BomRawHierarchy::getSortSeq)
                .orderByAsc(BomRawHierarchy::getId));
    if (rows == null || rows.isEmpty()) {
      return new FormalBomReadResult(
          normalizedProductCode,
          normalizedPeriodMonth,
          normalizedBomPurpose,
          false,
          List.of(),
          "未在 lp_bom_raw_hierarchy 找到正式 BOM");
    }

    rows = BomEffectiveTreePruner.prune(rows, normalizedProductCode);
    if (rows.isEmpty()) {
      return new FormalBomReadResult(
          normalizedProductCode,
          normalizedPeriodMonth,
          normalizedBomPurpose,
          false,
          List.of(),
          "未在 lp_bom_raw_hierarchy 找到有效连通 BOM");
    }

    List<BomRawHierarchy> sorted = rows.stream().sorted(rowComparator()).toList();
    Map<String, MaterialMasterRaw> masterByCode = selectMasterByCode(
        sorted.stream().map(BomRawHierarchy::getMaterialCode).collect(Collectors.toCollection(LinkedHashSet::new)),
        organizationCode);
    List<QuoteBomSourceLineDto> lines = new java.util.ArrayList<>(sorted.size());
    int lineNo = 1;
    for (BomRawHierarchy row : sorted) {
      MaterialMasterRaw master = masterByCode.get(trimToNull(row.getMaterialCode()));
      lines.add(toLine(row, master, lineNo++));
    }
    return new FormalBomReadResult(
        normalizedProductCode, normalizedPeriodMonth, normalizedBomPurpose, true, lines, null);
  }

  private QuoteBomSourceLineDto toLine(BomRawHierarchy row, MaterialMasterRaw master, int lineNo) {
    return new QuoteBomSourceLineDto(
        row.getId(),
        lineNo,
        row.getLevel(),
        row.getTopProductCode(),
        row.getParentCode(),
        row.getMaterialCode(),
        firstText(row.getMaterialName(), master == null ? null : master.getMaterialName()),
        firstText(row.getMaterialSpec(), master == null ? null : master.getMaterialSpec()),
        master == null ? null : trimToNull(master.getMaterialModel()),
        master == null ? null : trimToNull(master.getDrawingNo()),
        firstText(row.getShapeAttr(), master == null ? null : master.getShapeAttr()),
        master == null ? null : trimToNull(master.getMainCategoryCode()),
        master == null ? null : trimToNull(master.getUnit()),
        row.getSourceCategory(),
        row.getCostElementCode(),
        row.getBomPurpose(),
        row.getBomVersion(),
        row.getQtyPerParent(),
        row.getQtyPerTop(),
        null,
        row.getPath(),
        row.getSortSeq(),
        row.getId(),
        null,
        0);
  }

  private Map<String, MaterialMasterRaw> selectMasterByCode(
      LinkedHashSet<String> codes, String organizationCode) {
    codes.removeIf(code -> trimToNull(code) == null);
    if (codes.isEmpty()) {
      return Map.of();
    }
    String organization = MaterialOrganization.normalize(organizationCode);
    List<MaterialMasterRaw> rows =
        MaterialOrganization.COMMERCIAL.getCode().equals(organization)
            ? materialMasterRawMapper.selectByLatestBatchAndCodes(codes, null)
            : materialMasterRawMapper.selectByLatestBatchAndCodes(codes, null, organization);
    return rows.stream()
        .filter(row -> trimToNull(row.getMaterialCode()) != null)
        .collect(
            Collectors.toMap(
                row -> trimToNull(row.getMaterialCode()),
                Function.identity(),
                (first, ignored) -> first));
  }

  private Comparator<BomRawHierarchy> rowComparator() {
    return Comparator
        .comparing((BomRawHierarchy row) -> row.getLevel() == null ? Integer.MAX_VALUE : row.getLevel())
        .thenComparing(row -> row.getPath() == null ? "" : row.getPath())
        .thenComparing(row -> row.getSortSeq() == null ? Integer.MAX_VALUE : row.getSortSeq())
        .thenComparing(row -> row.getId() == null ? Long.MAX_VALUE : row.getId());
  }

  private String normalizePeriodMonth(String periodMonth) {
    String value = trimToNull(periodMonth);
    if (value == null) {
      return YearMonth.now().toString();
    }
    return YearMonth.parse(value).toString();
  }

  private String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
