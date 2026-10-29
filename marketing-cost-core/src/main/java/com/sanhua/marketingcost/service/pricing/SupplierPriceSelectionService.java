package com.sanhua.marketingcost.service.pricing;

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

  public record Selection(
      SupplierPriceCandidate candidate,
      SupplierSupplyRatio ratio,
      String rule,
      String message,
      SupplierPriceReview review) {}

  private final SupplierSupplyRatioMapper ratios;
  private final SupplierPriceDecisionMapper decisions;

  public SupplierPriceSelectionService(
      SupplierSupplyRatioMapper ratios, SupplierPriceDecisionMapper decisions) {
    this.ratios = ratios;
    this.decisions = decisions;
  }

  public Selection select(SupplierPriceScope scope, List<SupplierPriceCandidate> candidates) {
    return select(scope, candidates,
        candidates.isEmpty() ? List.of() : activeRatios(scope));
  }

  public Selection select(
      SupplierPriceScope scope,
      List<SupplierPriceCandidate> candidates,
      List<SupplierSupplyRatio> active) {
    return selectPolicy(scope,candidates,active,()->scope.oaFormItemId()==null?null:decisions.latest(scopeKey(scope)));
  }

  /** Same selection policy with batch-loaded review input; this operation performs no SQL. */
  public Selection selectPrepared(SupplierPriceScope scope,List<SupplierPriceCandidate> candidates,
      List<SupplierSupplyRatio> active,SupplierPriceDecision decision){
    if(decision!=null && !Objects.equals(scopeKey(scope),decision.getScopeKey()))throw new IllegalArgumentException("Prepared supplier decision belongs to another consumer scope");
    return selectPolicy(scope,candidates,active,()->scope.oaFormItemId()==null?null:decision);
  }

  private Selection selectPolicy(SupplierPriceScope scope,List<SupplierPriceCandidate> candidates,
      List<SupplierSupplyRatio> active,java.util.function.Supplier<SupplierPriceDecision> readDecision){
    boolean hadPrices = !candidates.isEmpty();
    candidates = SupplierRatioPricePolicy.eligible(candidates, active);
    var bySupplier = SupplierRatioPricePolicy.index(active);
    if (candidates.isEmpty()) return new Selection(null, null, "NO_PRICE",
        hadPrices ? "有效报价对应供应商已明确退出供货，请补充其他供货价格" : "没有有效价格", null);
    if (candidates.size() == 1) {
      return new Selection(candidates.get(0), SupplierRatioPricePolicy.match(bySupplier, candidates.get(0)), "SINGLE", "单一供应商，直接取价", null);
    }
    if (samePrice(candidates)) {
      // 同一有效取价范围内价格一致，不需要供货比率或人工确认；沿用稳定的供应商并列顺序。
      var same = SupplierRatioPricePolicy.highest(candidates);
      return new Selection(same, SupplierRatioPricePolicy.match(bySupplier, same),
          "SAME_PRICE", "各供应商有效单价相同，直接取价", null);
    }
    SupplierPriceCandidate chosen = SupplierRatioPricePolicy.choose(candidates, active);
    if (chosen != null) {
      return new Selection(
          chosen, SupplierRatioPricePolicy.match(bySupplier, chosen), "RATIO_MAX", "按最大供货比率取价，比率并列时取最高价", null);
    }
    String scopeKey = scopeKey(scope);
    String fingerprint = fingerprint(candidates, active);
    SupplierPriceDecision decision = readDecision.get();
    boolean fallback =
        decision != null
            && FALLBACK_HIGH.equals(decision.getDecision())
            && fingerprint.equals(decision.getFingerprint());
    boolean waiting = decision != null && WAIT_IMPORT.equals(decision.getDecision());
    String status = fallback ? FALLBACK_HIGH : waiting ? WAIT_IMPORT : "PENDING";
    String message =
        fallback ? "供货比率不足，已确认无新增审核，按最高价取价" : waiting ? "等待导入供货比率，导入后自动取价" : "供货比率不足，请确认是否有新增审核";
    var views =
        candidates.stream()
            .map(
                c ->
                    new SupplierPriceReview.Candidate(
                        c,
                        SupplierRatioPricePolicy.match(bySupplier, c) != null
                            ? SupplierRatioPricePolicy.match(bySupplier, c).getSupplyRatio()
                            : null))
            .toList();
    SupplierPriceReview review =
        new SupplierPriceReview(
            scopeKey,
            fingerprint,
            scope,
            views,
            status,
            fallback || waiting ? decision.getConfirmedBy() : null,
            fallback || waiting ? decision.getConfirmedAt() : null,
            message);
    chosen = fallback ? SupplierRatioPricePolicy.highest(candidates) : null;
    return new Selection(
        chosen,
        chosen == null ? null : SupplierRatioPricePolicy.match(bySupplier, chosen),
        status,
        message,
        review);
  }

  private static boolean samePrice(List<SupplierPriceCandidate> candidates) {
    if (candidates.size() < 2 || candidates.get(0).unitPrice() == null) return false;
    BigDecimal price = candidates.get(0).unitPrice();
    return price.signum() >= 0 && candidates.stream()
        .allMatch(candidate -> candidate.unitPrice() != null && price.compareTo(candidate.unitPrice()) == 0);
  }

  public List<SupplierSupplyRatio> activeRatios(SupplierPriceScope scope) {
    return ratios.selectList(SupplierSupplyRatioMapper.currentQuery(
        scope.businessUnitType(), List.of(scope.materialCode()), scope.pricingDate()));
  }

  /** Bounded batch read of all current relations, independent of supplier/price counts. */
  public java.util.Map<String, List<SupplierSupplyRatio>> prepareRatios(
      String businessUnit, java.util.Set<String> materials, java.time.LocalDate pricingDate) {
    if (materials.isEmpty()) return java.util.Map.of();
    var rows = new ArrayList<com.sanhua.marketingcost.entity.monthly.MonthlySupplierRatioInput>();
    var codes = new ArrayList<>(materials);
    for (int start = 0; start < codes.size(); start += 256)
      rows.addAll(ratios.currentRelations(
          businessUnit, pricingDate, codes.subList(start, Math.min(start + 256, codes.size()))));
    var result = new java.util.LinkedHashMap<String,List<SupplierSupplyRatio>>();
    rows.forEach(row -> result.computeIfAbsent(row.getRequestedMaterialCode(), ignored -> new ArrayList<>()).add(row));
    return result;
  }

  public static String scopeKey(SupplierPriceScope s) {
    return digest(
        List.of(
            text(s.businessUnitType()),
            text(s.oaNo()),
            text(s.oaFormItemId()),
            text(s.periodMonth()),
            text(s.pricingDate()),
            text(s.orgCode()),
            text(s.materialCode()),
            text(s.priceType())));
  }

  private static String fingerprint(
      List<SupplierPriceCandidate> candidates, List<SupplierSupplyRatio> ratios) {
    List<String> values = new ArrayList<>();
    candidates.stream()
        .sorted(Comparator.comparing(SupplierPriceCandidate::supplierCode))
        .forEach(
            c ->
                values.add(
                    digest(
                        List.of(
                            c.key(),
                            c.supplierCode(),
                            number(c.unitPrice()),
                            text(c.effectiveFrom()),
                            text(c.effectiveTo())))));
    values.add("RATIOS");
    ratios.stream()
        .sorted(Comparator.comparing(SupplierPriceSelectionService::ratioIdentity))
        .forEach(r -> values.add(digest(List.of(ratioIdentity(r), number(r.getSupplyRatio())))));
    return digest(values);
  }

  private static String ratioIdentity(SupplierSupplyRatio ratio) {
    return org.springframework.util.StringUtils.hasText(ratio.getSupplierCode()) ? ratio.getSupplierCode()
        : com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.supplierIdentity(null, ratio.getSupplierName());
  }

  private static String digest(List<String> parts) {
    StringBuilder value = new StringBuilder();
    parts.forEach(p -> value.append(p.length()).append(':').append(p));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256不可用", e);
    }
  }

  private static String number(BigDecimal value) {
    return value == null ? "NULL" : value.stripTrailingZeros().toPlainString();
  }

  private static String text(Object value) {
    return Objects.toString(value, "");
  }
}
