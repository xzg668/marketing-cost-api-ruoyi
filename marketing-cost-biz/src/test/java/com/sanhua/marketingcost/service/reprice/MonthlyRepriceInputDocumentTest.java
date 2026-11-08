package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceInputDocument.*;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MonthlyRepriceInputDocumentTest {
  @Test void completeMaterialCollectionCanExceedTheLimitOfOneSourceFact() {
    String evidence="来源".repeat(6000);
    String row="{\"price\":0.123456789012345678,\"evidence\":\""+evidence+"\"}";
    var inputs=sections();
    inputs.put(Section.MATERIAL_PRICES,"{\"prices\":["+String.join(",",java.util.Collections.nCopies(40,row))+"]}");
    var captured=MonthlyRepriceInputDocument.capture(scope(),inputs);
    assertThat(captured.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isGreaterThan(1024*1024);
    assertThat(captured.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isTrue();
    inputs.put(Section.MATERIAL_PRICES,"{\"prices\":["+String.join(",",java.util.Collections.nCopies(39,row))+"]}");
    assertThat(captured.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isFalse();
  }

  @Test void equivalentDecimalsAndObjectFieldOrderShareButNumericStringsDoNot() {
    var first = sections();
    var second = sections();
    first.put(Section.MANUFACTURING, "{\"rate\":0.0100,\"labor\":12}");
    second.put(Section.MANUFACTURING, "{\"labor\":12.0,\"rate\":1e-2}");
    var captured = MonthlyRepriceInputDocument.capture(scope(), first);
    assertThat(captured.sameInputs(MonthlyRepriceInputDocument.capture(scope(), second))).isTrue();
    second.put(Section.MANUFACTURING, "{\"labor\":\"12.0\",\"rate\":0.01}");
    assertThat(captured.sameInputs(MonthlyRepriceInputDocument.capture(scope(), second))).isFalse();
  }

  @ParameterizedTest @EnumSource(Section.class)
  void aChangeInAnyInputSectionPreventsReuse(Section section) {
    var changed = sections();
    changed.put(section, "{\"value\":2}");
    assertThat(MonthlyRepriceInputDocument.capture(scope(), sections())
        .sameInputs(MonthlyRepriceInputDocument.capture(scope(), changed))).isFalse();
  }

  @Test void hashCollisionIsNotAnEqualInputAndCannotMatchStoredContent() {
    var changed = sections();
    changed.put(Section.COPPER_SCENARIOS, "{\"value\":80000}");
    var first = MonthlyRepriceInputDocument.capture(scope(), sections(), ignored -> "0".repeat(64));
    var second = MonthlyRepriceInputDocument.capture(scope(), changed, ignored -> "0".repeat(64));
    assertThat(first.digest()).isEqualTo(second.digest());
    assertThat(first.sameInputs(second)).isFalse();
    assertThat(first.matchesStored(second.digest(), second.canonical())).isFalse();
    assertThat(first.matchesStored(first.digest(), first.canonical())).isTrue();
  }

  @Test void bomOrderRepeatedMaterialOccurrencesAndNullVersusZeroArePreserved() {
    var inputs = sections();
    inputs.put(Section.BOM, "{\"lines\":[{\"material\":\"M\",\"qty\":1},{\"material\":\"M\",\"qty\":2}]}");
    var first = MonthlyRepriceInputDocument.capture(scope(), inputs);
    inputs.put(Section.BOM, "{\"lines\":[{\"material\":\"M\",\"qty\":2},{\"material\":\"M\",\"qty\":1}]}");
    assertThat(first.sameInputs(MonthlyRepriceInputDocument.capture(scope(), inputs))).isFalse();
    inputs.put(Section.TECHNICAL_INPUTS, "{\"mouldFee\":null}");
    var missing = MonthlyRepriceInputDocument.capture(scope(), inputs);
    inputs.put(Section.TECHNICAL_INPUTS, "{\"mouldFee\":0}");
    assertThat(missing.sameInputs(MonthlyRepriceInputDocument.capture(scope(), inputs))).isFalse();
  }

  @Test void retainsExactPrecisionWithoutRoundingAndDoesNotAliasScope() {
    var inputs = sections();
    inputs.put(Section.MATERIAL_PRICES, "{\"price\":0.123456789012345678}");
    var first = MonthlyRepriceInputDocument.capture(scope(), inputs);
    inputs.put(Section.MATERIAL_PRICES, "{\"price\":0.123456789012345679}");
    assertThat(first.sameInputs(MonthlyRepriceInputDocument.capture(scope(), inputs))).isFalse();
    var scope = scope();
    var nextMonth = new Scope(scope.businessUnitType(), scope.month().plusMonths(1), scope.productCode(),
        scope.organization(), scope.algorithmVersion(), scope.feePolicyVersion());
    assertThat(MonthlyRepriceInputDocument.capture(scope, inputs)
        .sameInputs(MonthlyRepriceInputDocument.capture(nextMonth, inputs))).isFalse();
    assertThat(first.canonical()).contains("0.123456789012345678");
  }

  @Test void rejectsMissingSectionsDuplicateJsonKeysTrailingTokensAndEmptyFacts() {
    var inputs = sections();
    inputs.remove(Section.SUPPLIER_TERMS);
    assertThatThrownBy(() -> MonthlyRepriceInputDocument.capture(scope(), inputs))
        .isInstanceOf(IllegalArgumentException.class);
    for (String invalid : new String[]{"{}", "{\"rate\":1,\"rate\":2}", "{\"rate\":1} {}", "null", "[]"}) {
      var malformed = sections();
      malformed.put(Section.MANUFACTURING, invalid);
      assertThatThrownBy(() -> MonthlyRepriceInputDocument.capture(scope(), malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test void capturedValueDoesNotChangeWhenCallerMutatesTheSectionMap() {
    var inputs = sections();
    var first = MonthlyRepriceInputDocument.capture(scope(), inputs);
    inputs.clear();
    assertThat(first.sameInputs(MonthlyRepriceInputDocument.capture(scope(), sections()))).isTrue();
  }

  @Test void productUnitOrganizationsAndBothAlgorithmPoliciesArePartOfEquality() {
    var original = scope();
    var baseline = MonthlyRepriceInputDocument.capture(original, sections());
    for (var changed : new Scope[]{
        new Scope("COMMERCIAL", original.month(), "123", original.organization(), original.algorithmVersion(), original.feePolicyVersion()),
        new Scope("HOUSEHOLD", original.month(), "00123", original.organization(), original.algorithmVersion(), original.feePolicyVersion()),
        new Scope("COMMERCIAL", original.month(), "00123", new QuoteDataOrganization("220", "PLATE"), original.algorithmVersion(), original.feePolicyVersion()),
        new Scope("COMMERCIAL", original.month(), "00123", original.organization(), "NEXT_ALGORITHM", original.feePolicyVersion()),
        new Scope("COMMERCIAL", original.month(), "00123", original.organization(), original.algorithmVersion(), "NEXT_FEES")}) {
      assertThat(baseline.sameInputs(MonthlyRepriceInputDocument.capture(changed, sections()))).isFalse();
    }
    assertThatThrownBy(() -> new Scope("COMMERCIAL", original.month(), "00123",
        new QuoteDataOrganization("210", "PLATE"), original.algorithmVersion(), original.feePolicyVersion()))
        .isInstanceOf(IllegalArgumentException.class);
  }


  @ParameterizedTest @EnumSource(Section.class)
  void everySectionMustExistAndInvalidInputIdentifiesItsSection(Section section) {
    var missing=sections();missing.remove(section);
    assertThatThrownBy(()->MonthlyRepriceInputDocument.capture(scope(),missing))
        .hasMessageContaining("所有解析区块");
    var invalid=sections();invalid.put(section,null);
    assertThatThrownBy(()->MonthlyRepriceInputDocument.capture(scope(),invalid))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(section.name());
  }

  @Test void fullDetailMultiplicityParentGroupsAndSignedQuantitiesCannotBeReplacedByATotal() {
    var inputs=sections();
    inputs.put(Section.BOM,"{\"lines\":[{\"parent\":1,\"material\":\"M\",\"quantity\":2},{\"parent\":2,\"material\":\"M\",\"quantity\":2}]}");
    var separate=MonthlyRepriceInputDocument.capture(scope(),inputs);
    inputs.put(Section.BOM,"{\"lines\":[{\"parent\":1,\"material\":\"M\",\"quantity\":4}]}");
    assertThat(separate.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isFalse();
    inputs.put(Section.BOM,"{\"lines\":[{\"parent\":1,\"material\":\"M\",\"quantity\":2},{\"parent\":1,\"material\":\"M\",\"quantity\":2}]}");
    assertThat(separate.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isFalse();
    inputs.put(Section.BOM,"{\"lines\":[{\"parent\":1,\"material\":\"M\",\"quantity\":2},{\"parent\":2,\"material\":\"M\",\"quantity\":-2}]}");
    assertThat(separate.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isFalse();
  }

  @Test void omittedExplicitZeroAndLegacyFeeEncodingRemainDifferent() {
    var inputs=sections();
    inputs.put(Section.TECHNICAL_INPUTS,"{\"fees\":{\"encodingVersion\":2,\"mould\":null}}");
    var absent=MonthlyRepriceInputDocument.capture(scope(),inputs);
    inputs.put(Section.TECHNICAL_INPUTS,"{\"fees\":{\"encodingVersion\":2}}");
    assertThat(absent.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isFalse();
    inputs.put(Section.TECHNICAL_INPUTS,"{\"fees\":{\"encodingVersion\":2,\"mould\":0}}");
    var zero=MonthlyRepriceInputDocument.capture(scope(),inputs);
    assertThat(absent.sameInputs(zero)).isFalse();
    inputs.put(Section.TECHNICAL_INPUTS,"{\"fees\":{\"encodingVersion\":1,\"mould\":0}}");
    assertThat(zero.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isFalse();
  }

  @Test void canonicalNumbersKeepLargeIntegerLowDigitsAndExactFractions() {
    var inputs=sections();
    inputs.put(Section.MATERIAL_PRICES,"{\"price\":123456789012345678901234567890.123456789012345678,\"tiny\":1e-30}");
    var first=MonthlyRepriceInputDocument.capture(scope(),inputs);
    assertThat(first.canonical()).contains("123456789012345678901234567890.123456789012345678",
        "0.000000000000000000000000000001");
    inputs.put(Section.MATERIAL_PRICES,"{\"tiny\":0.000000000000000000000000000001,\"price\":123456789012345678901234567890.1234567890123456780}");
    assertThat(first.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isTrue();
    inputs.put(Section.MATERIAL_PRICES,"{\"price\":123456789012345678901234567891.123456789012345678,\"tiny\":1e-30}");
    assertThat(first.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isFalse();
  }

  @Test void unsupportedOrExcessiveInputIsRejectedWithoutTruncation() {
    for (var value:java.util.List.of("{\"x\":1e5000}","{\"x\":NaN}",
        "{\"nested\":{\"a\":1,\"a\":2}}","{\"x\":"+"[".repeat(64)+"0"+"]".repeat(64)+"}")) {
      var inputs=sections();inputs.put(Section.BOM,value);
      assertThatThrownBy(()->MonthlyRepriceInputDocument.capture(scope(),inputs))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BOM");
    }
    assertThatThrownBy(()->MonthlyRepriceInputDocument.capture(scope(),null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void theCombinedDocumentHasOneBoundedBudgetWithoutTruncatingAnySection() {
    var inputs=sections();
    for(var section:Section.values()) inputs.put(section,"{\"value\":\""+"中".repeat(40)+"\"}");
    assertThatThrownBy(()->MonthlyRepriceInputDocument.capture(scope(),inputs,
        com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec::sha256,1024))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("区块");
    var accepted=MonthlyRepriceInputDocument.capture(scope(),inputs);
    int exact=accepted.canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    assertThat(MonthlyRepriceInputDocument.capture(scope(),inputs,
        com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec::sha256,exact).canonical())
        .isEqualTo(accepted.canonical());
    assertThatThrownBy(()->MonthlyRepriceInputDocument.capture(scope(),inputs,
        com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec::sha256,exact-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void escapedUnicodeIsEquivalentButBusinessTextIsNotSilentlyTrimmed() {
    var inputs=sections();inputs.put(Section.SUPPLIER_TERMS,"{\"supplier\":\"供应商\"}");
    var unicode=MonthlyRepriceInputDocument.capture(scope(),inputs);
    String escaped="{\"supplier\":\""+"\\u4f9b\\u5e94\\u5546"+"\"}";
    inputs.put(Section.SUPPLIER_TERMS,escaped);
    assertThat(unicode.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isTrue();
    inputs.put(Section.SUPPLIER_TERMS,"{\"supplier\":\"供应商 \"}");
    assertThat(unicode.sameInputs(MonthlyRepriceInputDocument.capture(scope(),inputs))).isFalse();
  }

  private static Scope scope() {
    return new Scope("COMMERCIAL", YearMonth.of(2026, 11), "00123",
        new QuoteDataOrganization("210", "COMMERCIAL"), "MONTHLY_PRODUCT_COST_V1", "FEE_PROFILE_OPTIONAL_V2");
  }

  private static Map<Section, String> sections() {
    var result = new EnumMap<Section, String>(Section.class);
    for (var section : Section.values()) result.put(section, "{\"value\":1}");
    return result;
  }
}
