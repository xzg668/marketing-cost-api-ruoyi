package com.sanhua.marketingcost.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CostRunContext {

  public static final String SCENE_QUOTE = "QUOTE";
  public static final String SCENE_MONTHLY_REPRICE = "MONTHLY_REPRICE";
  public static final String BOM_SOURCE_POLICY_HISTORICAL_OA_BOM = "HISTORICAL_OA_BOM";

  private String scene;
  private String pricingMonth;
  private LocalDateTime priceAsOfTime;
  private String bomSourcePolicy;
  private Long adjustBatchId;
  private String repriceNo;
  private String businessUnitType;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String packageMethod;
  private String customerName;
  private String calcObjectKey;
  private Long costRunVersionId;
  private String costRunNo;
  private String pricePrepareNo;
  private String priceTypeConfirmNo;
  private String bomConfirmNo;
  /**
   * 核算引擎内部进度回调。
   *
   * <p>这里不进库、不参与接口序列化，只是把单个核算对象的 0-100 子进度交给外层任务映射。
   */
  @JsonIgnore
  private transient java.util.function.IntConsumer progress = p -> {};

  public static CostRunContext quote(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String packageMethod,
      String customerName,
      String businessUnitType,
      String pricingMonth,
      String calcObjectKey) {
    return quote(
        oaNo,
        oaFormItemId,
        productCode,
        packageMethod,
        customerName,
        businessUnitType,
        pricingMonth,
        null,
        calcObjectKey);
  }

  public static CostRunContext quote(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String packageMethod,
      String customerName,
      String businessUnitType,
      String pricingMonth,
      LocalDateTime priceAsOfTime,
      String calcObjectKey) {
    CostRunContext context = new CostRunContext();
    context.setScene(SCENE_QUOTE);
    context.setOaNo(oaNo);
    context.setOaFormItemId(oaFormItemId);
    context.setProductCode(productCode);
    context.setPackageMethod(packageMethod);
    context.setCustomerName(customerName);
    context.setBusinessUnitType(businessUnitType);
    context.setPricingMonth(pricingMonth);
    context.setPriceAsOfTime(priceAsOfTime);
    context.setCalcObjectKey(calcObjectKey);
    return context;
  }

  public static CostRunContext monthlyReprice(
      String pricingMonth,
      Long adjustBatchId,
      String repriceNo,
      String businessUnitType,
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String packageMethod,
      String customerName,
      String calcObjectKey) {
    return monthlyReprice(
        pricingMonth,
        adjustBatchId,
        repriceNo,
        businessUnitType,
        null,
        BOM_SOURCE_POLICY_HISTORICAL_OA_BOM,
        oaNo,
        oaFormItemId,
        productCode,
        packageMethod,
        customerName,
        calcObjectKey);
  }

  public static CostRunContext monthlyReprice(
      String pricingMonth,
      Long adjustBatchId,
      String repriceNo,
      String businessUnitType,
      LocalDateTime priceAsOfTime,
      String bomSourcePolicy,
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String packageMethod,
      String customerName,
      String calcObjectKey) {
    CostRunContext context = new CostRunContext();
    context.setScene(SCENE_MONTHLY_REPRICE);
    context.setPricingMonth(pricingMonth);
    context.setAdjustBatchId(adjustBatchId);
    context.setRepriceNo(repriceNo);
    context.setBusinessUnitType(businessUnitType);
    context.setPriceAsOfTime(priceAsOfTime);
    context.setBomSourcePolicy(bomSourcePolicy);
    context.setOaNo(oaNo);
    context.setOaFormItemId(oaFormItemId);
    context.setProductCode(productCode);
    context.setPackageMethod(packageMethod);
    context.setCustomerName(customerName);
    context.setCalcObjectKey(calcObjectKey);
    return context;
  }
}
