package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReview;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 上方核算提示与下方缺口明细使用同一展示口径，不执行取价、不改变业务缺口。 */
public final class PricePrepareGapPresentation {
  private PricePrepareGapPresentation() {}

  public static void enrich(QuotePricePrepareWorkbenchResponse response) {
    if (response == null || response.getGaps() == null || response.getGaps().getRecords() == null) return;
    var reviews = response.getSupplierPriceReviews() == null ? List.<SupplierPriceReview>of() : response.getSupplierPriceReviews();
    for (var gap : response.getGaps().getRecords()) {
      String code = material(gap);
      var review = reviews.stream().filter(r -> r != null && r.scope() != null
          && Objects.equals(code, r.scope().materialCode())
          && Set.of("PENDING", "WAIT_IMPORT").contains(text(r.status()))).findFirst().orElse(null);
      boolean priceGap = Set.of("MISSING_PRICE", "PRICE_SOURCE_ERROR").contains(text(gap.getGapType()));
      if ((priceGap && review != null) || "SUPPLIER_RATIO_REVIEW_REQUIRED".equals(gap.getReasonCode())) {
        boolean absent = review == null || review.candidates() == null || review.candidates().stream().allMatch(c -> c.supplyRatio() == null);
        view(gap, "SUPPLIER_RATIO_REVIEW_REQUIRED", absent ? "缺少供货比率" : "供货比率不足",
            review != null && "WAIT_IMPORT".equals(review.status())
                ? "已有供应商报价，等待财务导入供货比率后取价"
                : "已有多个供应商报价，供货比率无法确定取价；请财务维护供货比率或确认取价方式",
            "维护供货比率或确认取价方式");
      } else if ("MISSING_RAW_PRICE".equals(gap.getReasonCode())) {
        view(gap, "MISSING_RAW_PRICE", "缺少原料价格", "未找到本次适用的原料价格，请补齐对应价格来源", "维护原料价格");
      } else if ("MISSING_SCRAP_PRICE".equals(gap.getReasonCode())) {
        view(gap, "MISSING_SCRAP_PRICE", "缺少废料价格", "未找到本次适用的废料价格，请补齐对应价格来源", "维护废料价格");
      } else if ("MISSING_PRICE".equals(gap.getGapType()) && !"WAITING".equals(gap.getReasonCode())) {
        view(gap, text(gap.getReasonCode()), "缺少可用价格", "本次取价范围内未找到可用价格，请核实对应价格来源", "维护价格来源或补价");
      } else if ("PRICE_SOURCE_ERROR".equals(gap.getGapType())) {
        view(gap, text(gap.getReasonCode()), "取价异常", text(gap.getMessage()), "核对取价来源及异常原因");
      } else {
        view(gap, text(gap.getReasonCode()), "WAITING".equals(gap.getReasonCode()) ? "价格待就绪" : "计价资料待完善",
            text(gap.getMessage()), text(gap.getActionTarget()));
      }
    }
  }

  public static String summary(QuotePricePrepareWorkbenchResponse response, String fallback) {
    enrich(response);
    if (response == null || response.getGaps() == null || response.getGaps().getRecords() == null || response.getGaps().getRecords().isEmpty()) return fallback;
    Map<String, LinkedHashSet<String>> groups = new LinkedHashMap<>();
    for (var gap : response.getGaps().getRecords()) groups.computeIfAbsent(gap.getDisplayGapType(), ignored -> new LinkedHashSet<>()).add(material(gap));
    String message = groups.entrySet().stream().map(entry -> entry.getKey() + "："
        + entry.getValue().stream().limit(8).collect(Collectors.joining("、"))
        + (entry.getValue().size() > 8 ? "等" + entry.getValue().size() + "种物料" : ""))
        .collect(Collectors.joining("；"));
    if (groups.containsKey("缺少供货比率") || groups.containsKey("供货比率不足")) message += "；供货比率请财务维护或确认取价方式";
    return message;
  }

  public static String blockingCode(QuotePricePrepareWorkbenchResponse response) {
    enrich(response);
    var gaps = response == null || response.getGaps() == null ? null : response.getGaps().getRecords();
    if (gaps == null || gaps.isEmpty()) return "PRICE_MISSING";
    long ratios = gaps.stream().filter(g -> "SUPPLIER_RATIO_REVIEW_REQUIRED".equals(g.getDisplayReasonCode())).count();
    return ratios == gaps.size() ? "SUPPLIER_RATIO_REVIEW_REQUIRED"
        : ratios > 0 ? "PRICE_AND_SUPPLIER_RATIO_MISSING" : "PRICE_MISSING";
  }

  private static void view(PricePrepareGap gap, String reason, String type, String message, String action) {
    gap.setDisplayReasonCode(reason); gap.setDisplayGapType(type); gap.setDisplayMessage(message); gap.setDisplayAction(action);
  }
  private static String material(PricePrepareGap gap) { return !text(gap.getGapMaterialCode()).isBlank() ? gap.getGapMaterialCode() : !text(gap.getMaterialCode()).isBlank() ? gap.getMaterialCode() : "未标识物料"; }
  private static String text(String value) { return value == null ? "" : value; }
}
