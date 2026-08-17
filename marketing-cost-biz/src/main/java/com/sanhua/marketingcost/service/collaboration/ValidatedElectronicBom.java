package com.sanhua.marketingcost.service.collaboration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ValidatedElectronicBom(
    String sourceSystem,
    String productCode,
    String materialOrganizationCode,
    String bomPurpose,
    String sourceVersion,
    String versionStatus,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    OffsetDateTime queriedAt,
    List<Node> nodes) {

  public ValidatedElectronicBom {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
  }

  public record Node(
      String nodeKey,
      String parentNodeKey,
      int level,
      String parentMaterialCode,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String materialNature,
      BigDecimal quantityPerParent,
      BigDecimal quantityToTop,
      String unit,
      int sortSeq,
      String path) {}
}
