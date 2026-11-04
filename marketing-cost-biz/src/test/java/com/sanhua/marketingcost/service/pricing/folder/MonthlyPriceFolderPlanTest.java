package com.sanhua.marketingcost.service.pricing.folder;

import com.sanhua.marketingcost.service.pricing.OriginalRuleSignature;

import static com.sanhua.marketingcost.service.pricing.folder.MonthlyPriceFolderModel.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.impl.OriginalRangeRuleCalculator;
import com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyPriceFolderPlanTest {
  private final MonthlyPriceFolderPlan plan = new MonthlyPriceFolderPlan();
  private final MaterialPriceRuleKey key = new MaterialPriceRuleKey("COMMERCIAL", "210", "001234", "S01", "供应商", "SPEC", "只");
  private Candidate row(String id, Long file, String signature, String state) {
    return new Candidate(id, file, "文件" + id + ".xlsx", "主表", 2, "LINKED", key, signature, "公式" + signature,
        file == null ? 12L : null, state);
  }
  @Test void differentRulesAreAllRetainedRegardlessOfUploadOrder() {
    var a = row("a", 1L, "1.05", "UPLOADED"); var b = row("b", 2L, "1.092", "UPLOADED");
    var group = plan.build(List.of(a, b)).getFirst();
    assertThat(group.status()).isEqualTo("READY"); assertThat(group.variantCount()).isEqualTo(2);
    assertThat(plan.build(List.of(b, a))).containsExactly(group);
    assertThat(plan.representatives(group.candidates())).containsExactlyInAnyOrder(a, b);
  }
  @Test void duplicatesMergeButStagingIsNotPublication() {
    var a = row("a", 1L, "same", "UPLOADED"); var b = row("b", 2L, "same", "UPLOADED");
    var ready = plan.build(List.of(a, b)).getFirst();
    assertThat(ready.status()).isEqualTo("READY"); assertThat(ready.candidates()).hasSize(2);
    assertThat(plan.build(List.of(a, b, row("stored", null, "same", "STAGED"))).getFirst().status()).isEqualTo("PREPARED");
    assertThat(plan.build(List.of(a, b, row("stored", null, "same", "PUBLISHED"))).getFirst().status()).isEqualTo("IMPORTED");
  }
  @Test void priorPublishedAndNewRulesBothBelongToTheNextPublication() {
    var old = row("old", null, "old", "PUBLISHED"); var uploaded = row("new", 1L, "new", "UPLOADED");
    var staged = row("stage", null, "new", "STAGED");
    var group = plan.build(List.of(old, uploaded, staged)).getFirst();
    assertThat(group.status()).isEqualTo("PREPARED"); assertThat(group.variantCount()).isEqualTo(2);
    assertThat(plan.representatives(group.candidates())).containsExactlyInAnyOrder(old, staged);
  }
  @Test void organizationAndSupplierAreNeverCollapsedByMaterialCode() {
    var a = row("a", 1L, "same", "UPLOADED");
    var board = new Candidate("b", 2L, "板换.xlsx", "主表", 2, "LINKED",
        new MaterialPriceRuleKey("COMMERCIAL", "220", "001234", "S01", "供应商", "SPEC", "只"), "same", "formula", null, "UPLOADED");
    var supplier = new Candidate("c", 3L, "另一供方.xlsx", "主表", 2, "LINKED",
        new MaterialPriceRuleKey("COMMERCIAL", "210", "001234", "S02", "供应商二", "SPEC", "只"), "same", "formula", null, "UPLOADED");
    assertThat(plan.build(List.of(a, board, supplier))).hasSize(3).allMatch(g -> g.status().equals("READY"));
  }
}
