package com.sanhua.marketingcost.service.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.impl.SupplierSupplyRatioWorkbookParserImpl;
import com.sanhua.marketingcost.service.pricing.SupplierPriceCandidate;
import com.sanhua.marketingcost.service.pricing.SupplierPriceScope;
import com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Explicit local acceptance with a user-supplied workbook; never accesses a formal database. */
class SupplierRatioShellWorkbookLocalAcceptance extends SupplierSupplyRatioIncrementalIntegrationTest {
  @Test void providedShellImportsActualRatiosAndInlineHighestWithoutChangingTheOriginalFile() throws Exception {
    var source = Path.of(System.getProperty("supplier.ratio.shell-workbook"));
    byte[] original = Files.readAllBytes(source);
    var parsed = new SupplierSupplyRatioWorkbookParserImpl().parse(
        new ByteArrayInputStream(original), source.getFileName().toString(), null);
    assertThat(parsed.getErrors()).isEmpty();
    assertThat(parsed.getRows()).hasSize(2);
    var first = parsed.getRows().getFirst();
    var second = parsed.getRows().getLast();
    var mapper = sql.getMapper(SupplierSupplyRatioMapper.class);
    var selector = new SupplierPriceSelectionService(mapper, null);
    var date = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingDate();
    var scope = new SupplierPriceScope("COMMERCIAL", "LOCAL-SHELL", 1L,
        date.toString().substring(0, 7), date, "210", first.getMaterialCode(), "FIXED");
    var candidates = List.of(
        new SupplierPriceCandidate("A", first.getSupplierCode(), first.getSupplierName(), new BigDecimal("30"), null, null),
        new SupplierPriceCandidate("B", second.getSupplierCode(), second.getSupplierName(), new BigDecimal("10"), null, null));
    var imported = imports.importExcel(new ByteArrayInputStream(original), source.getFileName().toString(), "COMMERCIAL", "local-acceptance", null);
    assertThat(imported.getInsertedRows()).isEqualTo(2);
    var originalRows = mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SupplierSupplyRatio>().eq("is_active", 1));
    assertThat(originalRows).hasSize(2);
    for (var input : parsed.getRows()) assertThat(originalRows).anySatisfy(row -> {
      assertThat(row.getSupplierCode()).isEqualTo(input.getSupplierCode());
      assertThat(row.getSupplyRatio()).isEqualByComparingTo(input.getSupplyRatio());
    });
    byte[] numeric = changed(original, "5%", .95);
    imports.importExcel(new ByteArrayInputStream(numeric), "shell-numeric.xlsx", "COMMERCIAL", "local-acceptance", null);
    var actual = selector.select(scope, candidates);
    assertThat(actual.rule()).isEqualTo("RATIO_MAX");
    assertThat(actual.candidate().unitPrice()).isEqualByComparingTo("10");
    byte[] configured = changed(original, "无供货比率，取最高价", "");
    imports.importExcel(new ByteArrayInputStream(configured), "shell-highest.xlsx", "COMMERCIAL", "local-acceptance", null);
    var active = mapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SupplierSupplyRatio>().eq("is_active", 1));
    assertThat(active).hasSize(2).allSatisfy(row -> assertThat(row.getSupplyRatio()).isNull());
    assertThat(active).anySatisfy(row -> assertThat(row.getSelectionRule()).isEqualTo("无供货比率，取最高价"));
    var highest = selector.select(scope, candidates);
    assertThat(highest.rule()).isEqualTo("CONFIGURED_HIGH");
    assertThat(highest.candidate().unitPrice()).isEqualByComparingTo("30");
    assertThat(highest.review()).isNull();
    var repeated = imports.importExcel(new ByteArrayInputStream(configured), "shell-highest.xlsx", "COMMERCIAL", "local-acceptance", null);
    assertThat(repeated.getUnchangedRows()).isEqualTo(2);
    assertThat(repeated.getInsertedRows()).isZero();
    imports.importExcel(new ByteArrayInputStream(numeric), "shell-actual.xlsx", "COMMERCIAL", "local-acceptance", null);
    assertThat(selector.select(scope, candidates).candidate().unitPrice()).isEqualByComparingTo("10");
    assertThat(Files.readAllBytes(source)).isEqualTo(original);
    var output = Path.of(System.getProperty("supplier.ratio.shell-output"));
    Files.createDirectories(output.toAbsolutePath().getParent());
    Files.writeString(output, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
        "passed", true, "isolatedLocalOnly", true, "actualFileParsedAndImported", true,
        "sourceSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)),
        "actualRatio", "5%/95%", "ratioPrice", 10, "configuredHighestPrice", 30,
        "idempotent", true, "originalFileUnchanged", true, "actualRatiosRestoredPrice", 10)));
  }

  private byte[] changed(byte[] original, String first, Object second) throws Exception {
    try (var book = new XSSFWorkbook(new ByteArrayInputStream(original)); var output = new ByteArrayOutputStream()) {
      var sheet = book.getSheet("外壳");
      sheet.getRow(1).getCell(9).setCellValue(first);
      if (second instanceof Double value) sheet.getRow(2).getCell(9).setCellValue(value);
      else sheet.getRow(2).getCell(9).setCellValue((String) second);
      book.write(output);
      return output.toByteArray();
    }
  }
}
