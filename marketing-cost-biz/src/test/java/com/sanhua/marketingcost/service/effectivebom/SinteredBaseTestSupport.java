package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.node;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomTestSupport.shape;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.materialshape.SupplierRatioResolution;
import java.math.BigDecimal;
import java.util.List;

final class SinteredBaseTestSupport {

  static final String BASE = "201850113";
  static final String GOLD = "311034930";
  static final String BLANK = "201850157";

  private SinteredBaseTestSupport() {}

  static String actionJson(String excludedDirectChildCode) {
    return "{\"internalTargetShape\":\"MANUFACTURE\","
        + "\"externalTargetShape\":\"OUTSOURCE\","
        + "\"excludedDirectChildMaterialCodes\":[\""
        + excludedDirectChildCode
        + "\"]}";
  }

  static EffectiveBomShapeDecision supplierDecision(
      String materialCode,
      QuoteMaterialShape shape,
      String supplierCode,
      boolean internal,
      String actionJson) {
    SupplierRatioResolution resolution =
        new SupplierRatioResolution(
            "COMMERCIAL",
            "210",
            materialCode,
            "2026-08",
            shape,
            701L,
            "supplier-policy-fingerprint",
            801L,
            supplierCode,
            "供应商-" + supplierCode,
            new BigDecimal("0.60"),
            internal,
            "{\"internalSupplierCodes\":[\"SUP-210\",\"SUP-220\"]}",
            actionJson,
            null);
    return EffectiveBomShapeDecision.from(resolution, "制造件");
  }

  static List<BomRawHierarchy> standardTree() {
    return List.of(
        node(1, "P", "P", 0, "/P/", "1", "制造件"),
        node(2, BASE, "P", 1, "/P/" + BASE + "/", "1", "制造件"),
        node(3, BLANK, BASE, 2, "/P/" + BASE + "/" + BLANK + "/", "1", "采购件"),
        node(4, GOLD, BASE, 2, "/P/" + BASE + "/" + GOLD + "/", "0.0036", "采购件"),
        node(5, "GOLD-CHILD", GOLD, 3, "/P/" + BASE + "/" + GOLD + "/GOLD-CHILD/", "2", "采购件"));
  }

  static EffectiveBomShapeDecision[] decisions(
      EffectiveBomShapeDecision baseDecision) {
    return new EffectiveBomShapeDecision[] {
      shape("P", QuoteMaterialShape.MANUFACTURE),
      baseDecision,
      shape(BLANK, QuoteMaterialShape.PURCHASE),
      shape(GOLD, QuoteMaterialShape.PURCHASE),
      shape("GOLD-CHILD", QuoteMaterialShape.PURCHASE)
    };
  }
}
