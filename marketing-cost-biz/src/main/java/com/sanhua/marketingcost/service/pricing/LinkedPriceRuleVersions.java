package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.time.LocalDateTime;
import java.util.*;

/** One deterministic version policy per complete linked-rule identity. */
public final class LinkedPriceRuleVersions {
  private static final Comparator<PriceLinkedItem> IMPORT_ORDER=Comparator
      .comparing(PriceLinkedItem::getCreatedAt,Comparator.nullsFirst(LocalDateTime::compareTo))
      .thenComparing(PriceLinkedItem::getId,Comparator.nullsFirst(Long::compareTo));
  private static final Comparator<PriceLinkedItem> REVISION_ORDER=Comparator
      .comparing(PriceLinkedItem::getRuleRevision).thenComparing(IMPORT_ORDER);

  private LinkedPriceRuleVersions() {}

  public static List<PriceLinkedItem> current(Collection<PriceLinkedItem> rows) {
    var byIdentity=new LinkedHashMap<List<String>,List<PriceLinkedItem>>();
    for(var row:rows) {
      var identity=row.getRuleIdentityKey()==null || row.getRuleIdentityKey().isBlank()
          ?List.of("LEGACY",supplierKey(row),text(row.getSpecModel()),text(row.getUnit()))
          :List.of("PUBLISHED",row.getRuleIdentityKey());
      byIdentity.computeIfAbsent(identity,ignored->new ArrayList<>()).add(row);
    }
    var heads=new ArrayList<PriceLinkedItem>();
    for(var versions:byIdentity.values()) {
      // Pairwise switching between revision and time creates cycles. Choose once for the whole identity.
      var order=versions.stream().allMatch(row->row.getRuleRevision()!=null)?REVISION_ORDER:IMPORT_ORDER;
      heads.add(versions.stream().max(order).orElseThrow());
    }
    // Revisions belong to an identity, so different suppliers' revision numbers are not comparable.
    return heads.stream().sorted(IMPORT_ORDER.reversed()).toList();
  }

  public static String supplierKey(PriceLinkedItem row) {
    String code=SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode());
    if(code!=null)return "CODE:"+code;
    String name=SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierName());
    return name==null?"NO_SUPPLIER":"NAME:"+name;
  }
  private static String text(String value){return value==null?"":value.trim();}
}
