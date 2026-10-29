package com.sanhua.marketingcost.service.supplier;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;

/** All ratio imports and history queries use the explicitly selected login business unit. */
public final class SupplierSupplyRatioScope {
  private SupplierSupplyRatioScope() {}
  public static String require(String requested) {
    String current = BusinessUnitContext.getCurrentBusinessUnitType();
    if (current == null || !Set.of("COMMERCIAL", "HOUSEHOLD").contains(current))
      throw new AccessDeniedException("请先选择当前业务单元");
    if (requested != null && !requested.isBlank() && !current.equals(requested.trim()))
      throw new AccessDeniedException("不能访问其他业务单元的供货关系");
    return current;
  }
}
