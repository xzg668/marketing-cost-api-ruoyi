package com.sanhua.marketingcost.dto.jjb;

import java.util.List;
import java.util.Map;
import lombok.Builder;

/** Saved-version presentation contract. Decimal strings never pass through JavaScript doubles. */
public record JjbReadModel(Identity identity, Header header, List<Row> rows,
    List<MonthlyPrice> monthlyPrices, Totals totals, List<String> warnings) {
  public JjbReadModel {
    rows = List.copyOf(rows); monthlyPrices = List.copyOf(monthlyPrices); warnings = List.copyOf(warnings);
  }
  public record Identity(Long versionId, String costRunNo, String versionNo, String oaNo,
      Long oaFormItemId, String productCode, String businessUnitType, String pricingMonth,
      String algorithmVersion, String feePolicyVersion, String status, boolean monthly) {}
  public record Header(String productName, String productModel, String customerName,
      String customerCode, String customerDrawing, String descriptionBasis,
      String financeCuPrice, String customerCuPrice, String copperUnit,
      String productProperty, String coefficient, Map<String, String> quotation) {
    public Header {
      quotation = quotation == null ? Map.of() : Map.copyOf(quotation);
    }
    public Header(String productName, String productModel, String customerName,
        String customerCode, String customerDrawing, String descriptionBasis,
        String financeCuPrice, String customerCuPrice, String copperUnit,
        String productProperty, String coefficient) {
      this(productName, productModel, customerName, customerCode, customerDrawing, descriptionBasis,
          financeCuPrice, customerCuPrice, copperUnit, productProperty, coefficient, Map.of());
    }
  }
  public record Totals(String totalCost, String cuMaterialAdjustment, String finalQuoteAmount) {}
  public record Source(String table, Long id, String description) {}
  /** One row's identity/evidence survives layout changes and is shared with the trace drawer. */
  @Builder(toBuilder = true)
  public record Row(String key, String section, String kind, String code, String name,
      String drawingNo, String unitPrice, String quantity, String amount, String grossWeight,
      String netWeight, String rawUnitPrice, String scrapUnitPrice, String material,
      String shape, String priceSource, String remark, String rate, String baseAmount,
      String feeState, List<Source> sources, List<Long> partItemIds, List<Long> costItemIds,
      List<Long> monthlyPriceIds, List<String> paths, String householdRate,
      String salesRate, String coefficient) {
    public Row {
      sources = sources == null ? List.of() : List.copyOf(sources);
      partItemIds = partItemIds == null ? List.of() : List.copyOf(partItemIds);
      costItemIds = costItemIds == null ? List.of() : List.copyOf(costItemIds);
      monthlyPriceIds = monthlyPriceIds == null ? List.of() : List.copyOf(monthlyPriceIds);
      paths = paths == null ? List.of() : List.copyOf(paths);
    }
  }
  /** Deliberately excludes inquiry financial-reference columns and source-file download access. */
  public record MonthlyPrice(long id, String materialCode, String pricingType, String unit,
      String currency, String taxBasis, String price, String householdRate, String evidenceHash,
      List<String> applicableOrganizations, List<String> consumerOrganizations,
      String applicability, String sourceType, String sourceId, String sourceVersion,
      String sourceBatch, String supplier) {}
}
