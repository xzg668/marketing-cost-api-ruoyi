package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.reprice.MonthlyCostComparisonScope.Selected;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyCostComparisonSelectionFileTest {
  private static Selected selected(long id,String customer,String month,String unknown) {
    return new Selected("COMMERCIAL","000A",unknown==null?"OA_HEADER_CUSTOMER":null,customer,unknown,
        month,"OA-"+id,id,id,null,null,null,id,1,"SUCCESS",null);
  }
  private static Set<Path> temporarySelections() throws Exception {
    try(var files=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return files.filter(p->p.getFileName().toString().startsWith("monthly-cost-comparison-selection-"))
          .collect(java.util.stream.Collectors.toSet());
    }
  }
  @Test void boundedPagesKeepBothMonthsAndEveryUnmatchedIdentityExactlyOnce() throws Exception {
    var before=temporarySelections();
    var a=selected(1,"A","2026-10",null);var b=selected(2,"A","2026-11",null);
    var c=selected(3,"B","2026-11",null);var d=selected(4,null,"2026-10","LEGACY-OA4");
    try(var references=new MonthlyCostComparisonSelectionFile(new ObjectMapper())) {
      for(var value:List.of(a,b,c,d))references.append(value);
      assertThat(references.readPage(1,1)).containsExactly(a,b);
      assertThat(references.readPage(2,1)).containsExactly(c);
      assertThat(references.readPage(3,1)).containsExactly(d);
      assertThat(references.readPage(4,1)).isEmpty();
      assertThatThrownBy(()->references.append(a)).hasMessageContaining("已结束写入");
    }
    assertThat(temporarySelections()).isEqualTo(before);
  }
  @Test void emptySelectionAndEarlyFailureBothCloseTheirPrivateFiles() throws Exception {
    var before=temporarySelections();
    try(var empty=new MonthlyCostComparisonSelectionFile(new ObjectMapper())) {
      assertThatThrownBy(()->empty.readPage(2,1)).hasMessageContaining("顺序");
      assertThat(empty.readPage(1,50)).isEmpty();
    }
    try(var unfinished=new MonthlyCostComparisonSelectionFile(new ObjectMapper())) {
      unfinished.append(selected(1,"A","2026-10",null));
    }
    assertThat(temporarySelections()).isEqualTo(before);
  }
}
