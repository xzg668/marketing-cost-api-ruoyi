package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyCostVersionIntegrationTest.*;
import com.sanhua.marketingcost.service.pricing.CustomerMaterialPrices;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.PreparationBinding;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

/** Real MySQL adoption, shared P0, quote-local P1 and final cost persistence. */
@Tag("integration")
class MonthlyMaterialDifferenceIntegrationTest {
  @BeforeAll static void start() throws Exception { database(); }
  @AfterAll static void close() { stop(); }

  @Test void boardCustomerPricesDoNotEnterMonthlyP0AndTheDifferenceIsSavedExactlyOnce() {
    String month = "2061-01", material = code();
    publication("COMMERCIAL", month, "220");
    rule(material, "COMMERCIAL", month, "220", "S1", "cu+sus", true);
    publish("COMMERCIAL", "220");
    var sourceBefore = jdbc.queryForList("SELECT * FROM lp_finance_effective_price WHERE material_code=? ORDER BY id", material);
    var context = context("COMMERCIAL", month, "220");
    var reader = new MonthlyQuotationPrices(operation, household);
    var customer = new CustomerMaterialPrices(d("100000"), null, null, null, d("25000"), null);
    var session = new MonthlyQuotationSession(context, customer, reader, MonthlyScenarioTestSupport.create(null), priceStorage, codec, false);
    assertThat(session.conditions("220")).isEqualTo(NonCuConditions.empty());
    var input = new QuotationPriceSession.Input(material, "220", "件", "SPEC", TaxBasis.EXCLUDED,
        null, session.conditions("220"), d("2.5"), QuotationPriceSession.Purpose.MATERIAL);
    var first = session.read(List.of(input));
    assertThat(first.problems()).isEmpty();
    var value = first.values().get(input);
    assertThat(value.financialPrice()).isEqualByComparingTo("110");
    assertThat(value.customerPrice()).isEqualByComparingTo("125");
    session.consume("root/board", input, d("2.5"));
    var adopted = operation.adopt(context, session.plan()).plan();
    var selected = adopted.selections().get(value.request());
    long priceId = selected.monthlyPriceId();
    assertThat(selected.fact().price()).isEqualByComparingTo("110");
    var monthlyBefore = jdbc.queryForList("SELECT * FROM lp_material_monthly_price WHERE id=?", priceId);

    var other = new MonthlyQuotationSession(context,
        new CustomerMaterialPrices(null, null, null, null, d("30000"), null), reader,
        MonthlyScenarioTestSupport.create(null), priceStorage, codec, false);
    var second = other.read(List.of(input));
    assertThat(second.problems()).isEmpty();
    assertThat(second.values().get(input).selection().monthlyPriceId()).isEqualTo(priceId);
    assertThat(second.values().get(input).financialPrice()).isEqualByComparingTo("110");
    assertThat(second.values().get(input).customerPrice()).isEqualByComparingTo("130");

    var version = versionService.createRunning(context.oaNo(), 1L, "TOP", month, month, "FIN-METALS", "COMMERCIAL");
    version.setFinancePricePrepareNo("FIN-METALS"); version.setOaPricePrepareNo("OA-METALS"); versionMapper.updateById(version);
    long financial = prepare(version, value.request(), "FIN-METALS", "root/board", d("2.5"), d("110"));
    long quoted = prepare(version, value.request(), "OA-METALS", "root/board", d("2.5"), d("125"));
    jdbc.update("UPDATE lp_price_prepare_item SET price_org_code='220' WHERE id IN (?,?)", financial, quoted);
    var line = new MaterialLine("root/board", material, d("2.5"), List.of(priceId), d("275"), d("312.5"), null);
    var ordinary = new Ordinary(List.of(line), BigDecimal.ZERO, d("10"), d("2"), d(".01"), d(".20"),
        d("1.05"), d(".1"), d(".25"), d(".02"), BigDecimal.ZERO);
    var saved = costing.calculateAndSaveMaterials("COMMERCIAL", version.getId(), adopted, ordinary, null, customer,
        Map.of("root/board", new PreparationBinding(financial, quoted)));
    assertThat(saved.version().getCuMaterialAdjustment()).isEqualByComparingTo("37.5");
    assertThat(saved.version().getFinalQuoteAmount().subtract(saved.version().getTotalCost())).isEqualByComparingTo("37.5");
    assertThat(costing.read("COMMERCIAL", version.getId()).snapshot()).isEqualTo(saved.snapshot());
    assertThat(jdbc.queryForList("SELECT * FROM lp_material_monthly_price WHERE id=?", priceId)).isEqualTo(monthlyBefore);
    assertThat(jdbc.queryForList("SELECT * FROM lp_finance_effective_price WHERE material_code=? ORDER BY id", material)).isEqualTo(sourceBefore);
  }
}
