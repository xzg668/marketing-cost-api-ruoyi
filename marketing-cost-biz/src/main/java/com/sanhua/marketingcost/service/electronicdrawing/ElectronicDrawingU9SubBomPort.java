package com.sanhua.marketingcost.service.electronicdrawing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 电子图库混合树查询制造节点当前有效 U9 子 BOM 的稳定端口。 */
public interface ElectronicDrawingU9SubBomPort {

  SubBomResult query(SubBomQuery query);

  enum Status {
    AVAILABLE,
    NOT_FOUND,
    MULTIPLE,
    ORGANIZATION_MISMATCH,
    TIMEOUT,
    ERROR
  }

  record SubBomQuery(
      String oaNo,
      Long oaFormItemId,
      String parentMaterialCode,
      String periodMonth,
      String priceOrgCode,
      String materialOrganizationCode,
      String businessUnitType,
      String bomPurpose,
      LocalDate effectiveDate) {}

  /**
   * U9 根节点本身不重复返回；parentNodeKey=null 表示当前电子图库制造节点的直接 U9 子件。
   */
  record SubBomResult(
      Status status,
      String parentMaterialCode,
      String priceOrgCode,
      String materialOrganizationCode,
      List<U9Node> nodes,
      String message) {

    public SubBomResult {
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public static SubBomResult available(
        String parentMaterialCode,
        String priceOrgCode,
        String materialOrganizationCode,
        List<U9Node> nodes) {
      return new SubBomResult(
          Status.AVAILABLE, parentMaterialCode, priceOrgCode,
          materialOrganizationCode, nodes, null);
    }

    public static SubBomResult failure(Status status, String parentMaterialCode, String message) {
      if (status == Status.AVAILABLE) {
        throw new IllegalArgumentException("失败结果不能使用 AVAILABLE 状态");
      }
      return new SubBomResult(status, parentMaterialCode, null, null, List.of(), message);
    }
  }

  record U9Node(
      String nodeKey,
      String parentNodeKey,
      Long sourceRawHierarchyId,
      Long sourceU9BomId,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String shapeAttr,
      String mainCategoryCode,
      String sourceCategory,
      String costElementCode,
      String bomPurpose,
      String bomVersion,
      BigDecimal qtyPerParent,
      BigDecimal parentBaseQty,
      String unit,
      Integer sortSeq) {}
}
