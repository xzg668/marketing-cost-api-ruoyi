package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

/** 焊料来源快照；本次用量另存，审批历史不回查当前料品档案。 */
public final class TechnicalDataSolderSource {
  private TechnicalDataSolderSource() {}

  public record Material(Long id, String materialNo, String name, String drawingNo,
      String organizationCode, String mainCategoryCode, String unit, String importBatchId,
      String fingerprint) {}

  public record MaterialLookup(String status, String message, Material material) {}

  public record ReferenceEvidence(String materialNo, String name, String model, String priceOrgCode,
      String materialOrganizationCode, String accountingMonth, String branchPolicy, String fingerprint) {}

  public record BomEvidence(Long nodeId, String sourceLineKey, String parentCode, String path, Integer level,
      String priceOrgCode, String bomVersion, String bomPurpose, String importBatchId, String buildBatchId,
      BigDecimal quantityPerParent, BigDecimal quantityPerTop, String quantityUnit, String unitSource) {}

  public record ItemEvidence(Material material, BomEvidence bom, BigDecimal sourceQuantityKg) {}

  public record Reference(ReferenceEvidence evidence,
      List<TechnicalDataSupplementContent.SolderItem> items) {
    public Reference { items = List.copyOf(items); }
  }
}
