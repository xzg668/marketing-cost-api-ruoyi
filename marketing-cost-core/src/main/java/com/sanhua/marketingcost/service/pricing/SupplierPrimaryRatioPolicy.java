package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.time.*;
import java.util.*;

/** The existing maximum-ratio/update/id policy, applied to one captured input set. */
public final class SupplierPrimaryRatioPolicy {
  private SupplierPrimaryRatioPolicy() {}
  private static final LocalDateTime UNKNOWN_UPDATE=LocalDateTime.of(1970,1,1,0,0);
  private static final Comparator<SupplierSupplyRatio> ORDER=Comparator
      .comparing(SupplierSupplyRatio::getSupplyRatio,Comparator.reverseOrder())
      .thenComparing(row->row.getUpdatedAt()==null?UNKNOWN_UPDATE:row.getUpdatedAt(),Comparator.reverseOrder())
      .thenComparing(SupplierSupplyRatio::getId,Comparator.nullsLast(Comparator.reverseOrder()));
  /** Rows have already been scoped by SQL; each map key is its exact query identity. */
  public static Map<String,SupplierSupplyRatioResolveResult> resolve(String businessUnit,Set<String> materialCodes,
      LocalDate date,Map<String,? extends Collection<? extends SupplierSupplyRatio>> capturedByMaterial){
    var requested=new LinkedHashSet<String>();
    for(String code:materialCodes){String normalized=SupplierSupplyRatioNormalizeUtils.normalizeToNull(code);if(normalized!=null)requested.add(normalized);}
    var result=new LinkedHashMap<String,SupplierSupplyRatioResolveResult>();
    for(String code:requested){
      var captured=capturedByMaterial.get(code);SupplierSupplyRatio row=captured==null?null:captured.stream().filter(value->
          Integer.valueOf(1).equals(value.getIsActive()) && Integer.valueOf(0).equals(value.getDeleted()) && value.getSupplyRatio()!=null
          && (date==null || (value.getEffectiveFrom()==null || !value.getEffectiveFrom().isAfter(date)) && (value.getEffectiveTo()==null || !value.getEffectiveTo().isBefore(date))))
          .min(ORDER).orElse(null);
      result.put(code,row==null?SupplierSupplyRatioResolveResult.miss("未命中供货比例：material_code 无数据"):hit(row,"匹配 material_code，取供货比例最大供应商"));
    }
    return result;
  }
  public static SupplierSupplyRatioResolveResult hit(SupplierSupplyRatio row, String matchMode) {
    SupplierSupplyRatioResolveResult result = new SupplierSupplyRatioResolveResult();
    result.setMatched(true);
    result.setId(row.getId());
    result.setSupplierName(row.getSupplierName());
    result.setSupplierCode(row.getSupplierCode());
    result.setSupplyRatio(row.getSupplyRatio());
    result.setSourceType(row.getSourceType());
    result.setSourceBatchNo(row.getSourceBatchNo());
    result.setTraceMessage(
        "命中供货比例主供应商：supplierName="
            + row.getSupplierName()
            + ", supplyRatio="
            + row.getSupplyRatio()
            + ", matchMode="
            + matchMode);
    return result;
  }

}
