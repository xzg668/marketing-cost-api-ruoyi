package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

public record TechnicalDataNetLossResponse(Long taskId, Long productId, Integer expectedVersion,
    Long draftVersionId, String versionStatus, String moduleStatus, boolean editable, boolean historical,
    TechnicalDataSupplementContent.NetLoss content, List<String> issues,
    com.sanhua.marketingcost.service.NetLossRateQuery.Source publicSource,
    com.sanhua.marketingcost.service.NetLossRateQuery.ApplicableRate applicableRate) {}
