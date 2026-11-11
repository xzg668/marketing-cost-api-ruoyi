package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Explicitly invoked offline check of the uploaded original; no database connection. */
@Tag("integration")
class OriginalLinkedImportedSourceLocalAcceptance {
  @Test void original301030020BindsAllMonthlyBasesWithoutChangingTheWorkbook() throws Exception {
    Path evidence=Path.of(System.getProperty("linked.refresh.evidence"));
    Path inputs=Path.of(System.getProperty("linked.refresh.inputs"));
    var json=new ObjectMapper().findAndRegisterModules();
    Path file=inputs.resolve("actual-import-source-59.xls");byte[] bytes=Files.readAllBytes(file);
    String hash=PriceLinkedImportFileDigest.sha256(bytes);
    var observed=json.readTree(evidence.resolve("current.private.json").toFile());
    var calculation=json.readValue(observed.path("sources").path("lp_finance_effective_price").get(0).path("calculation_json").asText(),FinanceEffectivePriceService.Calculation.class);
    var factors=new LinkedHashMap<String,FactorPriceSnapshot.FactorValue>();
    for(var row:json.readTree(evidence.resolve("finance-factors.json").toFile())) {
      if(row.path("source_upload_batch_id").asLong()!=58 || !row.path("scheme_code").asText().equals("CU_90000"))continue;
      String key=row.path("canonical_factor_key").asText();
      factors.put(key,new FactorPriceSnapshot.FactorValue(1L,row.path("id").asLong(),row.path("factor_name").asText(),row.path("short_name").asText(),new BigDecimal(row.path("price").asText()),row.path("unit").asText(),true,List.of()));
    }
    var units=new LinkedHashMap<String,String>();
    for(var row:json.readTree(inputs.resolve("source-59-rules.json").toFile()))units.put(row.path("material_code").asText(),row.path("unit").asText());
    var parser=new OriginalLinkedWorkbookParser(new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl()));
    var parsed=parser.parseWithCatalog(bytes,"实际焊料补录.xls","COMMERCIAL",org->factors.keySet(),new OriginalLinkedWorkbookParser.ImportContext("210",units));
    assertThat(parsed.errors()).isEmpty();
    var rule=parsed.rules().stream().filter(r->r.identity().materialCode().equals("301030020")).findFirst().orElseThrow();
    assertThat(rule.factors().values()).extracting(OriginalLinkedWorkbookParser.FactorInput::canonicalKey)
        .containsExactlyInAnyOrder("AVG|1#CU","AVG|1#SN","AVG|1#AG","AVG|1#ZN","AVG|1#MN");
    var saved=calculation.context().financial();
    var full=new FactorPriceSnapshot(saved.scope(),saved.batchId(),saved.revision(),saved.fileName(),saved.fileSha256(),saved.contentHash(),factors,saved.schemeCodes(),saved.publishedBy(),saved.publishedAt(),saved.publicationNote(),saved.cuBands());
    var base=new PriceFactorContext("301030020",calculation.context().months(),full,null);
    var calculator=new OriginalLinkedRuleCalculator();var p0=calculator.calculate(rule,base);
    assertThat(p0.priceExcludingTax()).isCloseTo(new BigDecimal("3365.873080060361864602"),within(new BigDecimal("1e-18")));
    var customer=base.withCustomerMaterials(new CustomerMaterialPrices(new BigDecimal("105000"),new BigDecimal("24000"),new BigDecimal("15000000"),null,null,null));
    var p1=calculator.calculate(rule,customer);
    var expected=new BigDecimal("15").multiply(new BigDecimal(".372"))
        .add(new BigDecimal("24").subtract(factors.get("AVG|1#ZN").price()).multiply(new BigDecimal(".33")))
        .add(new BigDecimal("15000").subtract(factors.get("AVG|1#AG").price()).multiply(new BigDecimal(".26")))
        .divide(new BigDecimal("1.13"),MathContext.DECIMAL128);
    assertThat(p1.priceExcludingTax().subtract(p0.priceExcludingTax())).isCloseTo(expected,within(new BigDecimal("1e-27")));
    var report=new LinkedHashMap<String,Object>();report.put("passed",true);report.put("sourceSha256",hash);report.put("parsedRows",parsed.rules().size());
    report.put("boundFactors",rule.factors());report.put("baselineScheme",full.scope().schemeCode());report.put("baselinePrice",p0.priceExcludingTax());
    report.put("simulatedActualPrice",p1.priceExcludingTax());report.put("simulatedDifference",expected);report.put("productionWrites",false);
    Files.writeString(evidence.resolve("actual-source-refresh-verification.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    assertThat(PriceLinkedImportFileDigest.sha256(Files.readAllBytes(file))).isEqualTo(hash);
    var fresh=json.readTree(inputs.resolve("current-refresh-inputs.json").toFile());
    assertThat(fresh.path("monthly")).isEmpty();
    var byScheme=new TreeMap<String,Map<String,FactorPriceSnapshot.FactorValue>>();
    for(var row:fresh.path("factorPrices")) {
      var refs=new ArrayList<FactorPriceSnapshot.Source>();
      for(var ref:fresh.path("factorSources"))if(ref.path("factor_monthly_price_id").asLong()==row.path("id").asLong())
        refs.add(new FactorPriceSnapshot.Source(ref.path("id").asLong(),text(ref,"source_sheet_name"),text(ref,"source_cell_ref"),text(ref,"source_value_text"),text(ref,"source_unit"),text(ref,"source_formula_expr"),text(ref,"source_number_format"),text(ref,"conversion_basis")));
      assertThat(refs).isNotEmpty();
      var value=new FactorPriceSnapshot.FactorValue(row.path("factor_identity_id").asLong(),row.path("id").asLong(),text(row,"factor_name"),text(row,"short_name"),new BigDecimal(row.path("price").asText()),text(row,"unit"),row.path("tax_included").asInt()==1,refs);
      byScheme.computeIfAbsent(text(row,"scheme_code"),ignored->new LinkedHashMap<>()).put(text(row,"canonical_factor_key"),value);
    }
    var bands=new ArrayList<FactorPriceSnapshot.CuBand>();
    byScheme.forEach((scheme,values)->bands.add(new FactorPriceSnapshot.CuBand(scheme,values.get("AVG|1#CU").price().movePointRight(3),values)));
    var actualSnapshot=new FactorPriceSnapshot(saved.scope(),saved.batchId(),saved.revision(),saved.fileName(),saved.fileSha256(),saved.contentHash(),byScheme.get("CU_90000"),saved.schemeCodes(),saved.publishedBy(),saved.publishedAt(),saved.publicationNote(),bands);
    var refreshedContext=new PriceFactorContext("301030020",base.months(),actualSnapshot,null);
    var refreshedResult=calculator.calculate(rule,refreshedContext);
    var actualPrice=fresh.path("price").get(0);
    assertThat(refreshedResult.priceExcludingTax()).isCloseTo(new BigDecimal(actualPrice.path("unit_price").asText()),within(new BigDecimal("1e-18")));
    var keys=rule.factors().values().stream().map(OriginalLinkedWorkbookParser.FactorInput::canonicalKey).collect(java.util.stream.Collectors.toSet());
    var refreshed=new FinanceEffectivePriceService.Calculation(refreshedContext.evidenceForFactors(keys),refreshedResult,null,rule);
    var plan=new LinkedHashMap<String,Object>();
    plan.put("materialCode","301030020");plan.put("originalId",8144);plan.put("financePriceId",17245);plan.put("financeBatchId",58);plan.put("financeRevision",2);
    plan.put("sourceSha256",hash);plan.put("oldCalculationSha256",PriceLinkedImportFileDigest.sha256(actualPrice.path("calculation_json").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    plan.put("inputFingerprint",actualPrice.path("input_fingerprint").asText());plan.put("unitPrice",actualPrice.path("unit_price").asText());
    plan.put("newCalculationJson",json.writeValueAsString(refreshed));
    Files.writeString(inputs.resolve("refresh-plan.private.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(plan));
  }

  private static String text(com.fasterxml.jackson.databind.JsonNode row,String key) {
    return row.path(key).isNull()?null:row.path(key).asText();
  }
}
