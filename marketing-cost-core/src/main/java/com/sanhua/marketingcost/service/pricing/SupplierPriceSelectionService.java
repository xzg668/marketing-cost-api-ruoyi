package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.SupplierPriceDecision;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierPriceDecisionMapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class SupplierPriceSelectionService {
  public static final String REVIEW_REQUIRED = "SUPPLIER_RATIO_REVIEW_REQUIRED";
  public static final String WAIT_IMPORT = "WAIT_IMPORT";
  public static final String FALLBACK_HIGH = "FALLBACK_HIGH";
  public record Selection(SupplierPriceCandidate candidate, SupplierSupplyRatio ratio,
      String rule, String message, SupplierPriceReview review) {}

  private final SupplierSupplyRatioMapper ratios;
  private final SupplierPriceDecisionMapper decisions;

  public SupplierPriceSelectionService(SupplierSupplyRatioMapper ratios,
      SupplierPriceDecisionMapper decisions) {
    this.ratios = ratios;
    this.decisions = decisions;
  }

  public Selection select(SupplierPriceScope scope, List<SupplierPriceCandidate> candidates) {
    if (candidates.isEmpty()) return new Selection(null, null, "NO_PRICE", "没有有效价格", null);
    if (candidates.size() == 1) {
      return new Selection(candidates.get(0), null, "SINGLE", "单一供应商，直接取价", null);
    }
    List<SupplierSupplyRatio> active = activeRatios(scope);
    var bySupplier = SupplierRatioPricePolicy.index(active);
    SupplierPriceCandidate chosen = SupplierRatioPricePolicy.choose(candidates, active);
    if (chosen != null) {
      return new Selection(chosen, bySupplier.get(chosen.supplierCode()), "RATIO_MAX",
          "按最大供货比率取价，比率并列时取最高价", null);
    }
    String scopeKey = scopeKey(scope);
    String fingerprint = fingerprint(candidates, active);
    SupplierPriceDecision decision = scope.oaFormItemId() == null ? null : decisions.latest(scopeKey);
    boolean fallback = decision != null && FALLBACK_HIGH.equals(decision.getDecision())
        && fingerprint.equals(decision.getFingerprint());
    boolean waiting = decision != null && WAIT_IMPORT.equals(decision.getDecision());
    String status = fallback ? FALLBACK_HIGH : waiting ? WAIT_IMPORT : "PENDING";
    String message = fallback ? "供货比率不足，已确认无新增审核，按最高价取价"
        : waiting ? "等待导入供货比率，导入后自动取价" : "供货比率不足，请确认是否有新增审核";
    var views = candidates.stream().map(c -> new SupplierPriceReview.Candidate(c,
        bySupplier.containsKey(c.supplierCode()) ? bySupplier.get(c.supplierCode()).getSupplyRatio() : null)).toList();
    SupplierPriceReview review = new SupplierPriceReview(scopeKey, fingerprint, scope, views,
        status, fallback || waiting ? decision.getConfirmedBy() : null,
        fallback || waiting ? decision.getConfirmedAt() : null, message);
    chosen = fallback ? SupplierRatioPricePolicy.highest(candidates) : null;
    return new Selection(chosen, chosen == null ? null : bySupplier.get(chosen.supplierCode()),
        status, message, review);
  }

  public List<SupplierSupplyRatio> activeRatios(SupplierPriceScope scope) {
    return ratios.selectList(Wrappers.lambdaQuery(SupplierSupplyRatio.class)
        .eq(SupplierSupplyRatio::getBusinessUnitType, scope.businessUnitType())
        .eq(SupplierSupplyRatio::getMaterialCode, scope.materialCode())
        .eq(SupplierSupplyRatio::getIsActive, 1).eq(SupplierSupplyRatio::getDeleted, 0)
        .and(q -> q.isNull(SupplierSupplyRatio::getEffectiveFrom)
            .or().le(SupplierSupplyRatio::getEffectiveFrom, scope.pricingDate()))
        .and(q -> q.isNull(SupplierSupplyRatio::getEffectiveTo)
            .or().ge(SupplierSupplyRatio::getEffectiveTo, scope.pricingDate())));
  }

  public static String scopeKey(SupplierPriceScope s) {
    return digest(List.of(text(s.businessUnitType()), text(s.oaNo()), text(s.oaFormItemId()),
        text(s.periodMonth()), text(s.pricingDate()), text(s.orgCode()),
        text(s.materialCode()), text(s.priceType())));
  }

  private static String fingerprint(List<SupplierPriceCandidate> candidates,
      List<SupplierSupplyRatio> ratios) {
    List<String> values = new ArrayList<>();
    candidates.stream().sorted(Comparator.comparing(SupplierPriceCandidate::supplierCode))
        .forEach(c -> values.add(digest(List.of(c.supplierCode(), number(c.unitPrice()),
            text(c.effectiveFrom()), text(c.effectiveTo())))));
    values.add("RATIOS");
    ratios.stream().sorted(Comparator.comparing(SupplierSupplyRatio::getSupplierCode))
        .forEach(r -> values.add(digest(List.of(r.getSupplierCode(), number(r.getSupplyRatio())))));
    return digest(values);
  }

  private static String digest(List<String> parts) {
    StringBuilder value = new StringBuilder();
    parts.forEach(p -> value.append(p.length()).append(':').append(p));
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256不可用", e);
    }
  }

  private static String number(BigDecimal value) {
    return value == null ? "NULL" : value.stripTrailingZeros().toPlainString();
  }
  private static String text(Object value) { return Objects.toString(value, ""); }
}
