package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BomEffectiveTreePruner")
class BomEffectiveTreePrunerTest {

  @Test
  @DisplayName("prune：同路径取最新生效版本，孤儿子树不进入有效树")
  void pruneKeepsLatestConnectedTreeOnly() {
    List<BomRawHierarchy> pruned = BomEffectiveTreePruner.prune(
        List.of(
            row(1L, "P", "P", 0, "/P/", "F001", "2025-01-01"),
            row(2L, "P", "A", 1, "/P/A/", "F001", "2025-01-01"),
            row(3L, "P", "A", 1, "/P/A/", "F002", "2025-06-01"),
            row(4L, "P", "B", 2, "/P/A/B/", "F001", "2025-01-01"),
            row(5L, "P", "X", 2, "/P/MISSING/X/", "F001", "2025-01-01")),
        "P");

    assertThat(pruned).extracting(BomRawHierarchy::getPath)
        .containsExactly("/P/", "/P/A/", "/P/A/B/");
    assertThat(pruned.get(1).getBomVersion()).isEqualTo("F002");
  }

  private static BomRawHierarchy row(
      Long id, String top, String material, Integer level, String path, String version, String from) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setTopProductCode(top);
    row.setParentCode(level == 0 ? top : null);
    row.setMaterialCode(material);
    row.setLevel(level);
    row.setPath(path);
    row.setBomVersion(version);
    row.setEffectiveFrom(LocalDate.parse(from));
    row.setEffectiveTo(LocalDate.parse("9999-12-31"));
    return row;
  }
}
