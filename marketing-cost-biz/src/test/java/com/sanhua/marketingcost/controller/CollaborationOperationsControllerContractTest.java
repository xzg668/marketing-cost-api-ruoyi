package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

class CollaborationOperationsControllerContractTest {
  @Test
  void everyMutationRequiresDedicatedCompensationPermission() throws Exception {
    Method release = CollaborationOperationsController.class.getMethod(
        "releaseOutbox", Long.class,
        com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.CompensationRequest.class);
    Method invalidate = CollaborationOperationsController.class.getMethod(
        "invalidateApprovedResult", Long.class,
        com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.CompensationRequest.class);
    for (Method method : new Method[] { release, invalidate }) {
      assertThat(method.getAnnotation(PostMapping.class)).isNotNull();
      assertThat(method.getAnnotation(PreAuthorize.class).value())
          .contains("collaboration:operations:compensate");
    }
  }
}
