package com.sanhua.marketingcost.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPreparationPrice;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** Reads only the selected, captured quote. Never chooses another supplier or current source row. */
public record MakePartQuotedWeight(BigDecimal grams, String source) {
  private static final ObjectMapper JSON = new ObjectMapper();

  public static MakePartQuotedWeight read(MakePartMaterialPriceResolveResult price,
      MonthlyPriceDocumentCodec codec) {
    if (price == null || !"OK".equals(price.getStatus()) || price.getEvidence() == null
        || price.getEvidence().inputSnapshotJson() == null) return null;
    try {
      var captured = codec.decode(price.getEvidence().inputSnapshotJson(), MonthlyPreparationPrice.class);
      var fact = JSON.readTree(captured.selection().fact().evidence().json());
      String kind = fact.path("sourceKind").asText();
      boolean linked = "LINKED".equals(kind) || "LINKED_LEGACY".equals(kind);
      boolean range = "RANGE_FACTOR".equals(kind) || "RANGE_LEGACY_FACTOR".equals(kind);
      if (!linked && !range) return null;
      var source = fact.path("source");
      JsonNode selected = source.path("selected");
      if (selected.isMissingNode()) selected = source.path("selectedRule");
      if (!captured.request().materialCode().equals(selected.path("materialCode").asText())) return null;
      JsonNode selectedRule = selected;
      if (range) {
        var bandId = source.path("selectedBandId");
        if (bandId.isMissingNode()) bandId = source.path("calculation").path("resultRefId");
        for (var band : source.path("bands")) {
          if (!bandId.isMissingNode() && bandId.asText().equals(band.path("id").asText())) {
            selected = band; break;
          }
        }
      }
      BigDecimal grams = fromQuote(selected);
      if (grams == null && range) grams = fromRangeRule(selectedRule, selected);
      return grams == null ? null : new MakePartQuotedWeight(grams,
          linked ? "SRM联动价表" : "SRM区间价表");
    } catch (java.io.IOException | IllegalArgumentException invalid) {
      // Older adopted facts may lack weight evidence; retain the existing U9 fallback.
      return null;
    }
  }

  private static BigDecimal fromQuote(JsonNode selected) throws java.io.IOException {
    // Imported net_weight is stored in grams; the variable registry converts it to kg for pricing.
    BigDecimal explicit = decimal(selected.path("netWeight"));
    if (explicit != null) return explicit;
    String encoded = selected.path("sourceInputSnapshotJson").asText(null);
    if (encoded == null) return null;
    var rule = JSON.readTree(encoded).path("rule");
    return fromCompiled(rule.path("sourceFormula").asText(""), rule.path("compiled").path("sources"), rule.path("sheet").asText(""));
  }

  private static BigDecimal fromRangeRule(JsonNode selectedRule, JsonNode band) throws java.io.IOException {
    String encoded = selectedRule.path("sourceInputSnapshotJson").asText(null);
    String cell = band.path("sourceCellRef").asText("");
    String sheet = band.path("sourceSheetName").asText("");
    if (encoded == null || cell.isBlank()) return null;
    for (var rule : JSON.readTree(encoded).path("sources")) {
      for (var originalBand : rule.path("bands")) {
        var address = originalBand.path("address");
        if (!cell.equals(address.path("cell").asText())
            || !sheet.equals(address.path("sheet").asText())) continue;
        return fromCompiled(originalBand.path("source").path("formula").asText(""),
            originalBand.path("compiled").path("sources"), sheet);
      }
    }
    return null;
  }

  private static BigDecimal fromCompiled(String originalFormula, JsonNode inputs, String formulaSheet) {
    String formula = originalFormula.replace("$", "");
    var weights = new LinkedHashSet<BigDecimal>();
    for (var input : inputs) {
      BigDecimal value = decimal(input.path("rawValue"));
      String cell = input.path("address").path("cell").asText("").replace("$", "");
      String sheet = input.path("address").path("sheet").asText("");
      String qualified = "(?:" + Pattern.quote(sheet) + "|"
          + Pattern.quote("'" + sheet.replace("'", "''") + "'") + ")!";
      String reference = (sheet.isEmpty() ? "" : formulaSheet.isEmpty() || sheet.equalsIgnoreCase(formulaSheet)
          ? "(?:" + qualified + ")?" : qualified) + Pattern.quote(cell);
      // A captured worksheet value multiplied into the quote and divided by 1000 is grams.
      // Reject ambiguous candidates rather than treating a metal-price conversion as a weight.
      if (value != null && !cell.isEmpty() && Pattern.compile(
          "(?i)\\*\\s*\\(*\\s*" + reference
              + "(?![A-Z0-9_])\\s*\\)*\\s*/\\s*1000(?![0-9.])").matcher(formula).find()) {
        weights.add(value.stripTrailingZeros());
      }
    }
    return weights.size() == 1 ? weights.iterator().next() : null;
  }

  private static BigDecimal decimal(JsonNode value) {
    if (value.isMissingNode() || value.isNull()) return null;
    try {
      var number = new BigDecimal(value.asText().trim());
      return number.signum() < 0 ? null : number;
    } catch (NumberFormatException invalid) { return null; }
  }
}
