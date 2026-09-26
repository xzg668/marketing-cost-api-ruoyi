package com.sanhua.marketingcost.bom;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class U9BomLineKeyTest {

  @Test
  void sourceAndHierarchyShareBusinessIdentityAcrossDifferentImportIds() {
    BomU9Source source = new BomU9Source();
    source.setId(12L);
    source.setImportBatchId("old-batch");
    source.setPriceOrgCode("210");
    source.setParentMaterialNo("P");
    source.setChildMaterialNo("C");
    source.setBomPurpose("主制造");
    source.setChildSeq(20);
    source.setBomVersion("V1");
    source.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    source.setEffectiveTo(LocalDate.of(9999, 12, 31));

    BomRawHierarchy hierarchy = new BomRawHierarchy();
    hierarchy.setId(999L);
    hierarchy.setSourceU9RowId(123456L);
    hierarchy.setSourceImportBatchId("new-batch");
    hierarchy.setPriceOrgCode("210");
    hierarchy.setParentCode("P");
    hierarchy.setMaterialCode("C");
    hierarchy.setBomPurpose("主制造");
    hierarchy.setSortSeq(20);
    hierarchy.setBomVersion("V1");
    hierarchy.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    hierarchy.setEffectiveTo(LocalDate.of(9999, 12, 31));

    U9BomLineKey key = U9BomLineKey.from(source);
    assertThat(U9BomLineKey.from(hierarchy)).isEqualTo(key);
    assertThat(U9BomLineKey.from(hierarchy).token()).isEqualTo(key.token());
    assertThat(key.occurrenceToken("branch-a")).isNotEqualTo(key.occurrenceToken("branch-b"));
  }
}
