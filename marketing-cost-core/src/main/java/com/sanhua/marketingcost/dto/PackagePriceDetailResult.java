package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackagePriceDetailResult {
  private PackageComponentPrice price;
  private List<PackageComponentPriceDetail> details = new ArrayList<>();
}
