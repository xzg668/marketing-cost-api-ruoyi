package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeCandidate;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIdentity;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;

final class Qba07FormalBomTestSupport {

  static final String GROUP_MAIN = "GROUP-MAIN";

  private Qba07FormalBomTestSupport() {
  }

  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        BomRawHierarchy.class);
  }

  static QuoteBomReadContext context() {
    return new QuoteBomReadContext(
        "OA-QBA-07",
        701L,
        "TOP",
        "2026-07",
        "210",
        "COMMERCIAL",
        "COMMERCIAL",
        "主制造",
        LocalDate.of(2026, 7, 30));
  }

  static List<BomRawHierarchy> alternativeTree() {
    return List.of(
        row(1L, "TOP", "TOP", 0, "/TOP/", null, null),
        row(2L, "PARENT", "TOP", 1, "/TOP/PARENT/", null, null),
        row(
            3L,
            "STD",
            "PARENT",
            2,
            "/TOP/PARENT/STD/",
            GROUP_MAIN,
            "STANDARD"),
        row(
            4L,
            "STD-RAW",
            "STD",
            3,
            "/TOP/PARENT/STD/STD-RAW/",
            null,
            null),
        row(
            5L,
            "ALT",
            "PARENT",
            2,
            "/TOP/PARENT/ALT/",
            GROUP_MAIN,
            "ALTERNATIVE"),
        row(
            6L,
            "ALT-RAW",
            "ALT",
            3,
            "/TOP/PARENT/ALT/ALT-RAW/",
            null,
            null));
  }

  static BomAlternativeGroup mainGroup(List<BomRawHierarchy> rows) {
    return group(
        GROUP_MAIN,
        "PARENT",
        rows.stream().filter(row -> "STD".equals(row.getMaterialCode())).findFirst().orElseThrow(),
        rows.stream().filter(row -> "ALT".equals(row.getMaterialCode())).findFirst().orElseThrow());
  }

  static BomAlternativeGroup group(
      String key,
      String parent,
      BomRawHierarchy standard,
      BomRawHierarchy alternative) {
    return new BomAlternativeGroup(
        new BomAlternativeGroupIdentity(
            "210",
            "TOP",
            "PARENT-FP",
            parent,
            "主制造",
            "V1",
            LocalDate.of(2026, 1, 1),
            null,
            10,
            "010"),
        key,
        List.of(candidate(standard, BomChildType.STANDARD), candidate(alternative, BomChildType.ALTERNATIVE)));
  }

  static QuoteBomAlternativeSelectionResult selection(
      String groupKey, String standard, String selected, BomChildType selectedType) {
    return new QuoteBomAlternativeSelectionResult(
        "SEL-1",
        groupKey,
        standard,
        selected,
        selectedType,
        selectedType == BomChildType.STANDARD
            ? QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD
            : QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE,
        1,
        QuoteBomAlternativeSelection.STATUS_ACTIVE,
        false,
        false,
        true,
        "IMPORT-1",
        "BUILD-1");
  }

  static BomRawHierarchy row(
      Long id,
      String materialCode,
      String parentCode,
      int level,
      String path,
      String groupKey,
      String childType) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setPriceOrgCode("210");
    row.setTopProductCode("TOP");
    row.setParentCode(parentCode);
    row.setMaterialCode(materialCode);
    row.setMaterialName("名称-" + materialCode);
    row.setMaterialSpec("规格-" + materialCode);
    row.setShapeAttr(level < 2 ? "制造件" : "采购件");
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq(level);
    row.setQtyPerParent(BigDecimal.ONE);
    row.setQtyPerTop(BigDecimal.ONE);
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    row.setSourceType("U9");
    row.setSourceImportBatchId("IMPORT-1");
    row.setBuildBatchId("BUILD-1");
    row.setAlternativeGroupKey(groupKey);
    row.setChildType(childType);
    return row;
  }

  private static BomAlternativeCandidate candidate(
      BomRawHierarchy row, BomChildType childType) {
    return new BomAlternativeCandidate(
        row.getId(),
        row.getMaterialCode(),
        row.getMaterialName(),
        row.getMaterialSpec(),
        childType,
        row.getQtyPerParent(),
        row.getPath(),
        row.getSourceImportBatchId(),
        row.getBuildBatchId());
  }
}
