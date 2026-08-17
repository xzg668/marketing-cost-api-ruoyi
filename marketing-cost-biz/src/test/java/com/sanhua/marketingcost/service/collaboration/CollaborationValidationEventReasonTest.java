package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductTaskStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-11 原技术任务校验失败HOLD事件原因")
class CollaborationValidationEventReasonTest {

  @Test
  void techTaskUpdatedContainsStructuredReasonsWithoutBomTree() {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(10L);
    task.setProductTaskNo("PT-10");
    task.setProductCode("P-1");
    task.setTaskVersion(5);
    task.setTaskStatus("TECH_VALIDATION_FAILED");
    task.setOriginalTechnicianUserId(601L);
    task.setCurrentAssigneeUserId(601L);
    task.setCurrentAssigneeName("王工");
    QuoteCollaborationTask master = new QuoteCollaborationTask();
    master.setCollaborationNo("COL-1");
    master.setOaNo("OA-1");
    QuoteCollaborationGap gap = new QuoteCollaborationGap();
    gap.setGapStatus("OPEN");
    gap.setReasonCode("ORPHAN_NODE");
    gap.setReasonMessage("节点找不到父节点：C1");
    gap.setBomNodeKey("C1");
    gap.setBomPath("/P-1/C-1/");

    var command = new CollaborationTransitionEventFactory().productTransition(task, master,
        ProductTaskStatus.BOM_IN_PROGRESS, ProductAction.FAIL_TECH_VALIDATION,
        CollaborationNextAction.FIX_VALIDATION_ERRORS, List.of(gap)).orElseThrow();

    assertThat(command.data().path("validationIssueCount").asInt()).isEqualTo(1);
    assertThat(command.data().path("validationIssues").get(0).path("code").asText())
        .isEqualTo("ORPHAN_NODE");
    assertThat(command.data().has("bomTree")).isFalse();
  }
}
