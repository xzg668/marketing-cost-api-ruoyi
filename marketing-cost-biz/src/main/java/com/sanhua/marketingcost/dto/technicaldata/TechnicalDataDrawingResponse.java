package com.sanhua.marketingcost.dto.technicaldata;

import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingProductLookup.Option;
import java.util.List;

public record TechnicalDataDrawingResponse(Long productId, int expectedVersion, Long versionId,
    String accountingMonth, boolean editable, boolean acquired, String message,
    boolean materialsMatched, boolean bomComposed, boolean bomPublished, boolean sourceChanged,
    TechnicalDataSupplementContent.DrawingBom drawing, List<Option> drawingOptions,
    List<Resolution> resolutions, Long workflowId, boolean canResolveMaterials) {
  public record Resolution(String sourceNodeId, String materialNo, String matchStatus) {}
}
