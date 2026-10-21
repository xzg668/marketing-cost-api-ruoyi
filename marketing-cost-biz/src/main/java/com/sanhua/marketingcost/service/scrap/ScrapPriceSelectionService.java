package com.sanhua.marketingcost.service.scrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidence;
import com.sanhua.marketingcost.service.pricing.SupplierPriceCandidate;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext;
import com.sanhua.marketingcost.service.pricing.SupplierPriceScope;
import com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 登记废料先选公共月价供应商，再应用客户Cu；消费者组织只进入证据和选供范围。 */
@Service
public class ScrapPriceSelectionService {
  private final ScrapPriceScopeReader scopes;
  private final SupplierPriceSelectionService suppliers;
  private final OaFormMapper forms;
  private final ScrapPriceCalculator calculator;
  private final ScrapPriceCalculationEvidence evidence;

  public ScrapPriceSelectionService(
      ScrapPriceScopeReader scopes,
      SupplierPriceSelectionService suppliers,
      OaFormMapper forms,
      ScrapPriceCalculator calculator,
      ScrapPriceCalculationEvidence evidence) {
    this.scopes = scopes;
    this.suppliers = suppliers;
    this.forms = forms;
    this.calculator = calculator;
    this.evidence = evidence;
  }

  public record Selection(
      String priceType, BigDecimal unitPrice, PriceResolveEvidence evidence, String message) {}

  public ScrapPriceScopeReader.Batch openBatch() {
    return scopes.openBatch();
  }

  public boolean isManaged(
      String businessUnit, String month, String materialCode, boolean prepare) {
    return scopes.read(businessUnit, month, prepare).managedMaterials().contains(materialCode);
  }

  /** 空 Optional 仅表示普通物料；已登记或明确废料缺价直接报错，不能跌入SRM同料号。 */
  public Optional<Selection> select(
      String businessUnit,
      String month,
      String materialCode,
      String consumerOrg,
      String oaNo,
      LocalDate date,
      PricePrepareScenarioContext scenario,
      boolean prepare,
      boolean requiredScrap) {
    return select(
        businessUnit,
        month,
        materialCode,
        consumerOrg,
        oaNo,
        date,
        scenario.scenarioType(),
        scenario.supplierReviews(),
        prepare,
        requiredScrap);
  }

  public Selection selectForFormula(
      String businessUnit,
      String month,
      String materialCode,
      String consumerOrg,
      String oaNo,
      LocalDate date,
      boolean financeQuoteBase,
      SupplierPriceReviewContext reviews) {
    var scenario =
        financeQuoteBase || oaNo == null || oaNo.isBlank()
            ? QuotePriceScenarioType.FINANCE_QUOTE_BASE
            : QuotePriceScenarioType.OA_LOCKED;
    return select(
            businessUnit,
            month,
            materialCode,
            consumerOrg,
            oaNo,
            date,
            scenario,
            reviews == null ? new SupplierPriceReviewContext(null) : reviews,
            false,
            true)
        .orElseThrow();
  }

  private Optional<Selection> select(
      String businessUnit,
      String month,
      String materialCode,
      String consumerOrg,
      String oaNo,
      LocalDate date,
      QuotePriceScenarioType scenarioType,
      SupplierPriceReviewContext reviews,
      boolean prepare,
      boolean requiredScrap) {
    MaterialOrganization.fromPriceOrgCode(consumerOrg);
    var scope = scopes.read(businessUnit, month, prepare);
    if (!scope.managedMaterials().contains(materialCode) && !requiredScrap) return Optional.empty();
    var rows = scope.byMaterial().getOrDefault(materialCode, List.of());
    if (rows.isEmpty())
      throw new IllegalArgumentException("废料" + materialCode + "在" + month + "没有适用价格规则");
    var byKey = new LinkedHashMap<String, ScrapPrice>();
    for (var row : rows) {
      if (!"READY".equals(row.getCalcStatus()) || row.getUnitPrice() == null)
        throw new IllegalArgumentException("废料" + materialCode + "尚不可取价：" + row.getCalcMessage());
      if (rows.size() > 1 && (row.getSupplierCode() == null || row.getSupplierCode().isBlank()))
        throw new IllegalArgumentException("废料" + materialCode + "存在多个候选且供应商身份不明确，请维护供应商代码或移除冲突价格");
      if (byKey.putIfAbsent(row.getInputFingerprint(), row) != null)
        throw new IllegalArgumentException("废料候选价格身份重复：" + materialCode);
    }
    var supplierScope =
        new SupplierPriceScope(
            businessUnit,
            oaNo,
            reviews.oaFormItemId(),
            month,
            date == null ? java.time.YearMonth.parse(month).atDay(1) : date,
            consumerOrg,
            materialCode,
            ScrapPriceCalculationEvidence.SOURCE);
    var candidates =
        byKey.entrySet().stream()
            .map(
                e ->
                    new SupplierPriceCandidate(
                        e.getKey(),
                        e.getValue().getSupplierCode(),
                        e.getValue().getSupplierName(),
                        e.getValue().getUnitPrice(),
                        null,
                        null))
            .toList();
    var selected = suppliers.select(supplierScope, candidates);
    if (selected.review() != null) reviews.add(selected.review());
    if (selected.candidate() == null)
      throw new IllegalArgumentException("废料" + materialCode + "：" + selected.message());
    var row = byKey.get(selected.candidate().key());
    var factors = evidence.publicFactors(row);
    if (factors != null
        && scenarioType == QuotePriceScenarioType.OA_LOCKED
        && row.getFactorBindingsJson().contains("AVG|1#CU")) {
      var form =
          forms.selectOne(
              Wrappers.lambdaQuery(OaForm.class)
                  .eq(OaForm::getOaNo, oaNo)
                  .eq(OaForm::getBusinessUnitType, businessUnit));
      if (form == null || form.getCopperPrice() == null)
        throw new IllegalArgumentException("废料" + materialCode + "缺少报价客户Cu");
      factors = factors.withCustomerCopperPerTon(form.getCopperPrice());
    }
    var input = calculator.prepare(row, factors);
    BigDecimal price =
        factors != null && factors.customerCopperPricePerTon() != null
            ? calculator.calculate(input)
            : row.getUnitPrice();
    var snapshot = evidence.capture(row, consumerOrg, scenarioType, input, price, selected);
    return Optional.of(
        new Selection(
            row.getPriceType(),
            price,
            evidence.resolveEvidence(snapshot),
            rows.size() == 1 && row.getSupplierCode() == null ? "废料独立月价" : selected.message()));
  }
}
