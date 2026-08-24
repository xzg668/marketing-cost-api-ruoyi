package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import org.junit.jupiter.api.Test;

class QuoteCurrentSuccessMatcherTest {

  @Test
  void matchesOnlyWhenBusinessInputAndAlgorithmVersionAreBothCurrent() {
    OaFormItem item = new OaFormItem();
    item.setId(11L);
    item.setConfirmedCostVersionId(88L);
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setWorkspaceStatus("SUCCESS");
    workspace.setCurrentCostVersionId(88L);
    workspace.setInputFingerprint("FP-1");
    workspace.setLastSuccessInputFingerprint("FP-1");
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(88L);
    version.setOaNo("OA-1");
    version.setOaFormItemId(11L);
    version.setPricingMonth("2026-08");
    version.setInputFingerprint("FP-1");
    version.setAlgorithmVersion("COST_V1");
    version.setStatus("SUCCESS");

    assertThat(matches(item, workspace, version, "COST_V1")).isTrue();
    assertThat(matches(item, workspace, version, "COST_V2")).isFalse();
    assertThat(matches(item, workspace, version, null)).isFalse();
  }

  private boolean matches(
      OaFormItem item,
      QuoteCostingWorkspace workspace,
      QuoteCostRunVersion version,
      String algorithmVersion) {
    return QuoteCurrentSuccessMatcher.matches(
        "OA-1", 11L, "2026-08", item, workspace, version, algorithmVersion);
  }
}
