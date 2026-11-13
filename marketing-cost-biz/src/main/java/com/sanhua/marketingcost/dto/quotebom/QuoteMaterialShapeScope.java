package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

/** Frozen selected BOM branches, before a purchased parent cuts off its children. */
public record QuoteMaterialShapeScope(String periodMonth, String productCode, String fingerprint,
    List<Material> materials) {
  public record Material(String organization, String materialCode, String materialName,
      String materialSpec, String sourceShape) {}
}
