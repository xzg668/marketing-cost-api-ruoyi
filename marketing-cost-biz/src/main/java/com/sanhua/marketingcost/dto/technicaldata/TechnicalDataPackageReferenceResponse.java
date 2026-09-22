package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

public record TechnicalDataPackageReferenceResponse(List<Component> components) {
  public record Component(String key, String materialNo, String name, String model,
      String specification, int childCount, List<Source> sources) {}

  public record Source(Evidence evidence, List<Child> children) {}

  public record Evidence(Long parentNodeId, String topProductCode, String topProductName,
      String topProductModel, String topProductSpecification, String parentMaterialNo, String parentName, String parentModel,
      String parentSpecification, BigDecimal parentQuantity, String priceOrgCode,
      String materialOrganizationCode, String bomVersion, String bomPurpose, String buildBatchId,
      String parentPath, String structureFingerprint, String fingerprint) {}

  public record Child(Long sourceNodeId, String materialNo, String name, String model,
      String specification, BigDecimal quantity, String unit, String path) {}

  public record ChildOption(Evidence source, Child child) {}
}
