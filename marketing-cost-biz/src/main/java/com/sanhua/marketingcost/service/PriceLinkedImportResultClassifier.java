package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportResultClassifyRequest;

public interface PriceLinkedImportResultClassifier {
  void append(PriceItemImportResponse response, PriceLinkedImportResultClassifyRequest request);
}
