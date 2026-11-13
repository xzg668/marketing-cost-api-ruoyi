package com.sanhua.marketingcost.service.pricing;

import java.util.List;
import java.time.LocalDateTime;

/** 来源汇总与人工属性的契约；不改变任何价格选择和核算规则。 */
public final class MaterialPriceSourceCatalog {
  private MaterialPriceSourceCatalog() {}

  public record Filter(String month, String materialCode, String organization,
      String materialAttribute, int page, int size) {}
  public record Price(String value, String unit, String currency, String taxBasis,
      String supplierCode, String supplierName, String condition, String status) {}
  public record Key(String materialCode, String organization, String sourceType) {}
  public record Entry(long id, String materialCode, String materialName, String materialModel,
      String materialSpec, String organization, String organizationName, String sourceType,
      String sourceLabel, List<Price> prices, String materialAttribute, boolean automatic, long version) {}
  public record RefreshReport(int months, int sourceRows) {}
  public record RefreshState(long generation, LocalDateTime refreshedAt, String refreshedBy,
      int materialCount, int sourceCount) {}
  public record Page(String month, long total, List<Entry> rows, RefreshState refresh) {}
  public record Change(String month, String organization, String materialCode, String sourceType,
      String materialAttribute, Long expectedVersion) {}
  public record Candidate(Key key, String materialName, String materialModel, String materialSpec,
      String organizationName, List<Price> prices, String autoAttribute) {}
  public record Reference(Key key, String table, long id) {}

  public static String sourceLabel(String type) {
    return switch (type) {
      case "LINKED" -> "联动价";
      case "RANGE" -> "区间价";
      case "FIXED" -> "固定价";
      case "TIER" -> "阶梯价";
      case "ORDER" -> "订单价";
      case "INVOICE" -> "发票价";
      case "INQUIRY" -> "询价";
      case "HOUSEHOLD_INQUIRY" -> "家用询价";
      case "SETTLE_FIXED" -> "结算固定价";
      case "SETTLE_LINKED" -> "结算联动价";
      case "SCRAP" -> "废料价";
      case "MAKE" -> "自制件算价";
      default -> throw new IllegalArgumentException("未知价格来源：" + type);
    };
  }
}
