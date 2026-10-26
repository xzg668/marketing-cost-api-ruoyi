package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidence;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Shared read-only base-price input for the existing manufacturing calculator and technical checks. */
@Service
public class MakePartMaterialPriceResolveServiceImpl implements MakePartMaterialPriceResolveService {
  private final MonthlyQuotationSessions sessions;
  private final MaterialMasterRawMapper materials;
  private final MonthlyPriceDocumentCodec codec;

  public MakePartMaterialPriceResolveServiceImpl(MonthlyQuotationSessions sessions,
      MaterialMasterRawMapper materials, MonthlyPriceDocumentCodec codec) {
    this.sessions = sessions; this.materials = materials; this.codec = codec;
  }

  @Override public MakePartMaterialPriceResolveResult calculateMaterialUnitPrice(String materialCode,
      String period, LocalDate quoteDate, LocalDateTime asOf, String oaNo, String businessUnit,
      PricePrepareScenarioContext scenario, Purpose purpose) {
    String code = materialCode == null ? null : materialCode.trim();
    if (code == null || code.isBlank()) return MakePartMaterialPriceResolveResult.miss(
        code, "INVALID_INPUT", "materialCode 为空", null);
    Objects.requireNonNull(scenario, "制造件材料取价缺少报价产品上下文");
    String organization = scenario.supplierReviews().priceOrgCode();
    String materialOrganization = MaterialOrganization.fromPriceOrgCode(organization).getCode();
    var masters = materials.selectByLatestBatchAndCodes(List.of(code), null, materialOrganization);
    if (masters.size() > 1 || (purpose != Purpose.SCRAP && (masters.isEmpty()
        || masters.getFirst().getUnit() == null || masters.getFirst().getUnit().isBlank())))
      return MakePartMaterialPriceResolveResult.miss(code, "INVALID_INPUT", "原料采购单位或料品组织尚未核实", null);
    var master = masters.isEmpty() ? null : masters.getFirst();
    var session = sessions.open(oaNo, businessUnit, period,
        asOf == null ? LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE) : asOf, scenario);
    var input = new QuotationPriceSession.Input(code, organization,
        purpose == Purpose.SCRAP ? "KG" : master.getUnit(), master == null ? null : master.getMaterialSpec(),
        purpose == Purpose.SCRAP ? TaxBasis.EXCLUDED : null, null, session.conditions(organization),
        BigDecimal.ONE, QuotationPriceSession.Purpose.valueOf(purpose.name()));
    var read = session.read(List.of(input));
    var value = read.values().get(input);
    if (value == null) {
      var problem = read.problems().get(input);
      return MakePartMaterialPriceResolveResult.miss(code,
          problem.reason() == MonthlyAdoptionRetryPolicy.Failure.MISSING ? "MISSING_PRICE" : "ERROR",
          problem.message(), MonthlyPreparationPrice.KIND);
    }
    var result = MakePartMaterialPriceResolveResult.ok(code, value.selection().fact().type().name(),
        scenario.scenarioType() == QuotePriceScenarioType.FINANCE_QUOTE_BASE
            ? value.financialPrice() : value.customerPrice(),
        "按当月适用基础物料单价计算", MonthlyPreparationPrice.KIND);
    result.setEvidence(new PriceResolveEvidence(value.selection().monthlyPriceId(), null, null, null,
        null, null, null, null, false, null, codec.encode(MonthlyPreparationPrice.read(value))));
    return result;
  }
}
