package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackagePriceResult {
  private PackageComponentPrice price;
  private List<PackageComponentPriceDetail> details = new ArrayList<>();
  private PackageSnapshotResult snapshotResult;
  private String status;
  private boolean complete;
  private List<String> warnings = new ArrayList<>();

  public static PackagePriceResult of(
      PackageComponentPrice price,
      List<PackageComponentPriceDetail> details,
      PackageSnapshotResult snapshotResult) {
    PackagePriceResult result = new PackagePriceResult();
    result.setPrice(price);
    result.setDetails(details == null ? new ArrayList<>() : new ArrayList<>(details));
    result.setSnapshotResult(snapshotResult);
    result.setStatus(price == null ? null : price.getPriceStatus());
    result.setComplete(price != null && Boolean.TRUE.equals(price.getPriceComplete()));
    return result;
  }
}
