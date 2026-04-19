package com.sanhua.marketingcost.dto;

public class CostRunTrialResponse {
  private int productCount;
  private int partCount;
  private int costItemCount;

  public CostRunTrialResponse() {
  }

  public CostRunTrialResponse(int productCount, int partCount, int costItemCount) {
    this.productCount = productCount;
    this.partCount = partCount;
    this.costItemCount = costItemCount;
  }

  public int getProductCount() {
    return productCount;
  }

  public void setProductCount(int productCount) {
    this.productCount = productCount;
  }

  public int getPartCount() {
    return partCount;
  }

  public void setPartCount(int partCount) {
    this.partCount = partCount;
  }

  public int getCostItemCount() {
    return costItemCount;
  }

  public void setCostItemCount(int costItemCount) {
    this.costItemCount = costItemCount;
  }
}
