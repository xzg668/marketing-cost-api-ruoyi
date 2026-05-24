package com.sanhua.marketingcost.dto.priceprepare;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 包装组件价格准备结果，负责把包装价格服务结果映射为价格准备明细和缺口。 */
@Getter
@Setter
public class PackageComponentPricePrepareResult {
  private String status;
  private BigDecimal unitPrice;
  private BigDecimal amount;
  private String priceSource;
  private String resultRefType;
  private Long resultRefId;
  private String message;
  private List<Gap> gaps = new ArrayList<>();

  public static PackageComponentPricePrepareResult ready(
      BigDecimal unitPrice,
      BigDecimal amount,
      Long priceId,
      String message) {
    PackageComponentPricePrepareResult result = new PackageComponentPricePrepareResult();
    result.setStatus("READY");
    result.setUnitPrice(unitPrice);
    result.setAmount(amount);
    result.setPriceSource("包装组件价格");
    result.setResultRefType("PACKAGE_PRICE");
    result.setResultRefId(priceId);
    result.setMessage(message);
    return result;
  }

  public static PackageComponentPricePrepareResult notReady(String status, String message, List<Gap> gaps) {
    PackageComponentPricePrepareResult result = new PackageComponentPricePrepareResult();
    result.setStatus(status);
    result.setPriceSource("包装组件价格");
    result.setResultRefType("PACKAGE_PRICE");
    result.setMessage(message);
    result.setGaps(gaps == null ? new ArrayList<>() : new ArrayList<>(gaps));
    return result;
  }

  @Getter
  @Setter
  public static class Gap {
    private String gapType;
    private String gapMaterialCode;
    private String sourceTable;
    private String message;

    public Gap() {
    }

    public Gap(String gapType, String gapMaterialCode, String sourceTable, String message) {
      this.gapType = gapType;
      this.gapMaterialCode = gapMaterialCode;
      this.sourceTable = sourceTable;
      this.message = message;
    }
  }
}
