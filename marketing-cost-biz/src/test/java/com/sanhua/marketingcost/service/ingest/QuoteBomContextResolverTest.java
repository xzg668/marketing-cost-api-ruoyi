package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class QuoteBomContextResolverTest {
  private final QuoteBomContextResolver resolver = new QuoteBomContextResolver();

  @Test
  void explicitOaAccountingMonthWinsOverApplyDateAndServerMonth() {
    OaForm form = form("OA-CTX-001", "2026-08", LocalDate.of(2026, 7, 31));

    QuoteBomContext context = resolver.resolve(form, item(" MAT-1001 ", " BOX "));

    assertThat(context.costPeriodMonth()).isEqualTo("2026-08");
    assertThat(context.productCode()).isEqualTo("MAT-1001");
    assertThat(context.packageMethod()).isEqualTo("BOX");
  }

  @Test
  void applyDateMonthIsUsedWhenAccountingMonthMissing() {
    OaForm form = form("OA-CTX-002", null, LocalDate.of(2026, 9, 6));

    QuoteBomContext context = resolver.resolve(form, item("MAT-1002", null));

    assertThat(context.costPeriodMonth()).isEqualTo("2026-09");
  }

  @Test
  void existingExplicitCostPeriodKeepsMonthlyRepriceContext() {
    OaForm form = form("OA-CTX-REPRICE", "2026-01", LocalDate.of(2026, 1, 6));

    QuoteBomContext context =
        resolver.resolveWithExistingCostPeriod(form, item("MAT-REPRICE", "BOX"), "2026-07");

    assertThat(context.costPeriodMonth()).isEqualTo("2026-07");
  }

  @Test
  void missingAccountingMonthAndApplyDateIsBlockedExplicitly() {
    OaForm form = form("OA-CTX-003", null, null);

    assertThatThrownBy(() -> resolver.resolve(form, item("MAT-1003", "BOX")))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("核算月份")
        .hasMessageContaining("申请日期");
  }

  @Test
  void malformedAccountingMonthIsBlockedInsteadOfSilentlyChangingMonth() {
    OaForm form = form("OA-CTX-004", "2026-8", LocalDate.of(2026, 8, 1));

    assertThatThrownBy(() -> resolver.resolve(form, item("MAT-1004", "BOX")))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("YYYY-MM");
  }

  @Test
  void blankProductCodeIsBlocked() {
    assertThatThrownBy(
            () ->
                resolver.resolve(
                    form("OA-CTX-005", "2026-08", null), item(" ", "BOX")))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("产品料号为空");
  }

  @Test
  void nullSlashAndBlankPackageAreSameAndTextIsTrimmed() {
    OaForm form = form("OA-CTX-006", "2026-08", null);

    assertThat(resolver.resolve(form, item("MAT-1", null)).packageMethod()).isEmpty();
    assertThat(resolver.resolve(form, item("MAT-1", " / ")).packageMethod()).isEmpty();
    assertThat(resolver.resolve(form, item("MAT-1", "   ")).packageMethod()).isEmpty();
    assertThat(resolver.resolve(form, item("MAT-1", " BOX ")).packageMethod())
        .isEqualTo("BOX");
  }

  private OaForm form(String oaNo, String accountingMonth, LocalDate applyDate) {
    OaForm form = new OaForm();
    form.setOaNo(oaNo);
    form.setAccountingPeriodMonth(accountingMonth);
    form.setApplyDate(applyDate);
    form.setCustomer("客户甲");
    return form;
  }

  private OaFormItem item(String materialNo, String packageMethod) {
    OaFormItem item = new OaFormItem();
    item.setMaterialNo(materialNo);
    item.setPackageMethod(packageMethod);
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }
}
