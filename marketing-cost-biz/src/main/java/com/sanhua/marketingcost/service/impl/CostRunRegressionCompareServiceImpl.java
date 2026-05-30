package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunRegressionCompareReport;
import com.sanhua.marketingcost.dto.CostRunRegressionDifference;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.service.CostRunRegressionCompareService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CostRunRegressionCompareServiceImpl implements CostRunRegressionCompareService {

  private static final BigDecimal DECIMAL_TOLERANCE = new BigDecimal("0.000001");
  private static final String SECTION_RESULT = "RESULT";
  private static final String SECTION_CONTEXT = "CONTEXT";
  private static final String SECTION_PART_ITEM = "PART_ITEM";
  private static final String SECTION_COST_ITEM = "COST_ITEM";
  private static final String DEFAULT_COST_CATEGORY = "EXPENSE";

  private final CostRunResultMapper costRunResultMapper;
  private final CostRunPartItemMapper costRunPartItemMapper;
  private final CostRunCostItemMapper costRunCostItemMapper;

  public CostRunRegressionCompareServiceImpl(
      CostRunResultMapper costRunResultMapper,
      CostRunPartItemMapper costRunPartItemMapper,
      CostRunCostItemMapper costRunCostItemMapper) {
    this.costRunResultMapper = costRunResultMapper;
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.costRunCostItemMapper = costRunCostItemMapper;
  }

  @Override
  public List<CostRunObjectResult> loadStoredSnapshots(String oaNo) {
    String oaNoValue = requireText(oaNo, "oaNo");
    List<CostRunResult> results =
        costRunResultMapper.selectList(
            Wrappers.lambdaQuery(CostRunResult.class)
                .eq(CostRunResult::getOaNo, oaNoValue)
                .orderByAsc(CostRunResult::getProductCode, CostRunResult::getId));
    if (results == null || results.isEmpty()) {
      return List.of();
    }
    List<CostRunObjectResult> snapshots = new ArrayList<>();
    for (CostRunResult result : results) {
      if (result == null || !StringUtils.hasText(result.getProductCode())) {
        continue;
      }
      snapshots.add(loadStoredSnapshot(oaNoValue, result.getProductCode()));
    }
    return List.copyOf(snapshots);
  }

  @Override
  public CostRunObjectResult loadStoredSnapshot(String oaNo, String productCode) {
    String oaNoValue = requireText(oaNo, "oaNo");
    String productCodeValue = requireText(productCode, "productCode");
    CostRunResult result =
        costRunResultMapper.selectOne(
            Wrappers.lambdaQuery(CostRunResult.class)
                .eq(CostRunResult::getOaNo, oaNoValue)
                .eq(CostRunResult::getProductCode, productCodeValue)
                .last("LIMIT 1"));
    List<CostRunPartItem> partItems =
        costRunPartItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunPartItem.class)
                .eq(CostRunPartItem::getOaNo, oaNoValue)
                .eq(CostRunPartItem::getProductCode, productCodeValue)
                .orderByAsc(CostRunPartItem::getId));
    List<CostRunCostItem> costItems =
        costRunCostItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunCostItem.class)
                .eq(CostRunCostItem::getOaNo, oaNoValue)
                .eq(CostRunCostItem::getProductCode, productCodeValue)
                .orderByAsc(CostRunCostItem::getLineNo, CostRunCostItem::getId));

    CostRunContext context =
        CostRunContext.quote(
            oaNoValue,
            null,
            productCodeValue,
            null,
            result == null ? null : result.getCustomerName(),
            result == null ? null : result.getBusinessUnitType(),
            result == null ? null : result.getPeriod(),
            oaNoValue + "|" + productCodeValue);
    return CostRunObjectResult.of(
        context,
        result == null ? null : result.getId(),
        toResultDto(result),
        toPartDtos(partItems),
        toCostItemDtos(costItems));
  }

  @Override
  public CostRunRegressionCompareReport compareStoredSnapshot(
      String oaNo, String productCode, CostRunObjectResult candidateResult) {
    // T13 对账必须先读旧结果快照，再跑/传入新结果；这里不调用 CostRunEngine，避免重算覆盖旧基线。
    return compare(loadStoredSnapshot(oaNo, productCode), candidateResult);
  }

  @Override
  public CostRunRegressionCompareReport compare(
      CostRunObjectResult baselineSnapshot, CostRunObjectResult candidateResult) {
    String oaNo = firstText(oaNoOf(baselineSnapshot), oaNoOf(candidateResult));
    String productCode = firstText(productCodeOf(baselineSnapshot), productCodeOf(candidateResult));
    CostRunRegressionCompareReport report = CostRunRegressionCompareReport.of(oaNo, productCode);
    report.setBaselinePartCount(partItemsOf(baselineSnapshot).size());
    report.setCandidatePartCount(partItemsOf(candidateResult).size());
    report.setBaselineCostItemCount(costItemsOf(baselineSnapshot).size());
    report.setCandidateCostItemCount(costItemsOf(candidateResult).size());

    compareContext(report, baselineSnapshot, candidateResult);
    compareResult(report, resultOf(baselineSnapshot), resultOf(candidateResult));
    comparePartItems(report, partItemsOf(baselineSnapshot), partItemsOf(candidateResult));
    compareCostItems(report, costItemsOf(baselineSnapshot), costItemsOf(candidateResult));
    return report;
  }

  @Override
  public List<CostRunRegressionCompareReport> compareOaSnapshots(
      List<CostRunObjectResult> baselineSnapshots, List<CostRunObjectResult> candidateSnapshots) {
    Map<String, CostRunObjectResult> baselineMap = objectResultMap(baselineSnapshots);
    Map<String, CostRunObjectResult> candidateMap = objectResultMap(candidateSnapshots);
    List<CostRunRegressionCompareReport> reports = new ArrayList<>();
    for (String productCode : unionKeys(baselineMap, candidateMap)) {
      reports.add(compare(baselineMap.get(productCode), candidateMap.get(productCode)));
    }
    return List.copyOf(reports);
  }

  @Override
  public String renderMarkdownReport(String title, List<CostRunRegressionCompareReport> reports) {
    List<CostRunRegressionCompareReport> rows = reports == null ? List.of() : reports;
    long matchedCount = rows.stream().filter(CostRunRegressionCompareReport::isMatched).count();
    int differenceCount = rows.stream().mapToInt(r -> r.getDifferences().size()).sum();
    StringBuilder markdown = new StringBuilder();
    markdown
        .append("# ")
        .append(markdownText(firstText(title, "Cost Run Equivalence Report")))
        .append("\n\n")
        .append("| 指标 | 值 |\n")
        .append("| --- | --- |\n")
        .append("| 产品数 | ")
        .append(rows.size())
        .append(" |\n")
        .append("| 通过产品数 | ")
        .append(matchedCount)
        .append(" |\n")
        .append("| 差异数 | ")
        .append(differenceCount)
        .append(" |\n\n")
        .append("| OA | 产品 | 是否一致 | baseline part | actual part | baseline cost item | actual cost item | 差异数 |\n")
        .append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |\n");
    for (CostRunRegressionCompareReport report : rows) {
      markdown
          .append("| ")
          .append(markdownText(report.getOaNo()))
          .append(" | ")
          .append(markdownText(report.getProductCode()))
          .append(" | ")
          .append(report.isMatched() ? "YES" : "NO")
          .append(" | ")
          .append(report.getBaselinePartCount())
          .append(" | ")
          .append(report.getCandidatePartCount())
          .append(" | ")
          .append(report.getBaselineCostItemCount())
          .append(" | ")
          .append(report.getCandidateCostItemCount())
          .append(" | ")
          .append(report.getDifferences().size())
          .append(" |\n");
    }
    markdown.append("\n");
    if (differenceCount == 0) {
      markdown.append("无差异。\n");
      return markdown.toString();
    }
    markdown
        .append("## 差异明细\n\n")
        .append("| OA | 产品 | 区域 | 行键 | 字段 | baseline | actual | 说明 |\n")
        .append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
    for (CostRunRegressionCompareReport report : rows) {
      for (CostRunRegressionDifference difference : report.getDifferences()) {
        markdown
            .append("| ")
            .append(markdownText(report.getOaNo()))
            .append(" | ")
            .append(markdownText(report.getProductCode()))
            .append(" | ")
            .append(markdownText(difference.getSection()))
            .append(" | ")
            .append(markdownText(difference.getItemKey()))
            .append(" | ")
            .append(markdownText(difference.getFieldName()))
            .append(" | ")
            .append(markdownText(difference.getBaselineValue()))
            .append(" | ")
            .append(markdownText(difference.getCandidateValue()))
            .append(" | ")
            .append(markdownText(difference.getMessage()))
            .append(" |\n");
      }
    }
    return markdown.toString();
  }

  private void compareContext(
      CostRunRegressionCompareReport report,
      CostRunObjectResult baseline,
      CostRunObjectResult candidate) {
    compareText(
        report,
        SECTION_CONTEXT,
        "TOTAL",
        "businessUnitType",
        businessUnitTypeOf(baseline),
        businessUnitTypeOf(candidate));
    compareText(
        report,
        SECTION_CONTEXT,
        "TOTAL",
        "pricingMonth",
        pricingMonthOf(baseline),
        pricingMonthOf(candidate));
  }

  private void compareResult(
      CostRunRegressionCompareReport report,
      CostRunResultDto baseline,
      CostRunResultDto candidate) {
    if (baseline == null || candidate == null) {
      addRowDiff(report, SECTION_RESULT, "TOTAL", baseline, candidate);
      return;
    }
    compareText(report, SECTION_RESULT, "TOTAL", "oaNo", baseline.getOaNo(), candidate.getOaNo());
    compareText(
        report,
        SECTION_RESULT,
        "TOTAL",
        "productCode",
        baseline.getProductCode(),
        candidate.getProductCode());
    compareText(
        report,
        SECTION_RESULT,
        "TOTAL",
        "productName",
        baseline.getProductName(),
        candidate.getProductName());
    compareText(
        report,
        SECTION_RESULT,
        "TOTAL",
        "productModel",
        baseline.getProductModel(),
        candidate.getProductModel());
    compareText(
        report,
        SECTION_RESULT,
        "TOTAL",
        "customerName",
        baseline.getCustomerName(),
        candidate.getCustomerName());
    compareText(
        report,
        SECTION_RESULT,
        "TOTAL",
        "businessUnit",
        baseline.getBusinessUnit(),
        candidate.getBusinessUnit());
    compareText(
        report,
        SECTION_RESULT,
        "TOTAL",
        "department",
        baseline.getDepartment(),
        candidate.getDepartment());
    compareText(report, SECTION_RESULT, "TOTAL", "period", baseline.getPeriod(), candidate.getPeriod());
    compareText(
        report,
        SECTION_RESULT,
        "TOTAL",
        "currency",
        baseline.getCurrency(),
        candidate.getCurrency());
    compareText(report, SECTION_RESULT, "TOTAL", "unit", baseline.getUnit(), candidate.getUnit());
    compareText(
        report,
        SECTION_RESULT,
        "TOTAL",
        "calcStatus",
        baseline.getCalcStatus(),
        candidate.getCalcStatus());
    compareText(
        report,
        SECTION_RESULT,
        "TOTAL",
        "productAttr",
        baseline.getProductAttr(),
        candidate.getProductAttr());
    compareDecimal(
        report,
        SECTION_RESULT,
        "TOTAL",
        "totalCost",
        baseline.getTotalCost(),
        candidate.getTotalCost());
  }

  private void comparePartItems(
      CostRunRegressionCompareReport report,
      List<CostRunPartItemDto> baseline,
      List<CostRunPartItemDto> candidate) {
    Map<String, CostRunPartItemDto> baselineMap = partMap(baseline);
    Map<String, CostRunPartItemDto> candidateMap = partMap(candidate);
    for (String key : unionKeys(baselineMap, candidateMap)) {
      CostRunPartItemDto left = baselineMap.get(key);
      CostRunPartItemDto right = candidateMap.get(key);
      if (left == null || right == null) {
        addRowDiff(report, SECTION_PART_ITEM, key, left, right);
        continue;
      }
      compareText(report, SECTION_PART_ITEM, key, "partName", left.getPartName(), right.getPartName());
      compareText(report, SECTION_PART_ITEM, key, "material", left.getMaterial(), right.getMaterial());
      compareText(report, SECTION_PART_ITEM, key, "shapeAttr", left.getShapeAttr(), right.getShapeAttr());
      compareText(report, SECTION_PART_ITEM, key, "priceType", left.getPriceType(), right.getPriceType());
      compareText(report, SECTION_PART_ITEM, key, "priceSource", left.getPriceSource(), right.getPriceSource());
      compareDecimal(report, SECTION_PART_ITEM, key, "qty", left.getPartQty(), right.getPartQty());
      compareDecimal(report, SECTION_PART_ITEM, key, "unitPrice", left.getUnitPrice(), right.getUnitPrice());
      compareDecimal(report, SECTION_PART_ITEM, key, "amount", left.getAmount(), right.getAmount());
    }
  }

  private void compareCostItems(
      CostRunRegressionCompareReport report,
      List<CostRunCostItemDto> baseline,
      List<CostRunCostItemDto> candidate) {
    Map<String, CostRunCostItemDto> baselineMap = costMap(baseline);
    Map<String, CostRunCostItemDto> candidateMap = costMap(candidate);
    for (String key : unionKeys(baselineMap, candidateMap)) {
      CostRunCostItemDto left = baselineMap.get(key);
      CostRunCostItemDto right = candidateMap.get(key);
      if (left == null || right == null) {
        addRowDiff(report, SECTION_COST_ITEM, key, left, right);
        continue;
      }
      compareText(report, SECTION_COST_ITEM, key, "costName", left.getCostName(), right.getCostName());
      compareText(
          report,
          SECTION_COST_ITEM,
          key,
          "category",
          categoryText(left.getCategory()),
          categoryText(right.getCategory()));
      compareText(report, SECTION_COST_ITEM, key, "remark", left.getRemark(), right.getRemark());
      compareDecimal(report, SECTION_COST_ITEM, key, "baseAmount", left.getBaseAmount(), right.getBaseAmount());
      compareDecimal(report, SECTION_COST_ITEM, key, "rate", left.getRate(), right.getRate());
      compareDecimal(report, SECTION_COST_ITEM, key, "amount", left.getAmount(), right.getAmount());
    }
  }

  private Map<String, CostRunPartItemDto> partMap(List<CostRunPartItemDto> items) {
    List<CostRunPartItemDto> sorted = new ArrayList<>(items);
    sorted.sort(
        Comparator.comparing((CostRunPartItemDto i) -> text(i.getProductCode()))
            .thenComparing(i -> text(i.getPartCode()))
            .thenComparing(i -> text(i.getPartDrawingNo()))
            .thenComparing(i -> text(i.getPartName())));
    Map<String, CostRunPartItemDto> result = new LinkedHashMap<>();
    Map<String, Integer> counters = new LinkedHashMap<>();
    for (CostRunPartItemDto item : sorted) {
      String baseKey =
          text(item.getProductCode())
              + "|"
              + text(item.getPartCode())
              + "|"
              + text(item.getPartDrawingNo())
              + "|"
              + text(item.getPartName());
      int index = counters.merge(baseKey, 1, Integer::sum);
      result.put(baseKey + "#" + index, item);
    }
    return result;
  }

  private Map<String, CostRunObjectResult> objectResultMap(List<CostRunObjectResult> snapshots) {
    List<CostRunObjectResult> sorted =
        snapshots == null
            ? List.of()
            : snapshots.stream()
                .filter(snapshot -> snapshot != null && StringUtils.hasText(productCodeOf(snapshot)))
                .sorted(Comparator.comparing(snapshot -> text(productCodeOf(snapshot))))
                .toList();
    Map<String, CostRunObjectResult> result = new LinkedHashMap<>();
    Map<String, Integer> counters = new LinkedHashMap<>();
    for (CostRunObjectResult snapshot : sorted) {
      String baseKey = text(productCodeOf(snapshot));
      int index = counters.merge(baseKey, 1, Integer::sum);
      result.put(baseKey + "#" + index, snapshot);
    }
    return result;
  }

  private Map<String, CostRunCostItemDto> costMap(List<CostRunCostItemDto> items) {
    List<CostRunCostItemDto> sorted = new ArrayList<>(items);
    sorted.sort(
        Comparator.comparing((CostRunCostItemDto i) -> categoryText(i.getCategory()))
            .thenComparing(i -> text(i.getCostCode()))
            .thenComparing(i -> text(i.getCostName())));
    Map<String, CostRunCostItemDto> result = new LinkedHashMap<>();
    Map<String, Integer> counters = new LinkedHashMap<>();
    for (CostRunCostItemDto item : sorted) {
      String baseKey =
          categoryText(item.getCategory()) + "|" + text(item.getCostCode()) + "|" + text(item.getCostName());
      int index = counters.merge(baseKey, 1, Integer::sum);
      result.put(baseKey + "#" + index, item);
    }
    return result;
  }

  private List<String> unionKeys(Map<String, ?> baselineMap, Map<String, ?> candidateMap) {
    List<String> keys = new ArrayList<>(baselineMap.keySet());
    for (String key : candidateMap.keySet()) {
      if (!baselineMap.containsKey(key)) {
        keys.add(key);
      }
    }
    return keys;
  }

  private void compareText(
      CostRunRegressionCompareReport report,
      String section,
      String itemKey,
      String field,
      String baseline,
      String candidate) {
    if (!text(baseline).equals(text(candidate))) {
      report.addDifference(
          CostRunRegressionDifference.of(
              section, itemKey, field, baseline, candidate, "文本字段不一致"));
    }
  }

  private void compareDecimal(
      CostRunRegressionCompareReport report,
      String section,
      String itemKey,
      String field,
      BigDecimal baseline,
      BigDecimal candidate) {
    if (decimalEquals(baseline, candidate)) {
      return;
    }
    report.addDifference(
        CostRunRegressionDifference.of(
            section,
            itemKey,
            field,
            decimalText(baseline),
            decimalText(candidate),
            "金额或数量字段不一致"));
  }

  private void addRowDiff(
      CostRunRegressionCompareReport report,
      String section,
      String itemKey,
      Object baseline,
      Object candidate) {
    report.addDifference(
        CostRunRegressionDifference.of(
            section,
            itemKey,
            "_row",
            baseline == null ? null : "存在",
            candidate == null ? null : "存在",
            "对账行缺失"));
  }

  private boolean decimalEquals(BigDecimal baseline, BigDecimal candidate) {
    if (baseline == null || candidate == null) {
      return baseline == candidate;
    }
    return baseline.subtract(candidate).abs().compareTo(DECIMAL_TOLERANCE) <= 0;
  }

  private String decimalText(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private CostRunResultDto toResultDto(CostRunResult result) {
    if (result == null) {
      return null;
    }
    CostRunResultDto dto = new CostRunResultDto();
    dto.setOaNo(result.getOaNo());
    dto.setProductCode(result.getProductCode());
    dto.setProductName(result.getProductName());
    dto.setProductModel(result.getProductModel());
    dto.setCustomerName(result.getCustomerName());
    dto.setBusinessUnit(result.getBusinessUnit());
    dto.setDepartment(result.getDepartment());
    dto.setPeriod(result.getPeriod());
    dto.setCurrency(result.getCurrency());
    dto.setUnit(result.getUnit());
    dto.setTotalCost(result.getTotalCost());
    dto.setCalcStatus(result.getCalcStatus());
    dto.setProductAttr(result.getProductAttr());
    return dto;
  }

  private List<CostRunPartItemDto> toPartDtos(List<CostRunPartItem> items) {
    List<CostRunPartItemDto> result = new ArrayList<>();
    if (items == null) {
      return result;
    }
    for (CostRunPartItem item : items) {
      CostRunPartItemDto dto = new CostRunPartItemDto();
      dto.setOaNo(item.getOaNo());
      dto.setProductCode(item.getProductCode());
      dto.setPartCode(item.getPartCode());
      dto.setPartName(item.getPartName());
      dto.setPartDrawingNo(item.getPartDrawingNo());
      dto.setPartQty(item.getQty());
      dto.setMaterial(item.getMaterial());
      dto.setShapeAttr(item.getShapeAttr());
      dto.setPriceSource(item.getPriceSource());
      dto.setUnitPrice(item.getUnitPrice());
      dto.setAmount(item.getAmount());
      dto.setRemark(item.getRemark());
      result.add(dto);
    }
    return result;
  }

  private List<CostRunCostItemDto> toCostItemDtos(List<CostRunCostItem> items) {
    List<CostRunCostItemDto> result = new ArrayList<>();
    if (items == null) {
      return result;
    }
    for (CostRunCostItem item : items) {
      CostRunCostItemDto dto = new CostRunCostItemDto();
      dto.setCostCode(item.getCostCode());
      dto.setCostName(item.getCostName());
      dto.setBaseAmount(item.getBaseAmount());
      dto.setRate(item.getRate());
      dto.setAmount(item.getAmount());
      dto.setRemark(item.getRemark());
      dto.setCategory(item.getCategory());
      result.add(dto);
    }
    return result;
  }

  private List<CostRunPartItemDto> partItemsOf(CostRunObjectResult result) {
    return result == null || result.getPartItems() == null ? List.of() : result.getPartItems();
  }

  private List<CostRunCostItemDto> costItemsOf(CostRunObjectResult result) {
    return result == null || result.getCostItems() == null ? List.of() : result.getCostItems();
  }

  private CostRunResultDto resultOf(CostRunObjectResult result) {
    return result == null ? null : result.getResult();
  }

  private String oaNoOf(CostRunObjectResult result) {
    if (result == null) {
      return null;
    }
    if (result.getContext() != null && StringUtils.hasText(result.getContext().getOaNo())) {
      return result.getContext().getOaNo();
    }
    return result.getResult() == null ? null : result.getResult().getOaNo();
  }

  private String productCodeOf(CostRunObjectResult result) {
    if (result == null) {
      return null;
    }
    if (result.getContext() != null && StringUtils.hasText(result.getContext().getProductCode())) {
      return result.getContext().getProductCode();
    }
    return result.getResult() == null ? null : result.getResult().getProductCode();
  }

  private String businessUnitTypeOf(CostRunObjectResult result) {
    return result == null || result.getContext() == null ? null : result.getContext().getBusinessUnitType();
  }

  private String pricingMonthOf(CostRunObjectResult result) {
    if (result != null && result.getContext() != null && StringUtils.hasText(result.getContext().getPricingMonth())) {
      return result.getContext().getPricingMonth();
    }
    return result == null || result.getResult() == null ? null : result.getResult().getPeriod();
  }

  private String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    return StringUtils.hasText(second) ? second.trim() : null;
  }

  private String requireText(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(fieldName + " 不能为空");
    }
    return value.trim();
  }

  private String text(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  private String categoryText(String value) {
    return StringUtils.hasText(value) ? value.trim() : DEFAULT_COST_CATEGORY;
  }

  private String markdownText(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("|", "\\|").replace("\r", " ").replace("\n", " ");
  }
}
