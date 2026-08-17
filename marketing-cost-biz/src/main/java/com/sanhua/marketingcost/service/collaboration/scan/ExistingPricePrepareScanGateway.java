package com.sanhua.marketingcost.service.collaboration.scan;

import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.service.PricePrepareBomItemLoader;
import com.sanhua.marketingcost.service.PricePrepareService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 调用现有价格体系的纯计算入口；不生成价格批次，不自行决定新品价格类型。 */
@Component
public class ExistingPricePrepareScanGateway implements QuoteCollaborationPriceScanGateway {

  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILED = "FAILED";
  private static final String GAP_TYPE_MISSING_PRICE = "MISSING_PRICE";
  private static final String GAP_TYPE_MISSING_PRICE_TYPE = "MISSING_PRICE_TYPE";
  private static final String ACTION_MAINTAIN_PRICE = "MAINTAIN_PRICE";

  private final PricePrepareService pricePrepareService;
  private final PricePrepareBomItemLoader bomItemLoader;

  public ExistingPricePrepareScanGateway(PricePrepareService pricePrepareService) {
    this(pricePrepareService, null);
  }

  @Autowired
  public ExistingPricePrepareScanGateway(
      PricePrepareService pricePrepareService,
      PricePrepareBomItemLoader bomItemLoader) {
    this.pricePrepareService = pricePrepareService;
    this.bomItemLoader = bomItemLoader;
  }

  @Override
  public CollaborationPriceScanResult check(QuoteCollaborationScanContext context) {
    if (context == null) {
      return CollaborationPriceScanResult.error("价格检查上下文为空");
    }
    try {
      PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
      request.setOaNo(context.oaNo());
      request.setOaFormItemId(context.oaFormItemId());
      request.setTopProductCode(context.productCode());
      request.setPeriodMonth(context.accountingMonth());
      request.setPriceAsOfTime(context.scanAt());
      request.setBusinessUnitType(context.businessUnitType());
      request.setSourceType("U9");
      PricePrepareCalculationResult calculation = pricePrepareService.calculate(request);
      if (calculation == null || calculation.getSummary() == null) {
        return CollaborationPriceScanResult.error("现有价格准备没有返回汇总结果");
      }
      PricePrepareGenerateResult summary = calculation.getSummary();
      List<PricePrepareGap> gaps =
          calculation.getGaps() == null ? List.of() : calculation.getGaps();
      if (STATUS_FAILED.equals(summary.getStatus())) {
        return CollaborationPriceScanResult.error(
            firstText(summary.getMessage(), "现有价格准备执行失败"));
      }
      List<PricePrepareGap> nonPriceGaps =
          gaps.stream().filter(gap -> !isRealPriceGap(gap)).toList();
      if (!nonPriceGaps.isEmpty()) {
        return CollaborationPriceScanResult.error(
            "价格准备仍存在结构或主档缺口，不能误生成技术补价任务："
                + firstText(nonPriceGaps.get(0).getMessage(), nonPriceGaps.get(0).getGapType()));
      }
      if (!gaps.isEmpty()) {
        return CollaborationPriceScanResult.gaps(
            summary.getTotalCount(), enrich(context, gaps));
      }
      if (!STATUS_SUCCESS.equals(summary.getStatus()) || summary.getGapCount() != 0) {
        return CollaborationPriceScanResult.error(
            firstText(summary.getMessage(), "价格准备汇总状态与缺口明细不一致"));
      }
      if (summary.getTotalCount() <= 0) {
        return CollaborationPriceScanResult.error("价格准备没有形成任何可检查明细");
      }
      return CollaborationPriceScanResult.ready(summary.getTotalCount());
    } catch (RuntimeException exception) {
      return CollaborationPriceScanResult.error(
          "现有价格准备只读计算失败：" + exceptionMessage(exception));
    }
  }

  private boolean isRealPriceGap(PricePrepareGap gap) {
    if (gap == null || !StringUtils.hasText(gap.getGapMaterialCode())) {
      return false;
    }
    if (GAP_TYPE_MISSING_PRICE_TYPE.equals(gap.getGapType())) {
      // 新品无价格类型时由技术选择正式体系中的价格类型，不再误判成系统结构异常。
      return true;
    }
    return GAP_TYPE_MISSING_PRICE.equals(gap.getGapType())
        && ACTION_MAINTAIN_PRICE.equals(gap.getActionType());
  }

  private List<CollaborationPriceScanResult.PriceGap> enrich(
      QuoteCollaborationScanContext context, List<PricePrepareGap> gaps) {
    List<BomCostingRow> rows = loadRows(context);
    List<CollaborationPriceScanResult.PriceGap> result = new ArrayList<>();
    for (PricePrepareGap gap : gaps) {
      String target = firstText(gap.getGapMaterialCode(), gap.getMaterialCode());
      List<BomCostingRow> exact = rows.stream()
          .filter(row -> same(target, row == null ? null : row.getMaterialCode()))
          .toList();
      List<BomCostingRow> positions = exact.isEmpty()
          ? rows.stream().filter(row -> same(gap.getMaterialCode(),
              row == null ? null : row.getMaterialCode())).toList()
          : exact;
      if (positions.isEmpty()) {
        result.add(toGap(context, gap, null, target));
      } else {
        for (BomCostingRow row : positions) {
          result.add(toGap(context, gap, row, target));
        }
      }
    }
    return aggregate(result);
  }

