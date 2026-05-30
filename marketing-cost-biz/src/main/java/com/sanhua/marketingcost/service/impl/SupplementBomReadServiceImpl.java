package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.dto.quotebom.SupplementBomReadResult;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.SupplementBomReadService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SupplementBomReadServiceImpl implements SupplementBomReadService {

  private static final String VERSION_STATUS_APPROVED = "APPROVED";
  private static final int ACTIVE = 1;

  private final QuoteBomSupplementVersionMapper versionMapper;
  private final QuoteBomSupplementDetailMapper detailMapper;

  public SupplementBomReadServiceImpl(
      QuoteBomSupplementVersionMapper versionMapper, QuoteBomSupplementDetailMapper detailMapper) {
    this.versionMapper = versionMapper;
    this.detailMapper = detailMapper;
  }

  @Override
  public SupplementBomReadResult readApproved(
      String quoteProductCode, String productType, String supplementScope, String periodMonth) {
    String normalizedCode = trimToNull(quoteProductCode);
    String normalizedType = trimToNull(productType);
    String normalizedScope = trimToNull(supplementScope);
    String normalizedPeriodMonth = normalizePeriodMonth(periodMonth);
    if (normalizedCode == null || normalizedScope == null) {
      return empty(
          normalizedCode,
          normalizedType,
          normalizedScope,
          normalizedPeriodMonth,
          "报价产品料号或补录范围为空");
    }

    YearMonth month = YearMonth.parse(normalizedPeriodMonth);
    LocalDate periodStart = month.atDay(1);
    LocalDate periodEnd = month.atEndOfMonth();
    List<QuoteBomSupplementVersion> candidates =
        versionMapper.selectList(
            Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
                .eq(QuoteBomSupplementVersion::getQuoteProductCode, normalizedCode)
                .eq(
                    normalizedType != null,
                    QuoteBomSupplementVersion::getProductType,
                    normalizedType)
                .eq(QuoteBomSupplementVersion::getSupplementScope, normalizedScope)
                .eq(QuoteBomSupplementVersion::getVersionStatus, VERSION_STATUS_APPROVED)
                .eq(QuoteBomSupplementVersion::getActiveFlag, ACTIVE));

    QuoteBomSupplementVersion selected =
        candidates == null
            ? null
            : candidates.stream()
                .filter(version -> validForPeriod(version, periodStart, periodEnd))
                .sorted(versionComparator(normalizedPeriodMonth))
                .findFirst()
                .orElse(null);
    if (selected == null) {
      return empty(
          normalizedCode,
          normalizedType,
          normalizedScope,
          normalizedPeriodMonth,
          "未找到 6 个月有效期内的已审核补录 BOM");
    }

    List<QuoteBomSourceLineDto> lines =
        detailMapper
            .selectList(
                Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
                    .eq(QuoteBomSupplementDetail::getSupplementVersionId, selected.getId())
                    .orderByAsc(QuoteBomSupplementDetail::getLineNo))
            .stream()
            .map(this::toLine)
            .toList();
    return new SupplementBomReadResult(
        normalizedCode,
        selected.getProductType(),
        normalizedScope,
        normalizedPeriodMonth,
        true,
        selected.getId(),
        selected.getTaskId(),
        selected.getTaskNo(),
        selected.getBomSource(),
        selected.getReuseValidUntil(),
        lines,
        null);
  }

  private boolean validForPeriod(
      QuoteBomSupplementVersion version, LocalDate periodStart, LocalDate periodEnd) {
    if (version.getReuseValidUntil() == null || version.getReuseValidUntil().isBefore(periodStart)) {
      return false;
    }
    if (version.getEffectiveFrom() != null && version.getEffectiveFrom().isAfter(periodEnd)) {
      return false;
    }
    return version.getEffectiveTo() == null || !version.getEffectiveTo().isBefore(periodStart);
  }

  private Comparator<QuoteBomSupplementVersion> versionComparator(String periodMonth) {
    return Comparator
        .comparing((QuoteBomSupplementVersion v) -> periodMonth.equals(trimToNull(v.getPeriodMonth())))
        .reversed()
        .thenComparing(
            v -> v.getReviewedAt() == null ? LocalDateTime.MIN : v.getReviewedAt(),
            Comparator.reverseOrder())
        .thenComparing(
            v -> v.getId() == null ? Long.MIN_VALUE : v.getId(),
            Comparator.reverseOrder());
  }

  private QuoteBomSourceLineDto toLine(QuoteBomSupplementDetail detail) {
    return new QuoteBomSourceLineDto(
        detail.getId(),
        detail.getLineNo(),
        detail.getLevel(),
        detail.getQuoteProductCode(),
        detail.getParentCode(),
        detail.getMaterialCode(),
        detail.getMaterialName(),
        detail.getMaterialSpec(),
        detail.getMaterialModel(),
        detail.getDrawingNo(),
        detail.getShapeAttr(),
        detail.getMainCategoryCode(),
        detail.getUnit(),
        detail.getSourceCategory(),
        detail.getCostElementCode(),
        detail.getBomPurpose(),
        detail.getBomVersion(),
        detail.getQtyPerParent(),
        detail.getQtyPerTop(),
        detail.getParentBaseQty(),
        detail.getPath(),
        detail.getSortSeq(),
        detail.getSourceRawHierarchyId(),
        detail.getSourceU9BomId(),
        detail.getManualFlag());
  }

  private SupplementBomReadResult empty(
      String quoteProductCode,
      String productType,
      String supplementScope,
      String periodMonth,
      String gapMessage) {
    return new SupplementBomReadResult(
        quoteProductCode,
        productType,
        supplementScope,
        periodMonth,
        false,
        null,
        null,
        null,
        null,
        null,
        List.of(),
        gapMessage);
  }

  private String normalizePeriodMonth(String periodMonth) {
    String value = trimToNull(periodMonth);
    if (value == null) {
      return YearMonth.now().toString();
    }
    return YearMonth.parse(value).toString();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
