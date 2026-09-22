package com.sanhua.marketingcost.dto.technicaldata;

import com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingSourceQuery.Assessment;
import java.math.BigDecimal;
import java.util.List;

public record TechnicalDataManufacturingResponse(Long productId, int expectedVersion, Long versionId,
    boolean editable, boolean historical, Assessment source,
    TechnicalDataSupplementContent.Manufacturing saved, List<String> issues,
    List<CalculationInput> calculationInputs,
    boolean bomComposed, String bomMessage,
    List<com.sanhua.marketingcost.service.technicaldata.TechnicalDataMaterialPriceQuery.PriceRequirement> priceRequirements) {
  public record CalculationInput(String parentSourceNodeId, String rawMaterialNo,
      BigDecimal grossWeightG, BigDecimal netWeightG, BigDecimal netLengthMm,
      BigDecimal purchasingQuantity, String purchasingUnit) {}
}
