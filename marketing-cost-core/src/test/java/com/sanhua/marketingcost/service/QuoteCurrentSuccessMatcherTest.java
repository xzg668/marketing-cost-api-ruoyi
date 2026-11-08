package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class QuoteCurrentSuccessMatcherTest {

  private OaFormItem item;
  private QuoteCostingWorkspace workspace;
  private QuoteCostRunVersion version;

  @BeforeEach
  void setUpCurrentSuccess() {
    item = new OaFormItem();
    item.setId(11L);
    item.setConfirmedCostVersionId(88L);
    workspace = new QuoteCostingWorkspace();
    workspace.setWorkspaceStatus("SUCCESS");
    workspace.setCurrentCostVersionId(88L);
    workspace.setInputFingerprint("FP-1");
    workspace.setLastSuccessInputFingerprint("FP-1");
    workspace.setSourceRevision("REV-1");
    workspace.setLastSuccessSourceRevision("REV-1");
    version = new QuoteCostRunVersion();
    version.setId(88L);
    version.setOaNo("OA-1");
    version.setOaFormItemId(11L);
    version.setPricingMonth("2026-08");
    version.setInputFingerprint("FP-1");
    version.setSourceRevision("REV-1");
    version.setAlgorithmVersion("COST_V1");
    version.setStatus("SUCCESS");
  }

  @Test
  void matchesOnlyWhenBusinessInputAndAlgorithmVersionAreBothCurrent() {
    assertThat(matches("COST_V1", "REV-1")).isTrue();
    assertThat(matches("COST_V2", "REV-1")).isFalse();
    assertThat(matches(null, "REV-1")).isFalse();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "REV-2"})
  void doesNotReuseSuccessWhenCurrentInputsAreUnknownOrChanged(String sourceRevision) {
    assertThat(matches("COST_V1", sourceRevision)).isFalse();
  }

  @Test
  void doesNotReuseCostFrozenFromAnotherSourceRevision() {
    version.setSourceRevision("REV-0");
    assertThat(matches("COST_V1", "REV-1")).isFalse();
  }

  @Test
  void doesNotReuseCostWhenWorkspaceHasNotCaughtUpWithCurrentInputs() {
    workspace.setSourceRevision("REV-0");
    assertThat(matches("COST_V1", "REV-1")).isFalse();
  }

  @Test
  void doesNotReuseCostWithoutMatchingLastSuccessfulInputs() {
    workspace.setLastSuccessSourceRevision(null);
    assertThat(matches("COST_V1", "REV-1")).isFalse();
    workspace.setLastSuccessSourceRevision("REV-0");
    assertThat(matches("COST_V1", "REV-1")).isFalse();
  }

  @Test void savedResultIsIndependentOfCurrentPublicInputsAndAlgorithm() {
    assertThat(saved()).isTrue();
    assertThat(matches("COST_V2", "NEW-PRICES-AND-RATES")).isFalse();
    assertThat(saved()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"QUEUED", "RUNNING", "WAIT_PRICE", "SYSTEM_FAILED", "STALE"})
  void unfinishedRegenerationCannotBeSubmitted(String status) {
    workspace.setWorkspaceStatus(status);
    assertThat(saved()).isFalse();
  }

  @Test void savedResultStillRequiresTheSameCompletedVersionAndProduct() {
    workspace.setLastSuccessInputFingerprint("OLD");
    assertThat(saved()).isFalse();
    workspace.setLastSuccessInputFingerprint("FP-1");
    item.setConfirmedCostVersionId(89L);
    assertThat(saved()).isFalse();
    item.setConfirmedCostVersionId(88L);
    version.setOaFormItemId(12L);
    assertThat(saved()).isFalse();
    version.setOaFormItemId(11L);
    version.setPricingMonth("2026-09");
    assertThat(saved()).isFalse();
  }

  private boolean saved() {
    return QuoteCurrentSuccessMatcher.matchesSaved("OA-1", 11L, "2026-08", item, workspace, version);
  }

  private boolean matches(String algorithmVersion, String sourceRevision) {
    return QuoteCurrentSuccessMatcher.matches(
        "OA-1", 11L, "2026-08", item, workspace, version, algorithmVersion, sourceRevision);
  }
}
