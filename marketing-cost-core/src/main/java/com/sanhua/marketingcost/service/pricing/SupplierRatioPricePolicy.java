package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 与价格来源无关的供应商选择规则，不写数据，也不替报价员确认。 */
public final class SupplierRatioPricePolicy {
  private SupplierRatioPricePolicy() {}

  /** Determine the main suppliers from the complete ratio set, before looking at a price table. */
  public static List<SupplierSupplyRatio> primarySuppliers(List<SupplierSupplyRatio> ratios) {
    index(ratios);
    var known=ratios.stream().filter(r->r.getSupplyRatio()!=null).toList();
    var total=known.stream().map(SupplierSupplyRatio::getSupplyRatio).reduce(BigDecimal.ZERO,BigDecimal::add);
    var max=known.stream().map(SupplierSupplyRatio::getSupplyRatio).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    if (total.compareTo(BigDecimal.ONE)>0 || max.signum()<=0
        || max.compareTo(BigDecimal.ONE.subtract(total))<=0) return List.of();
    return known.stream().filter(r->r.getSupplyRatio().compareTo(max)==0).toList();
  }

  public static <T> List<T> forPrimarySupplier(List<T> rows,List<SupplierSupplyRatio> ratios,
      Function<T,String> code,Function<T,String> name) {
    // Source rows are already scoped to the requested material and price conditions.
    // A sole quoted supplier is usable even when another supplier is configured as primary.
    if (hasSingleSupplier(rows, code, name)) return rows;
    var primary=primarySuppliers(ratios);
    if(primary.isEmpty())return rows;
    // Resolve names against the complete relation set before applying the primary constraint.
    var bySupplier=index(ratios);
    return rows.stream().filter(row->primary.contains(match(bySupplier,new SupplierPriceCandidate("",code.apply(row),
        name.apply(row),BigDecimal.ZERO,null,null)))).toList();
  }

  public static <T> boolean hasSingleSupplier(List<T> rows, Function<T,String> code, Function<T,String> name) {
    var identities = new java.util.HashSet<String>();
    for (T row : rows) {
      String supplierCode = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(code.apply(row));
      String supplierName = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(name.apply(row));
      if (supplierCode == null && supplierName == null) return false;
      identities.add(com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.supplierIdentity(supplierCode, supplierName));
      if (identities.size() > 1) return false;
    }
    return identities.size() == 1;
  }

