package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPrunerImpl;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativePruneResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteAwareBomAlternativeResolver;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FormalBomReadServiceImpl implements FormalBomReadService {

  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  private final MaterialMasterRawMapper materialMasterRawMapper;
  private final PlateCommercialMakeBomExpansionService crossOrganizationExpansionService;
  private final QuoteAwareBomAlternativeResolver quoteAlternativeResolver;

  public FormalBomReadServiceImpl(
      BomRawHierarchyMapper bomRawHierarchyMapper, MaterialMasterRawMapper materialMasterRawMapper) {
    this(
        bomRawHierarchyMapper,
        materialMasterRawMapper,
        new PlateCommercialMakeBomExpansionService(
            bomRawHierarchyMapper, materialMasterRawMapper),
        null);
  }

  public FormalBomReadServiceImpl(
      BomRawHierarchyMapper bomRawHierarchyMapper,
      MaterialMasterRawMapper materialMasterRawMapper,
      PlateCommercialMakeBomExpansionService crossOrganizationExpansionService) {
    this(
        bomRawHierarchyMapper,
        materialMasterRawMapper,
        crossOrganizationExpansionService,
        null);
  }

  @Autowired
  public FormalBomReadServiceImpl(
      BomRawHierarchyMapper bomRawHierarchyMapper,
      MaterialMasterRawMapper materialMasterRawMapper,
      PlateCommercialMakeBomExpansionService crossOrganizationExpansionService,
      QuoteAwareBomAlternativeResolver quoteAlternativeResolver) {
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
    this.crossOrganizationExpansionService = crossOrganizationExpansionService;
    this.quoteAlternativeResolver = quoteAlternativeResolver;
  }

  @Override
  public FormalBomReadResult read(QuoteBomReadContext context) {
    if (context == null) {
      throw new IllegalArgumentException("报价BOM读取上下文不能为空");
    }
    if (quoteAlternativeResolver == null) {
      throw new IllegalStateException("报价BOM替代选择解析器未配置");
    }
    QuoteDataOrganization organization =
        MaterialOrganization.normalizeQuoteDataOrganization(
            new QuoteDataOrganization(
                context.priceOrgCode(),
                context.materialOrganizationCode()));
    String normalizedProductCode = trimToNull(context.topProductCode());
    String normalizedPeriodMonth = normalizePeriodMonth(context.periodMonth());
    String normalizedBomPurpose = trimToNull(context.bomPurpose());
    QuoteBomReadContext normalizedContext =
        new QuoteBomReadContext(
            trimToNull(context.oaNo()),
            context.oaFormItemId(),
            normalizedProductCode,
            normalizedPeriodMonth,
            organization.priceOrgCode(),
            organization.materialOrganizationCode(),
            trimToNull(context.businessUnitType()),
            normalizedBomPurpose,
            context.quoteDate() == null ? LocalDate.now() : context.quoteDate());
    return readInternal(
        normalizedProductCode,
        normalizedPeriodMonth,
        normalizedBomPurpose,
        normalizedContext.quoteDate(),
        organization,
        normalizedContext);
  }

  @Override
  public FormalBomReadResult read(
      String productCode, String periodMonth, String bomPurpose, LocalDate quoteDate) {
    throw new IllegalArgumentException("读取正式 BOM 必须显式传入报价组织和料品组织");
  }

  @Override
  public FormalBomReadResult read(
      String productCode,
      String periodMonth,
      String bomPurpose,
      LocalDate quoteDate,
      QuoteDataOrganization quoteDataOrganization) {
    return readInternal(
        trimToNull(productCode),
        normalizePeriodMonth(periodMonth),
        trimToNull(bomPurpose),
        quoteDate == null ? LocalDate.now() : quoteDate,
        MaterialOrganization.normalizeQuoteDataOrganization(
            quoteDataOrganization),
        null);
  }

  private FormalBomReadResult readInternal(
      String normalizedProductCode,
      String normalizedPeriodMonth,
      String normalizedBomPurpose,
      LocalDate effectiveDate,
      QuoteDataOrganization organization,
      QuoteBomReadContext quoteContext) {
    if (normalizedProductCode == null) {
      return new FormalBomReadResult(
          null, normalizedPeriodMonth, normalizedBomPurpose, false, List.of(), "产品料号为空");
    }

    List<BomRawHierarchy> rows =
        bomRawHierarchyMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getPriceOrgCode, organization.priceOrgCode())
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

    if (quoteContext != null) {
      BomAlternativePruneResult pruned =
          quoteAlternativeResolver.resolve(quoteContext, rows);
      rows = pruned.nodes();
    }

    PlateCommercialMakeBomExpansionService.ExpansionResult expansion =
        crossOrganizationExpansionService.expand(
            rows,
            normalizedProductCode,
            effectiveDate,
            normalizedBomPurpose,
            "U9",
            organization);
    if (expansion.hasGaps()) {
      String prefix =
          quoteContext == null
              ? ""
              : BomAlternativeBranchPrunerImpl
                      .ALT_BRANCH_STRUCTURE_MISSING
                  + ": ";
      return new FormalBomReadResult(
          normalizedProductCode,
          normalizedPeriodMonth,
          normalizedBomPurpose,
          false,
          List.of(),
          prefix
              + "跨组织制造 BOM 展开失败："
              + String.join("；", expansion.gaps()));
    }
    rows = expansion.rows();

    List<BomRawHierarchy> sorted = rows.stream().sorted(rowComparator()).toList();
    Map<String, MaterialMasterRaw> masterByCode =
        MaterialOrganization.PLATE.getCode().equals(organization.materialOrganizationCode())
            ? expansion.plateMasters()
            : selectMasterByCode(
                sorted.stream()
                    .map(BomRawHierarchy::getMaterialCode)
                    .collect(Collectors.toCollection(LinkedHashSet::new)),
                organization.materialOrganizationCode());
    Map<String, MaterialMasterRaw> commercialMasterByCode =
        MaterialOrganization.COMMERCIAL.getCode().equals(
                organization.materialOrganizationCode())
            ? masterByCode
            : expansion.commercialMasters();
    List<QuoteBomSourceLineDto> lines = new java.util.ArrayList<>(sorted.size());
    int lineNo = 1;
    for (BomRawHierarchy row : sorted) {
      QuoteDataOrganization lineOrganization = lineOrganization(row, organization);
      MaterialMasterRaw master =
          MaterialOrganization.COMMERCIAL.getCode().equals(
                  lineOrganization.materialOrganizationCode())
              ? commercialMasterByCode.get(trimToNull(row.getMaterialCode()))
              : masterByCode.get(trimToNull(row.getMaterialCode()));
      lines.add(toLine(row, master, lineNo++, lineOrganization));
    }
    return new FormalBomReadResult(
        normalizedProductCode, normalizedPeriodMonth, normalizedBomPurpose, true, lines, null);
  }

  private QuoteBomSourceLineDto toLine(
      BomRawHierarchy row,
      MaterialMasterRaw master,
      int lineNo,
      QuoteDataOrganization organization) {
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
        firstText(master == null ? null : master.getMainCategoryCode(), row.getMaterialCategory1()),
        firstText(master == null ? null : master.getMainCategoryName(), row.getMaterialCategory2()),
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
        row.getSourceU9RowId(),
        0,
        firstText(row.getPriceOrgCode(), organization.priceOrgCode()),
        organization.materialOrganizationCode(),
        row.getChildType(),
        row.getAlternativeGroupKey());
  }

  private QuoteDataOrganization lineOrganization(
      BomRawHierarchy row, QuoteDataOrganization fallback) {
    String priceOrgCode = trimToNull(row == null ? null : row.getPriceOrgCode());
    if (priceOrgCode == null) {
      return fallback;
    }
    return MaterialOrganization.fromPriceOrgCode(priceOrgCode).toQuoteDataOrganization();
  }

  private Map<String, MaterialMasterRaw> selectMasterByCode(
      LinkedHashSet<String> codes, String organizationCode) {
    codes.removeIf(code -> trimToNull(code) == null);
    if (codes.isEmpty()) {
      return Map.of();
    }
    String organization = MaterialOrganization.normalize(organizationCode);
    List<MaterialMasterRaw> rows =
        materialMasterRawMapper.selectByLatestBatchAndCodes(codes, null, organization);
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
