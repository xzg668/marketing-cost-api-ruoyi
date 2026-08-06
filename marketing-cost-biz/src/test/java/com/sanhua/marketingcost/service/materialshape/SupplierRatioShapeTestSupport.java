package com.sanhua.marketingcost.service.materialshape;

import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.math.BigDecimal;
import java.time.LocalDate;

final class SupplierRatioShapeTestSupport {

  private SupplierRatioShapeTestSupport() {}

  static MaterialQuoteShapeResolution policyResolution(
      String org, String materialCode, String month) {
    return new MaterialQuoteShapeResolution(
        org,
        materialCode,
        month,
        "制造件",
        QuoteMaterialShape.MANUFACTURE,
        null,
        MaterialQuoteShapeSource.SUPPLIER_RATIO,
        81L,
        "policy-fingerprint",
        "{\"internalSupplierCodes\":[\"SUP-210\",\"SUP-220\"]}",
        "{\"internalTargetShape\":\"MANUFACTURE\","
            + "\"externalTargetShape\":\"OUTSOURCE\","
            + "\"excludedDirectChildMaterialCodes\":[\"311034930\"]}",
        "命中供货比例形态规则，等待主供应商解析");
  }

  static SupplierSupplyRatio row(
      long id,
      String org,
      String materialCode,
      String supplierCode,
      String supplierName,
      String ratio) {
    SupplierSupplyRatio row = new SupplierSupplyRatio();
    row.setId(id);
    row.setBusinessUnitType(org);
    row.setMaterialCode(materialCode);
    row.setSupplierCode(supplierCode);
    row.setSupplierName(supplierName);
    row.setMaterialShape(
        "SUP-210".equalsIgnoreCase(supplierCode)
                || "SUP-220".equalsIgnoreCase(supplierCode)
            ? "制造件"
            : "采购件");
    row.setSupplyRatio(ratio == null ? null : new BigDecimal(ratio));
    row.setDeleted(0);
    return row;
  }

  static SupplierSupplyRatio shape(SupplierSupplyRatio row, String materialShape) {
    row.setMaterialShape(materialShape);
    return row;
  }

  static SupplierSupplyRatio dates(
      SupplierSupplyRatio row, LocalDate from, LocalDate to) {
    row.setEffectiveFrom(from);
    row.setEffectiveTo(to);
    return row;
  }
}
