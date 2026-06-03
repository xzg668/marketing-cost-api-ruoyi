package com.sanhua.marketingcost.dto.packagecomponent;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PackageComponentBulkGenerateResponse {
  private int totalCount;
  private int successCount;
  private int failedCount;
  private List<PackageComponentBulkGenerateResult> records = new ArrayList<>();
}
