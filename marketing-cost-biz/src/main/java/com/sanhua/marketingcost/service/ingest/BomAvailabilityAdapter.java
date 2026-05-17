package com.sanhua.marketingcost.service.ingest;

public interface BomAvailabilityAdapter {
  BomAvailability findAvailableBom(String oaNo, String productCode);
}
