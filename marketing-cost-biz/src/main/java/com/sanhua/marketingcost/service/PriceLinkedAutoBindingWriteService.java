package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteRequest;
import com.sanhua.marketingcost.dto.PriceLinkedAutoBindingWriteResult;

public interface PriceLinkedAutoBindingWriteService {
  PriceLinkedAutoBindingWriteResult write(PriceLinkedAutoBindingWriteRequest request);
}
