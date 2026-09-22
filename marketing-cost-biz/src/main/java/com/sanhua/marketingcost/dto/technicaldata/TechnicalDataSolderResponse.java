package com.sanhua.marketingcost.dto.technicaldata;

import com.sanhua.marketingcost.service.technicaldata.TechnicalDataMaterialPriceQuery.PriceRequirement;
import java.util.List;

public record TechnicalDataSolderResponse(Long taskId, Long productId, Integer expectedVersion,
    Long draftVersionId, String versionStatus, String moduleStatus, boolean editable, boolean historical,
    TechnicalDataSupplementContent.Solder content, List<String> issues, List<PriceRequirement> priceRequirements) {}
