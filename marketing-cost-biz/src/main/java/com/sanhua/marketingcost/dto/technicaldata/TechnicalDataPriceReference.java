package com.sanhua.marketingcost.dto.technicaldata;

/** 只复制参考公式及其参数；供应商属于参考证据，不成为补录价格的供应商。 */
public record TechnicalDataPriceReference(Long id, String materialNo, String name, String model,
    String unit, String organizationCode, String businessUnit, String month,
    String formula, String formulaText, TechnicalDataSupplementContent.PriceParameters parameters,
    Integer taxIncluded, String supplierName, String inputSnapshotJson, String fingerprint,
    java.util.List<Binding> bindings) {
  public TechnicalDataPriceReference { bindings = bindings == null ? java.util.List.of() : java.util.List.copyOf(bindings); }
  public record Binding(String tokenName, String factorCode, String priceSource,
      Long factorIdentityId, Integer buScoped) {}
}
