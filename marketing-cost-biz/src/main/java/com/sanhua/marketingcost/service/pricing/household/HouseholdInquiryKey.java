package com.sanhua.marketingcost.service.pricing.household;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;

/** Source identity is either an explicit material code or an exact model/specification. */
public final class HouseholdInquiryKey {
  private HouseholdInquiryKey() {}
  public static String of(String material,String specification) {
    if(material!=null) {
      if(material.isBlank() || material.length()>64 || !material.equals(material.strip()))
        throw new IllegalArgumentException("料号格式无效");
      return "C:"+material;
    }
    if(specification==null || specification.isBlank() || specification.length()>512 || !specification.equals(specification.strip()))
      throw new IllegalArgumentException("没有料号时必须提供型号或规格");
    return "S:"+MonthlyPriceDocumentCodec.sha256(specification);
  }
}
