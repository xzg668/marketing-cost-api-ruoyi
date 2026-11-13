package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Row;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomNodeResponse;
import java.io.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class JjbBomOrderTest {
  private final JjbBomOrder order = new JjbBomOrder(List.of());

  private Row part(long id, String code, String path) {
    return Row.builder().key("PART:" + id).kind("PART").section("ORDINARY_PARTS").code(code)
        .name(code).partItemIds(List.of(id)).paths(path == null ? List.of() : List.of(path))
        .quantity("2").unit("只").unitPrice("1.234567").amount("2.469134").build();
  }

  @Test void savedOccurrenceSequenceAndDepthTakePrecedenceOverMaterialCodeAndInsertionOrder() {
    var parent = part(1, "900", "/TOP/900@20@010/");
    var child = part(2, "100", "TOP/900@20@010/100@10@020");
    var first = part(3, "999", "TOP/999@2@030");
    var next = part(4, "001", "TOP/001@100@010");
    var result = order.apply(List.of(child, next, parent, first));
    assertThat(result).containsExactly(first, parent, child, next);
    assertThat(result).containsExactlyInAnyOrder(child, next, parent, first);
  }

  @Test void repeatedMaterialCodesInDifferentBranchesAreSeparateOccurrences() {
    var a = part(1, "SAME", "TOP/A@10/SAME@30");
    var b = part(2, "SAME", "TOP/B@20/SAME@10");
    var c = part(3, "CHILD", "TOP/A@10/CHILD@40");
    assertThat(order.apply(List.of(b, c, a))).containsExactly(a, c, b);
  }

  @Test void manufacturingExplanationsFollowTheirParentAndSavedChildPathsStayInsideTheBranch() {
    var parent = part(1, "MAKE", "TOP/MAKE@10");
    var inline = Row.builder().key("MAKE:1").kind("EXPLANATION").section("ORDINARY_PARTS")
        .partItemIds(List.of(1L)).name("制造依据").build();
    var child = part(2, "RAW", "TOP/MAKE@10/RAW@20").toBuilder().kind("EXPLANATION").build();
    var next = part(3, "NEXT", "TOP/NEXT@20");
    assertThat(order.apply(List.of(next, parent, inline, child))).containsExactly(parent, inline, child, next);
  }

  @Test void savedTreeSequenceOverridesEncodedSequenceAndDoesNotUseCurrentTree() throws Exception {
    var first = part(1, "A", "TOP/A@20");
    var last = part(2, "B", "TOP/B@10");
    var json = new ObjectMapper();
    var nodes = List.of(
        json.readValue("{\"nodePath\":\"/TOP/A@20/\",\"sortSeq\":1}", QuoteEffectiveBomNodeResponse.class),
        json.readValue("{\"nodePath\":\"/TOP/B@10/\",\"sortSeq\":2}", QuoteEffectiveBomNodeResponse.class));
    assertThat(new JjbBomOrder(nodes).apply(List.of(last, first))).containsExactly(first, last);
  }

  @Test void householdUsesFirstOccurrenceAndFinancialSectionsAndSubtotalsKeepTheirPositions() {
    var a = part(1, "A", "TOP/A@10").toBuilder().kind("HOUSEHOLD").section("HOUSEHOLD")
        .paths(List.of("TOP/Z@100/A@10", "TOP/A@10")).build();
    var b = part(2, "B", "TOP/B@20").toBuilder().kind("HOUSEHOLD").section("HOUSEHOLD").build();
    var fee = Row.builder().key("FEE").kind("COST").section("THREE_EXPENSES").amount("1.000000").build();
    var subtotal = Row.builder().key("SUBTOTAL").kind("SUBTOTAL").section("HOUSEHOLD").amount("4.938268").build();
    var separate = part(3, "SEPARATE", "TOP/SEPARATE@1").toBuilder().section("SEPARATE_PARTS").build();
    assertThat(order.apply(List.of(fee, b, a, subtotal, separate))).containsExactly(fee, a, b, subtotal, separate);
  }

  @Test void legacyRowsWithoutSavedPathsKeepTheirOrderAndAllOriginalFields() {
    var a = part(1, "Z", null);
    var b = part(2, "A", null);
    var before = List.of(a, b);
    assertThat(order.apply(before)).containsExactly(a, b);
    assertThat(before).containsExactly(a, b);
  }

  @Test void actualExcelUsesTheSameBranchOrderAndKeepsNumericAmounts() throws Exception {
    var b = part(2, "B", "TOP/B@20");
    var a = part(1, "A", "TOP/A@10");
    var base = JjbExcelRendererTest.model(0, 0, 0);
    var rows = new ArrayList<>(List.of(b, a)); rows.addAll(base.rows());
    var model = new JjbReadModel(base.identity(), base.header(), order.apply(rows), base.monthlyPrices(), base.totals(), base.warnings());
    var output = new ByteArrayOutputStream();
    new JjbExcelRenderer().write(model, output);
    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
      var codes = new ArrayList<String>();
      for (var row : workbook.getSheetAt(0)) if (row.getCell(0) != null && List.of("A", "B").contains(row.getCell(0).toString())) {
        codes.add(row.getCell(0).toString());
        assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(2.469134);
      }
      assertThat(codes).containsExactly("A", "B");
    }
  }
}
