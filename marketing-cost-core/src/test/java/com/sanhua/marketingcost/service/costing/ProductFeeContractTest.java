package com.sanhua.marketingcost.service.costing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sanhua.marketingcost.dto.costing.ProductFeeCode;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProductFeeContractTest {

  @Test
  void newEncodingKeepsUnfilledAmountsDistinctFromExplicitNoFee() {
    assertNull(ProductFeeContract.parseAmount(null));
    assertNull(ProductFeeContract.parseAmount("  "));
    assertEquals(BigDecimal.ZERO, ProductFeeContract.parseAmount("0"));
    assertEquals(BigDecimal.ZERO, ProductFeeContract.parseAmount(" / "));
    assertEquals(ProductFeeContract.State.MISSING, ProductFeeContract.state(null, 2));
    assertEquals(
        ProductFeeContract.State.EXPLICIT_NONE,
        ProductFeeContract.state(BigDecimal.ZERO, 2));
  }

  @ParameterizedTest
  @CsvSource({
      "0.000001, 0.000001",
      "1.234567, 1.234567",
      "999999999999.999999, 999999999999.999999",
      "1.230000, 1.23",
      "000000000001.000000, 1"
  })
  void acceptedAmountsRetainTheExactDecimalValue(String input, String expected) {
    BigDecimal amount = ProductFeeContract.parseAmount(input);
    assertEquals(0, new BigDecimal(expected).compareTo(amount));
    assertEquals(ProductFeeContract.State.EXPLICIT_AMOUNT, ProductFeeContract.state(amount, 2));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "-1", "+1", "1e3", "1E-3", "1,000", "NaN", "Infinity", "invalid",
      "1000000000000", "0000000000000", "0.0000000", "1.0000001", ".5", "1.", "1 0"
  })
  void nonemptyInvalidOrOverPrecisionInputIsRejected(String input) {
    assertThrows(IllegalArgumentException.class, () -> ProductFeeContract.parseAmount(input));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "\t\r\n", "\u3000", " \t\u3000\n"})
  void blankInputIncludingFullWidthWhitespaceRemainsUnfilled(String input) {
    assertNull(ProductFeeContract.parseAmount(input));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(ints = {1})
  void oldEncodingDoesNotInventAnExplicitUserChoice(Integer encoding) {
    assertEquals(ProductFeeContract.State.MISSING, ProductFeeContract.state(null, encoding));
    assertEquals(ProductFeeContract.State.LEGACY, ProductFeeContract.state(BigDecimal.ZERO, encoding));
    assertEquals(
        ProductFeeContract.State.LEGACY,
        ProductFeeContract.state(new BigDecimal("1.234567"), encoding));
  }

  @Test
  void storedTrailingZerosAreRepresentationsRatherThanNewOverPrecisionInput() {
    assertEquals(
        ProductFeeContract.State.LEGACY,
        ProductFeeContract.state(new BigDecimal("1.2300000"), 1));
    assertEquals(
        ProductFeeContract.State.LEGACY,
        ProductFeeContract.state(new BigDecimal("0.0000000"), null));
  }

  @ParameterizedTest
  @CsvSource({"1, LEGACY", "2, EXPLICIT_NONE"})
  void aStoredZeroExponentDoesNotTurnZeroIntoAnOutOfRangeAmount(
      int encoding, ProductFeeContract.State expected) {
    assertEquals(expected, ProductFeeContract.state(new BigDecimal("0E+12"), encoding));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0, 3, Integer.MAX_VALUE})
  void unsupportedEncodingIsRejectedBeforeCheckingAnAbsentAmount(int encoding) {
    assertThrows(IllegalArgumentException.class, () -> ProductFeeContract.state(null, encoding));
    assertThrows(
        IllegalArgumentException.class,
        () -> ProductFeeContract.state(BigDecimal.ZERO, encoding));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-0.000001", "1000000000000", "0.0000001"})
  void invalidStoredAmountsCannotBecomeLegalByBeingMarkedLegacy(String input) {
    for (Integer encoding : Arrays.asList(null, 1, 2)) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ProductFeeContract.state(new BigDecimal(input), encoding));
    }
  }

  @ParameterizedTest
  @CsvSource({
      "MOULD, OTHER_EXP_TECH_MOULD, unitMouldFee, 模具费",
      "CERTIFICATION, OTHER_EXP_TECH_CERTIFICATION, unitCertificationFee, 认证费",
      "DEPRECIATION, OTHER_EXP_TECH_DEPRECIATION, unitDepreciationFee, 折旧费",
      "TOOLING, OTHER_EXP_TECH_TOOLING, unitToolingFee, 工装夹具费"
  })
  void feeDisplayNamesNeverReplaceTheirStableCostAndInputIdentities(
      ProductFeeCode code, String costCode, String fieldName, String displayName) {
    assertEquals(costCode, code.costCode());
    assertEquals(fieldName, code.fieldName());
    assertEquals(displayName, code.displayName());
  }

  @Test
  void exactlyFourFeeRowsHaveTheAgreedDisplayOrder() {
    assertEquals(
        List.of("MOULD", "CERTIFICATION", "DEPRECIATION", "TOOLING"),
        Arrays.stream(ProductFeeCode.values()).map(Enum::name).toList());
  }

  @Test
  void feePolicyAndEncodingHaveIndependentStableIdentifiers() {
    assertEquals(1, ProductFeeContract.LEGACY_ENCODING_VERSION);
    assertEquals(2, ProductFeeContract.CURRENT_ENCODING_VERSION);
    assertEquals("FEE_PROFILE_OPTIONAL_V2", ProductFeeContract.POLICY_VERSION);
  }
}
