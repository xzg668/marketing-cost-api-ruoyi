package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-07 审核结果来源指纹")
class ApprovedResultFingerprintsTest {

  private final ApprovedResultFingerprints fingerprints = new ApprovedResultFingerprints();

  @Test
  @DisplayName("完整BOM指纹不受数据库返回顺序影响，但结构用量变化立即改变")
  void fullBomFingerprintIsOrderIndependentAndStructureSensitive() {
    QuoteBomSupplementDetail first = detail(1, "TOP", "RAW-1", "1.000");
    QuoteBomSupplementDetail second = detail(2, "TOP", "RAW-2", "2.000");

    String original = fingerprints.fullBom(List.of(first, second));
    String reordered = fingerprints.fullBom(List.of(second, first));
    second.setQtyPerParent(new BigDecimal("2.001"));
    String changed = fingerprints.fullBom(List.of(first, second));

    assertThat(original).hasSize(64).isEqualTo(reordered).isNotEqualTo(changed);
  }

  @Test
  @DisplayName("包装结构指纹包含父子关系与用量")
  void packageFingerprintIncludesRelationshipAndQuantity() {
    PackageComponentStructureLineDto first = packageLine("PACK", "BOX", "1");
    PackageComponentStructureLineDto changed = packageLine("PACK", "BOX", "2");

    assertThat(fingerprints.packageStructure(List.of(first)))
        .hasSize(64)
        .isNotEqualTo(fingerprints.packageStructure(List.of(changed)));
  }

  @Test
  @DisplayName("裸品U9上下文指纹不包含报价月份，但包含组织和当前BOM结构")
  void u9ContextCanReuseAcrossMonthsButDetectsStructureAndOrganizationChanges() {
    CurrentU9BomResult u9 = CurrentU9BomResult.available(
        "U9", "V6", "SYNC-1", 18, "f".repeat(64));
    String august = fingerprints.u9Context(context("2026-08", "210"), u9);
    String september = fingerprints.u9Context(context("2026-09", "210"), u9);
    String otherOrg = fingerprints.u9Context(context("2026-09", "220"), u9);
    String changedStructure = fingerprints.u9Context(
        context("2026-09", "210"),
        CurrentU9BomResult.available("U9", "V6", "SYNC-2", 18, "e".repeat(64)));

    assertThat(august).isEqualTo(september);
    assertThat(otherOrg).isNotEqualTo(august);
    assertThat(changedStructure).isNotEqualTo(august);
  }

  private QuoteBomSupplementDetail detail(
      int lineNo, String parent, String material, String quantity) {
    QuoteBomSupplementDetail row = new QuoteBomSupplementDetail();
    row.setLineNo(lineNo);
    row.setLevel(1);
    row.setParentCode(parent);
    row.setMaterialCode(material);
    row.setMaterialName(material);
    row.setQtyPerParent(new BigDecimal(quantity));
    row.setQtyPerTop(new BigDecimal(quantity));
    row.setParentBaseQty(BigDecimal.ONE);
    row.setUnit("件");
    row.setPath(parent + "/" + material);
    return row;
  }

  private PackageComponentStructureLineDto packageLine(
      String parent, String child, String quantity) {
    BigDecimal qty = new BigDecimal(quantity);
    return new PackageComponentStructureLineDto(
        1L, 2L, "FIN-1", "FIN-1", "2026-08", 1,
        parent, "包装组件", null, null, null, null, null, "套",
        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 3L, parent,
        child, "纸箱", null, null, null, null, null, "件",
        qty, qty, BigDecimal.ONE, 4L, parent, parent + "/" + child, 1);
  }

  private QuoteCollaborationScanContext context(String month, String org) {
    return new QuoteCollaborationScanContext(
        1L, 2L, "OA-1", month, "COMMERCIAL", "P-1", "产品", "规格", "型号",
        org, "COMMERCIAL", LocalDate.of(2026, 8, 13),
        LocalDateTime.of(2026, 8, 13, 10, 0));
  }
}
