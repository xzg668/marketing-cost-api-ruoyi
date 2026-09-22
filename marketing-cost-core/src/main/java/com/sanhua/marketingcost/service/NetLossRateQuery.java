package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

/** 核算与补录共用的公共取率：组织内有效料品 → 裸品 → 年度、业务单元配置。 */
@Service
public class NetLossRateQuery {
  public record Source(String status, String reasonCode, String message, Long materialId,
      String materialNo, String name, String model, String bareMaterialNo, String organizationCode,
      int year, String businessUnitType, Long configurationId, BigDecimal rate) {}

  /** 只接受调用方已核验审批版本的补录值；公共查询失败不能解释成缺少公共配置。 */
  public record ApplicableRate(String status, String sourceType, BigDecimal rate, String message) {}

  public static boolean validPublicRate(BigDecimal rate) {
    return rate != null && rate.signum() > 0 && rate.compareTo(BigDecimal.ONE) < 0;
  }

  public static ApplicableRate select(Source publicSource, BigDecimal approvedSupplementalRate) {
    if (publicSource == null) throw new IllegalArgumentException("缺少公共净损失率查询结果");
    if ("AVAILABLE".equals(publicSource.status())) {
      if (!validPublicRate(publicSource.rate())) return new ApplicableRate("ERROR", "PUBLIC", null,
          "公共净损失率必须大于 0% 且小于 100%，请核实配置");
      return new ApplicableRate("AVAILABLE", "PUBLIC", publicSource.rate(), "采用公共净损失率");
    }
    if (!"MISSING".equals(publicSource.status())) {
      return new ApplicableRate(publicSource.status(), "PUBLIC", null, publicSource.message());
    }
    if (approvedSupplementalRate == null) {
      return new ApplicableRate("MISSING", null, null, publicSource.message());
    }
    if (approvedSupplementalRate.signum() < 0 || approvedSupplementalRate.compareTo(BigDecimal.ONE) >= 0) {
      throw new IllegalArgumentException("已审批补录净损失率不在有效范围内");
    }
    return new ApplicableRate("AVAILABLE", "TECH_SUPPLEMENTAL", approvedSupplementalRate, "采用已审批补录净损失率");
  }

  private final MaterialMasterRawMapper materials;
  private final QualityLossRateMapper rates;

  public NetLossRateQuery(MaterialMasterRawMapper materials, QualityLossRateMapper rates) {
    this.materials = materials;
    this.rates = rates;
  }

  public Source lookup(String code, String model, String organization, int year, String businessUnit) {
    requireContext(organization, year, businessUnit);
    var matches = blank(code) ? List.<MaterialMasterRaw>of()
        : materials.selectByLatestBatchAndCodes(List.of(code.trim()), null, organization);
    if (matches.isEmpty() && !blank(model)) {
      matches = materials.selectNetLossProducts("MODEL", model.trim(), true, organization, 2);
    }
    if (matches.size() != 1) return missing(matches.isEmpty() ? "NET_LOSS_PRODUCT_MISSING" : "NET_LOSS_PRODUCT_AMBIGUOUS",
        matches.isEmpty() ? "未找到对应的有效成品料品档案" : "对应多个成品，请在补录中选择参考成品或直接填写",
        code, organization, year, businessUnit);
    return resolve(matches.getFirst(), organization, year, businessUnit);
  }

  public List<Source> search(String searchBy, String keyword, String organization, int year, String businessUnit) {
    requireContext(organization, year, businessUnit);
    if (!("CODE".equals(searchBy) || "MODEL".equals(searchBy)) || blank(keyword) || keyword.trim().length() > 100) {
      throw new IllegalArgumentException("请选择成品料号或型号，并填写 1—100 字的查询条件");
    }
    var matches = materials.selectNetLossProducts(searchBy, keyword.trim(), false, organization, 101);
    if (matches.size() > 100) throw new IllegalArgumentException("匹配的成品较多，请填写更具体的查询条件");
    if ("MODEL".equals(searchBy)) {
      var exact = matches.stream().filter(row -> keyword.trim().equalsIgnoreCase(row.getMaterialModel())).toList();
      if (!exact.isEmpty()) matches = exact;
    }
    return matches.stream().map(row -> resolve(row, organization, year, businessUnit)).toList();
  }

  private Source resolve(MaterialMasterRaw material, String organization, int year, String businessUnit) {
    String bare = !blank(material.getMainCategoryCode()) && material.getMainCategoryCode().trim().startsWith("11")
        ? material.getMaterialCode() : material.getBareCode();
    if (blank(bare)) return source(material, null, organization, year, businessUnit, null, "MISSING",
        "NET_LOSS_BARE_MISSING", "该成品未维护裸品料号");
    var rate = rates.selectOne(Wrappers.lambdaQuery(QualityLossRate.class)
        .eq(QualityLossRate::getRateYear, year).eq(QualityLossRate::getBareProductCode, bare.trim())
        .eq(QualityLossRate::getBusinessUnitType, businessUnit));
    if (rate == null) return source(material, bare.trim(), organization, year, businessUnit, null,
        "MISSING", "NET_LOSS_RATE_MISSING", "该裸品暂无本核算年度、业务单元的净损失率");
    if (!validPublicRate(rate.getLossRate())) {
      return source(material, bare.trim(), organization, year, businessUnit, rate,
          "ERROR", "NET_LOSS_RATE_INVALID", "公共净损失率必须大于 0% 且小于 100%，请核实配置");
    }
    return source(material, bare.trim(), organization, year, businessUnit, rate,
        "AVAILABLE", "NET_LOSS_SOURCE_AVAILABLE", "沿用本产品已有净损失率");
  }

  private static Source source(MaterialMasterRaw material, String bare, String organization, int year, String businessUnit,
      QualityLossRate rate, String status, String code, String message) {
    return new Source(status, code, message, material.getId(), material.getMaterialCode(), material.getMaterialName(),
        material.getMaterialModel(), bare, organization, year, businessUnit,
        rate == null ? null : rate.getId(), rate == null ? null : rate.getLossRate());
  }

  private static Source missing(String reason, String message, String code, String organization, int year, String businessUnit) {
    return new Source("MISSING", reason, message, null, code, null, null, null, organization, year, businessUnit, null, null);
  }
  private static void requireContext(String organization, int year, String businessUnit) {
    if (blank(organization) || blank(businessUnit) || year < 1900 || year > 9999) throw new IllegalArgumentException("净损失率缺少有效组织、核算年度或业务单元");
  }
  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
