package com.sanhua.marketingcost.dto.collaboration;

import java.math.BigDecimal;
import java.util.List;

public record TechnicalPackageDraftRequest(
    Integer expectedTaskVersion,
    List<Line> lines) {

  public TechnicalPackageDraftRequest {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }

  public record Line(
      Long draftLineId,
      String packageParentCode,
      String packageParentName,
      String packageParentSpec,
      String packageParentModel,
      String packageParentUnit,
      BigDecimal packageQtyPerTop,
      String packageMaterialCode,
      String packageMaterialName,
      String packageMaterialSpec,
      String packageMaterialModel,
      String packageMaterialUnit,
      BigDecimal childQtyPerParent,
      String remark) {}
}
