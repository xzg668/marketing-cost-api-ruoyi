package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class LinkedPriceRuleVersionsTest {
  static PriceLinkedItem row(long id,Integer revision,int day,String supplier) {
    var row=new PriceLinkedItem();row.setId(id);row.setRuleRevision(revision);row.setCreatedAt(LocalDateTime.of(2026,10,day,0,0));row.setSupplierCode(supplier);row.setUnit("件");return row;
  }
  @Test void allSixMixedRevisionOrdersSelectTheSameLatestImportInsteadOfFormingACycle() {
    var a=row(1,1,3,"S1");var b=row(2,2,1,"S1");var c=row(3,null,2,"S1");
    for(var permutation:List.of(List.of(a,b,c),List.of(a,c,b),List.of(b,a,c),List.of(b,c,a),List.of(c,a,b),List.of(c,b,a)))
      assertThat(LinkedPriceRuleVersions.current(permutation)).containsExactly(a);
  }
  @Test void fullyVersionedIdentityStillUsesRevisionBeforeImportTime() {
    var newerImport=row(1,1,3,"S1");var newerRevision=row(2,2,1,"S1");
    assertThat(LinkedPriceRuleVersions.current(List.of(newerImport,newerRevision))).containsExactly(newerRevision);
  }
  @Test void versionPolicyIsChosenPerIdentityAndDistinctSuppliersStayDistinct() {
    var a=row(1,1,3,"S1");var b=row(2,2,1,"S1");var c=row(3,null,2,"S1");var d=row(4,1,5,"S2");var e=row(5,2,4,"S2");
    assertThat(LinkedPriceRuleVersions.current(List.of(e,c,b,d,a))).containsExactly(e,a);
  }
  @Test void tiesUseRealRowIdAndDoNotMutateHistoricalRows() {
    var first=row(1,null,1,"S1");var last=row(2,null,1,"S1");var rows=new ArrayList<>(List.of(last,first));
    assertThat(LinkedPriceRuleVersions.current(rows)).containsExactly(last);assertThat(rows).containsExactly(last,first);assertThat(first.getRuleRevision()).isNull();
  }
}
