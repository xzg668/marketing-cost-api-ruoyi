package com.sanhua.marketingcost.dto.quotebom;

import java.util.List;

public record QuoteProductBomTaskCreateRequest(List<Long> oaFormItemIds, Integer tokenExpireHours) {}
