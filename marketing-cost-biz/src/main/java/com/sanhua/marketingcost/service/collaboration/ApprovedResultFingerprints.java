package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** 可复用BOM、包装和裸品U9本体上下文的稳定SHA-256指纹。 */
@Component
public class ApprovedResultFingerprints {

  private static final String VERSION = "QCBP07-FP-V1";

  public String fullBom(List<QuoteBomSupplementDetail> rows) {
    List<String> values = new ArrayList<>();
    for (QuoteBomSupplementDetail row : safe(rows)) {
      values.add(canonical(
          row.getLineNo(), row.getLevel(), row.getParentCode(), row.getMaterialCode(),
          row.getMaterialName(), row.getMaterialSpec(), row.getMaterialModel(), row.getDrawingNo(),
          row.getShapeAttr(), row.getMainCategoryCode(), row.getSourceCategory(),
          row.getCostElementCode(), row.getBomPurpose(), row.getBomVersion(),
          decimal(row.getQtyPerParent()), decimal(row.getQtyPerTop()),
          decimal(row.getParentBaseQty()), row.getUnit(), row.getPath(), row.getSortSeq()));
    }
    values.sort(Comparator.naturalOrder());
    return digest("FULL_BOM", values);
  }

  public String packageStructure(List<PackageComponentStructureLineDto> rows) {
    List<String> values = new ArrayList<>();
    for (PackageComponentStructureLineDto row : safe(rows)) {
      values.add(canonical(
          row.lineNo(), row.referenceFinishedCode(), row.sourceTopProductCode(),
          row.packageParentCode(), row.packageParentName(), row.packageParentSpec(),
          row.packageParentModel(), row.packageParentDrawingNo(), row.packageParentShapeAttr(),
          row.packageParentMainCategoryCode(), row.packageParentUnit(),
          decimal(row.packageQtyPerParent()), decimal(row.packageQtyPerTop()),
          decimal(row.packageParentBaseQty()), row.packageSourcePath(),
          row.packageChildCode(), row.packageChildName(), row.packageChildSpec(),
          row.packageChildModel(), row.packageChildDrawingNo(), row.packageChildShapeAttr(),
          row.packageChildMainCategoryCode(), row.packageChildUnit(),
          decimal(row.childQtyPerParent()), decimal(row.childQtyPerTop()),
          decimal(row.childParentBaseQty()), row.childSourceParentCode(),
          row.childSourcePath(), row.childSourceSortSeq()));
    }
    values.sort(Comparator.naturalOrder());
    return digest("BARE_PACKAGE", values);
  }

  public String u9Structure(List<QuoteBomSourceLineDto> rows) {
    List<String> values = new ArrayList<>();
    for (QuoteBomSourceLineDto row : safe(rows)) {
      values.add(canonical(
          row.lineNo(), row.level(), row.topProductCode(), row.parentCode(), row.materialCode(),
          row.materialName(), row.materialSpec(), row.materialModel(), row.drawingNo(),
          row.shapeAttr(), row.mainCategoryCode(), row.unit(), row.sourceCategory(),
          row.costElementCode(), row.bomPurpose(), row.bomVersion(),
          decimal(row.qtyPerParent()), decimal(row.qtyPerTop()), decimal(row.parentBaseQty()),
          row.path(), row.sortSeq(), row.priceOrgCode(), row.materialOrganizationCode(),
          row.childType(), row.alternativeGroupKey()));
    }
    values.sort(Comparator.naturalOrder());
    return digest("U9_STRUCTURE", values);
  }

  public String u9Context(
      QuoteCollaborationScanContext context, CurrentU9BomResult u9) {
    if (context == null || u9 == null || u9.status() != CurrentU9BomResult.Status.AVAILABLE) {
      throw new IllegalArgumentException("裸品U9本体上下文必须是当前有效BOM");
    }
    return digest("U9_CONTEXT", List.of(canonical(
        context.productCode(), context.priceOrgCode(), context.materialOrganizationCode(),
        u9.source(), u9.bomVersion(), u9.lineCount(), u9.structureFingerprint())));
  }

  private String digest(String type, List<String> values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update((VERSION + "|" + type + "|").getBytes(StandardCharsets.UTF_8));
      for (String value : values) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
      }
      return HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前JVM不支持SHA-256", exception);
    }
  }

  private String canonical(Object... values) {
    StringBuilder result = new StringBuilder();
    for (Object value : values) {
      String text = value == null ? "" : value.toString().trim();
      result.append(text.length()).append(':').append(text).append('|');
    }
    return result.toString();
  }

  private String decimal(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }
}
