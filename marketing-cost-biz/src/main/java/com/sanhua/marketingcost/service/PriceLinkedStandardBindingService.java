package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.StandardBindingCheckRequest;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import java.util.List;

public interface PriceLinkedStandardBindingService {
  List<StandardBindingDecision> checkAndRecord(StandardBindingCheckRequest request);
}
