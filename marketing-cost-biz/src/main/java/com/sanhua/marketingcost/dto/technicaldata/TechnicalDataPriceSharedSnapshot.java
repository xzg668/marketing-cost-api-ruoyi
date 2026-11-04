package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

/** 本产品只引用原补价资料；不复制价格行，也不产生第二个补价来源。 */
public record TechnicalDataPriceSharedSnapshot(TechnicalDataPriceRequirementsResponse requirements,
    List<TechnicalDataPriceOwner> owners) {}
