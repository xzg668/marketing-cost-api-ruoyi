package com.sanhua.marketingcost.service.materialshape;

import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;

final class MaterialQuoteShapeTestSupport {

  private MaterialQuoteShapeTestSupport() {}

  static MaterialQuoteShapePolicy fixed(
      long id,
      String org,
      String materialCode,
      String targetShape,
      String fromMonth,
      String toMonth) {
    MaterialQuoteShapePolicy policy = new MaterialQuoteShapePolicy();
    policy.setId(id);
    policy.setMaterialOrgCode(org);
    policy.setMaterialCode(materialCode);
    policy.setPolicyMode(MaterialQuoteShapePolicy.MODE_FIXED);
    policy.setFixedTargetShape(targetShape);
    policy.setEffectiveFromMonth(fromMonth);
    policy.setEffectiveToMonth(toMonth);
    policy.setEnabled(MaterialQuoteShapePolicy.ENABLED);
    return policy;
  }

  static MaterialQuoteShapePolicy supplierRatio(
      long id, String org, String materialCode, String fromMonth) {
    MaterialQuoteShapePolicy policy =
        fixed(id, org, materialCode, null, fromMonth, null);
    policy.setPolicyMode(MaterialQuoteShapePolicy.MODE_SUPPLIER_RATIO);
    policy.setConditionConfigJson(
        "{\"internalSupplierCodes\":[\"SUP-210\",\"SUP-220\"]}");
    policy.setActionConfigJson(
        "{\"internalTargetShape\":\"MANUFACTURE\","
            + "\"externalTargetShape\":\"OUTSOURCE\","
            + "\"excludedDirectChildMaterialCodes\":[\"311034930\"]}");
    return policy;
  }
}
