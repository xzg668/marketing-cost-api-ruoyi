package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.service.ingest.ResolvedCustomerKey;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceObjectScope.*;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonthlyRepriceObjectScopeTest {
  private static final YearMonth MONTH = YearMonth.of(2026, 11);

  @Test void mergesAcrossOaAndKeepsEverySourceWhileSelectingLatestSuccess() {
    var first = candidate(1, "00123", "客户甲");
    var second = candidate(2, "00123", "客户甲");
    var discovery = MonthlyRepriceObjectScope.discover("COMMERCIAL", MONTH, List.of(first, second));
    assertThat(discovery.sourceCount()).isEqualTo(2);
    assertThat(discovery.unresolved()).isEmpty();
    assertThat(discovery.groups()).hasSize(1);
    var group = discovery.groups().getFirst();
    assertThat(group.sources()).containsExactly(first, second);
    assertThat(group.selected()).isEqualTo(second);
  }

  @Test void keepsCustomerCaseLeadingZerosProductMonthAndUnitDistinct() {
    var base = candidate(1, "00123", "001AbC");
    var same = candidate(2, " 00123 ", " 001AbC ");
    var caseChanged = candidate(3, "00123", "001abc");
    var zeroChanged = candidate(4, "00123", "1AbC");
    var productChanged = candidate(5, "123", "001AbC");
    var result = MonthlyRepriceObjectScope.discover("COMMERCIAL", MONTH,
        List.of(base, same, caseChanged, zeroChanged, productChanged));
    assertThat(result.groups()).hasSize(4);
    var identity = result.groups().getFirst().identity();
    assertThat(identity.key()).isNotEqualTo(new Identity("HOUSEHOLD", MONTH, "00123",
        identity.customerSource(), "001AbC").key());
    assertThat(identity.key()).isNotEqualTo(new Identity("COMMERCIAL", MONTH.plusMonths(1), "00123",
        identity.customerSource(), "001AbC").key());
  }

  @Test void distinguishesVerifiedCodesFromHeaderLabelsAndDoesNotInferAliases() {
    var header = candidate(1, "P", "客户甲");
    var verified = new Candidate(new MonthlyRepriceContract.ObjectIdentity("COMMERCIAL", "OA-2", 2), 2,
        "P", new ResolvedCustomerKey("客户甲", ResolvedCustomerKey.Source.VERIFIED_CUSTOMER_CODE, null), header.completedAt());
    assertThat(MonthlyRepriceObjectScope.discover("COMMERCIAL", MONTH, List.of(header, verified)).groups()).hasSize(2);
  }

  @Test void missingCustomersRemainIndividuallyTraceableAndNeverBecomeOneBlankCustomer() {
    var missing = new ArrayList<Candidate>();
    for (int i = 1; i <= 3; i++) missing.add(new Candidate(
        new MonthlyRepriceContract.ObjectIdentity("COMMERCIAL", "OA-" + i, i), i, "P",
        new ResolvedCustomerKey("OA:OA-" + i, ResolvedCustomerKey.Source.OA_NUMBER_FALLBACK, "客户缺失"),
        LocalDateTime.of(2026, 10, 1, 12, 0)));
    var result = MonthlyRepriceObjectScope.discover("COMMERCIAL", MONTH, missing);
    assertThat(result.groups()).isEmpty();
    assertThat(result.unresolved()).extracting(row -> row.source().source().oaNo())
        .containsExactly("OA-1", "OA-2", "OA-3");
    assertThat(result.sourceCount()).isEqualTo(3);
  }

  @Test void rejectsDuplicateHistoricalVersionsWithinOneSourceAndCrossUnitInput() {
    var first = candidate(1, "P", "C");
    var repeated = new Candidate(first.source(), 2, "P", first.customer(), first.completedAt().plusMinutes(1));
    assertThatThrownBy(() -> MonthlyRepriceObjectScope.discover("COMMERCIAL", MONTH, List.of(first, repeated)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MonthlyRepriceObjectScope.discover("HOUSEHOLD", MONTH, List.of(first)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void tenThousandSourcesHaveNoOmissionsAndBecome2500CustomerProductObjects() {
    var inputs = new ArrayList<Candidate>();
    long version = 1;
    for (int product = 0; product < 250; product++)
      for (int customer = 0; customer < 10; customer++)
        for (int oa = 0; oa < 4; oa++) inputs.add(candidate(version++, "P" + product, "C" + customer));
    var result = MonthlyRepriceObjectScope.discover("COMMERCIAL", MONTH, inputs);
    assertThat(result.sourceCount()).isEqualTo(10000);
    assertThat(result.groups()).hasSize(2500).allSatisfy(group -> assertThat(group.sources()).hasSize(4));
    assertThat(result.groups().stream().map(group -> group.identity().key()).distinct().count()).isEqualTo(2500);
    assertThat(result.groups().stream().flatMap(group -> group.sources().stream()).map(Candidate::costVersionId))
        .containsExactlyElementsOf(inputs.stream().map(Candidate::costVersionId).toList());
  }

  @Test void externallyConstructedGroupsCannotMixAnotherCustomersSource() {
    var source = candidate(1, "P", "C");
    var identity = new Identity("COMMERCIAL", MONTH, "P", ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER, "OTHER");
    assertThatThrownBy(() -> new Group(identity, List.of(source))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void successCompletionTimeTakesPrecedenceOverVersionIdAndInputOrder() {
    var older = candidate(100, "P", "C");
    var laterBase = candidate(2, "P", "C");
    var later = new Candidate(laterBase.source(), laterBase.costVersionId(), laterBase.productCode(),
        laterBase.customer(), older.completedAt().plusDays(1));
    for (var order : List.of(List.of(older, later), List.of(later, older))) {
      var group = MonthlyRepriceObjectScope.discover("COMMERCIAL", MONTH, order).groups().getFirst();
      assertThat(group.selected()).isSameAs(later);
      assertThat(group.sources()).containsExactlyElementsOf(order);
    }
  }

  @Test void equalCompletionTimeUsesHigherVersionIdAndKeepsWholeSourceIdentity() {
    var first = candidate(1, "P", "C");
    var next = candidate(2, "P", "C");
    var tied = new Candidate(next.source(), next.costVersionId(), next.productCode(), next.customer(), first.completedAt());
    for (var order : List.of(List.of(first, tied), List.of(tied, first))) {
      var selected = MonthlyRepriceObjectScope.discover("COMMERCIAL", MONTH, order).groups().getFirst().selected();
      assertThat(selected).isSameAs(tied);
      assertThat(selected.source().oaNo()).isEqualTo("OA-2");
      assertThat(selected.source().oaFormItemId()).isEqualTo(2);
      assertThat(selected.costVersionId()).isEqualTo(2);
    }
  }

  @Test void aSourceWithoutSuccessfulCompletionCannotParticipateInSelection() {
    var source = candidate(1, "P", "C");
    assertThatThrownBy(() -> new Candidate(source.source(), 1, "P", source.customer(), null))
        .isInstanceOf(NullPointerException.class).hasMessageContaining("完成时间");
  }

  private static Candidate candidate(long id, String product, String customer) {
    return new Candidate(new MonthlyRepriceContract.ObjectIdentity("COMMERCIAL", "OA-" + id, id), id,
        product, new ResolvedCustomerKey(customer, ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER, null),
        LocalDateTime.of(2026, 10, 1, 12, 0).plusSeconds(id));
  }
}