  private List<CollaborationPriceScanResult.PriceGap> aggregate(
      List<CollaborationPriceScanResult.PriceGap> gaps) {
    Map<String, CollaborationPriceScanResult.PriceGap> result = new LinkedHashMap<>();
    for (CollaborationPriceScanResult.PriceGap gap : gaps) {
      String key = String.join("|", safe(gap.materialCode()), safe(gap.gapType()),
          safe(gap.actionType()), safe(gap.sourceType()), safe(gap.bomPath()),
          safe(gap.materialRole()), safe(gap.accountingMonth()), safe(gap.applicableOrgCode()));
      result.merge(key, gap, (left, right) -> copyWithQuantity(left,
          add(left.bomQuantity(), right.bomQuantity())));
    }
    return List.copyOf(result.values());
  }

  private CollaborationPriceScanResult.PriceGap copyWithQuantity(
      CollaborationPriceScanResult.PriceGap gap, BigDecimal quantity) {
    return new CollaborationPriceScanResult.PriceGap(
        gap.materialCode(), gap.gapType(), gap.actionType(), gap.reason(), gap.sourceTable(),
        gap.existingOfficialPriceType(), gap.sourceType(), gap.sourceId(), gap.bomNodeKey(),
        gap.bomPath(), gap.materialName(), gap.materialSpec(), gap.materialModel(),
        gap.materialRole(), quantity, gap.bomUnit(), gap.accountingMonth(),
        gap.applicableOrgCode());
  }

  private BigDecimal add(BigDecimal left, BigDecimal right) {
    if (left == null) return right;
    if (right == null) return left;
    return left.add(right).stripTrailingZeros();
  }

  private CollaborationPriceScanResult.PriceGap toGap(
      QuoteCollaborationScanContext context,
      PricePrepareGap gap,
      BomCostingRow row,
      String target) {
    boolean exact = row != null && same(target, row.getMaterialCode());
    String path = row == null ? null : row.getPath();
    if (!exact && StringUtils.hasText(path) && StringUtils.hasText(target)) {
      path = path.endsWith("/") ? path + target + "/" : path + "/" + target + "/";
    }
    Long sourceId = row == null ? null
        : row.getId() == null ? row.getRawHierarchyNodeId() : row.getId();
    String sourceTable = trimToNull(gap.getSourceTable());
    String reason = gap.getMessage();
    if (sourceTable != null) {
      reason = firstText(reason, "当前报价条件下无法取得有效价格")
          + "（取价来源：" + sourceTable + "）";
    }
    return new CollaborationPriceScanResult.PriceGap(
        target,
        gap.getGapType(),
        gap.getActionType(),
        reason,
        sourceTable,
        trimToNull(gap.getPriceType()),
        sourceType(gap),
        sourceId,
        row == null ? null : nodeKey(row),
        path,
        row == null ? null : row.getMaterialName(),
        row == null ? null : row.getMaterialSpec(),
        null,
        materialRole(gap, row),
        exact && row != null ? row.getQtyPerTop() : null,
        exact && row != null ? row.getUnit() : null,
        context.accountingMonth(),
        context.priceOrgCode());
  }

  private List<BomCostingRow> loadRows(QuoteCollaborationScanContext context) {
    if (bomItemLoader == null) return List.of();
    List<BomCostingRow> rows = bomItemLoader.loadByQuoteItem(
        context.oaNo(), context.oaFormItemId(), context.productCode(), context.accountingMonth());
    return rows == null ? List.of() : rows;
  }

  private String sourceType(PricePrepareGap gap) {
    String itemType = trimToNull(gap.getItemType());
    if ("MAKE_PART".equals(itemType)) return "MAKE_PART_GAP";
    if ("PACKAGE_COMPONENT".equals(itemType)) return "PACKAGE_GAP";
    return "PRICE_PREPARE";
  }

  private String materialRole(PricePrepareGap gap, BomCostingRow row) {
    if ("PACKAGE_COMPONENT".equals(trimToNull(gap.getItemType()))) return "PACKAGE";
    String text = String.join(" ", safe(gap.getMessage()),
        row == null ? "" : safe(row.getMaterialName()),
        row == null ? "" : safe(row.getSourceCategory()),
        row == null ? "" : safe(row.getCostElementCode()));
    if (text.contains("废料") || text.contains("废铜") || text.contains("边角")) return "SCRAP";
    if (text.contains("原材料") || text.contains("原料")) return "RAW";
    return "NORMAL";
  }

  private String nodeKey(BomCostingRow row) {
    if (row.getRawHierarchyNodeId() != null) return "RAW:" + row.getRawHierarchyNodeId();
    if (row.getId() != null) return "COSTING:" + row.getId();
    return trimToNull(row.getPath());
  }

  private boolean same(String left, String right) {
    return trimToNull(left) != null && trimToNull(left).equals(trimToNull(right));
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private String firstText(String first, String fallback) {
    return StringUtils.hasText(first) ? first.trim() : fallback;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String exceptionMessage(RuntimeException exception) {
    return StringUtils.hasText(exception.getMessage())
        ? exception.getMessage().trim()
        : exception.getClass().getSimpleName();
  }
}
