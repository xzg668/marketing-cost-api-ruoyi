package com.sanhua.marketingcost.service.ingest;

public interface BomAvailabilityAdapter {
  BomAvailability findAvailableBom(String oaNo, String productCode, String periodMonth);

  default BomAvailability findAvailableBom(String oaNo, String productCode) {
    return findAvailableBom(oaNo, productCode, null);
  }
}