  public static SupplierPriceCandidate choose(
      List<SupplierPriceCandidate> candidates, List<SupplierSupplyRatio> ratios) {
    candidates = eligible(candidates, ratios);
    if (candidates.isEmpty()) return null;
    if (candidates.size() == 1) return candidates.get(0);
    Map<String, SupplierSupplyRatio> bySupplier = index(ratios);
    List<SupplierPriceCandidate> known = candidates.stream()
        .filter(c -> match(bySupplier, c) != null
            && match(bySupplier, c).getSupplyRatio() != null).toList();
    if (known.isEmpty()) return null;
    Comparator<SupplierPriceCandidate> preference = Comparator
        .comparing((SupplierPriceCandidate c) -> match(bySupplier, c).getSupplyRatio())
        .thenComparing(SupplierPriceCandidate::unitPrice)
        .thenComparing(SupplierPriceCandidate::supplierCode);
    SupplierPriceCandidate best = known.stream().max(preference).orElseThrow();
    if (known.size() == candidates.size()) return best;

    // 空值仍是空值。按物料总比例100%的业务前提，用未分配比例的上界判断
    // 空值是否可能改变最大供应商；不补造0，也不使用没有有效价格的供应商报价。
    BigDecimal total = ratios.stream().map(SupplierSupplyRatio::getSupplyRatio)
        .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(BigDecimal.ONE) > 0) return null;
    BigDecimal remaining = BigDecimal.ONE.subtract(total);
    return match(bySupplier, best).getSupplyRatio().compareTo(remaining) > 0
        ? best : null;
  }

  /** Apply withdrawal before every shortcut or manual fallback; null/missing is not zero. */
  public static List<SupplierPriceCandidate> eligible(
      List<SupplierPriceCandidate> candidates, List<SupplierSupplyRatio> ratios) {
    index(ratios); // Reject ambiguous current versions even in single/same-price branches.
    return candidates.stream().filter(c -> !withdrawn(c.supplierCode(), c.supplierName(), ratios)).toList();
  }

  public static boolean withdrawn(String code, String name, List<SupplierSupplyRatio> ratios) {
    String normalizedCode = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(code);
    String normalizedName = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(name);
    return ratios.stream().filter(r -> r.getSupplyRatio() != null && r.getSupplyRatio().signum() == 0)
        .anyMatch(r -> {
          String rowCode = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(r.getSupplierCode());
          if (normalizedCode != null && rowCode != null) return normalizedCode.equals(rowCode);
          return normalizedName != null && normalizedName.equals(
              com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(r.getSupplierName()));
        });
  }

  public static SupplierPriceCandidate highest(List<SupplierPriceCandidate> candidates) {
    return candidates.stream().max(Comparator.comparing(SupplierPriceCandidate::unitPrice)
        .thenComparing(SupplierPriceCandidate::supplierCode)).orElse(null);
  }

  public static Map<String, SupplierSupplyRatio> index(List<SupplierSupplyRatio> ratios) {
    return ratios.stream().collect(Collectors.toMap(
        r -> com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.supplierIdentity(r.getSupplierCode(), r.getSupplierName()),
        Function.identity(), (a, b) -> {
          throw new IllegalStateException("同一物料和供应商存在多条有效供货比率：" + a.getSupplierName());
        }));
  }

  public static SupplierSupplyRatio match(Map<String, SupplierSupplyRatio> ratios, SupplierPriceCandidate candidate) {
    String code = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(candidate.supplierCode());
    SupplierSupplyRatio exact = code == null ? null : ratios.get("CODE:" + code);
    if (exact != null) return exact;
    String name = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(candidate.supplierName());
    if (name == null) return null;
    // Explicit, conflicting codes must never be replaced by a name match.
    if (code != null) return ratios.get("NAME:" + name);
    int highest=0;
    var matches=new java.util.TreeMap<String,SupplierSupplyRatio>();
    for (var row : ratios.values()) {
      int score=nameScore(name,row.getSupplierName());
      if(score>highest){highest=score;matches.clear();}
      if(score>0 && score==highest)matches.put(
          com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.supplierIdentity(row.getSupplierCode(),row.getSupplierName()),row);
    }
    if(matches.size()>1)throw new IllegalArgumentException("供应商名称无法唯一匹配，请补齐供应商编码："+name+"，候选="+String.join("、",matches.keySet()));
    return matches.isEmpty()?null:matches.firstEntry().getValue();
  }

  private static int nameScore(String requested,String actual) {
    String left=normalizedName(requested),right=normalizedName(actual);
    if(left.isEmpty() || right.isEmpty())return 0;
    if(left.equals(right))return 10000;
    left=companyCore(left);right=companyCore(right);
    if(left.isEmpty() || right.isEmpty())return 0;
    if(left.equals(right))return 9000;
    int shorter=Math.min(left.length(),right.length()),longer=Math.max(left.length(),right.length());
    return shorter>=2 && (left.contains(right) || right.contains(left))?1000+shorter*1000/longer:0;
  }
  private static String normalizedName(String name) {
    return name==null?"":java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFKC)
        .toUpperCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{P}\\u3000]+","");
  }
  private static String companyCore(String name) {
    for(String suffix:List.of("股份有限公司","有限责任公司","有限公司","公司"))
      if(name.endsWith(suffix))return name.substring(0,name.length()-suffix.length());
    return name;
  }
}
