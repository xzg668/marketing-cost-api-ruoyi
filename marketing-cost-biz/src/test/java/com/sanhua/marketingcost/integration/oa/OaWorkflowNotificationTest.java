package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaWorkflowNotificationTest {
  static ObjectNode event(String flow, String type, String... employees) {
    var root=new ObjectMapper().createObjectNode().put("requestId",flow).put("eventType",type).put("reason","回调验收意见");
    if (employees.length>0) { var list=root.putArray("employeeNos"); for(var employee:employees) list.add(employee); }
    return root;
  }
  @Test void flowIdentityAndLeadingZerosArePreserved() {
    var parsed=OaWorkflowNotification.parse(event("1137224760702033922","TECH_APPROVED","001002","001001"));
    assertThat(parsed.requestId()).isEqualTo("1137224760702033922");
    assertThat(parsed.employeeNos()).containsExactly("001001","001002");
  }
  @ParameterizedTest @ValueSource(strings={"TECHNICAL","TECH_APPROVED"})
  void requiresTechniciansForBothTechnicalEvents(String type) {
    assertThatThrownBy(()->OaWorkflowNotification.parse(event("WF",type))).hasMessageContaining("employeeNos");
    var numeric=event("WF",type);numeric.putArray("employeeNos").add(123);
    assertThatThrownBy(()->OaWorkflowNotification.parse(numeric)).hasMessageContaining("字符串");
    assertThatThrownBy(()->OaWorkflowNotification.parse(event("WF",type,"001","001"))).hasMessageContaining("重复");
  }
  @ParameterizedTest @ValueSource(strings={"COSTING","COMPLETED"})
  void documentEventsNeedNoTechnician(String type) {
    assertThat(OaWorkflowNotification.parse(event("WF",type)).employeeNos()).isEmpty();
    assertThatThrownBy(()->OaWorkflowNotification.parse(event("WF",type,"001"))).hasMessageContaining("按整单");
  }
  @ParameterizedTest @ValueSource(strings={"TECHNICAL","COSTING"})
  void returnsRequireAReason(String type) {
    var root=type.equals("TECHNICAL")?event("WF",type,"001"):event("WF",type);
    root.put("reason","");assertThatThrownBy(()->OaWorkflowNotification.parse(root)).hasMessageContaining("reason");
  }
  @Test void completionAllowsEmptyReasonAndRejectsOldContractAndTypo() {
    assertThat(OaWorkflowNotification.parse(event("WF","COMPLETED").put("reason","")).reason()).isNull();
    assertThatThrownBy(()->OaWorkflowNotification.parse(event("WF","COMPLETEN"))).hasMessageContaining("未知");
    assertThatThrownBy(()->OaWorkflowNotification.parse(event("WF","COMPLETED").put("workflowRequestId","other"))).hasMessageContaining("未定义");
  }
  @ParameterizedTest @ValueSource(strings={"TECHNICAL","TECH_APPROVED","COSTING","COMPLETED"})
  void integrationExamplesUseThePublicContract(String type) throws Exception {
    try(var input=getClass().getResourceAsStream("/fixtures/oa-workflow/"+type+".json")) {
      assertThat(OaWorkflowNotification.parse(new ObjectMapper().readTree(input)).eventType()).isEqualTo(type);
    }
  }
}
