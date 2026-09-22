package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

/** 浏览器复制参数时保留十进制精度；审批快照仍使用原始数值 DTO。 */
public record TechnicalDataPriceReferenceView(Long id, String materialNo, String name, String model,
    String unit, String organizationCode, String businessUnit, String month,
    String formula, String formulaText, Parameters parameters, Integer taxIncluded,
    String supplierName, String inputSnapshotJson, String fingerprint,
    List<TechnicalDataPriceReference.Binding> bindings) {

  public static TechnicalDataPriceReferenceView from(TechnicalDataPriceReference source) {
    var value = source.parameters();
    var parameters = value == null ? null : new Parameters(decimal(value.blankWeight()),
        decimal(value.netWeight()), value.weightUnit(), decimal(value.processFee()),
        decimal(value.agentFee()), value.feeUnit());
    return new TechnicalDataPriceReferenceView(source.id(), source.materialNo(), source.name(),
        source.model(), source.unit(), source.organizationCode(), source.businessUnit(), source.month(),
        source.formula(), source.formulaText(), parameters, source.taxIncluded(), source.supplierName(),
        source.inputSnapshotJson(), source.fingerprint(), source.bindings());
  }

  private static String decimal(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }

  public record Parameters(String blankWeight, String netWeight, String weightUnit,
      String processFee, String agentFee, String feeUnit) {}
}
