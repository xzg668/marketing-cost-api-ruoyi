package com.sanhua.marketingcost.dto.technicaldata;

import com.sanhua.marketingcost.service.NetLossRateQuery;

public record TechnicalDataNetLossReference(NetLossRateQuery.Source source, String fingerprint) {}
