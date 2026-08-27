package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

class CollaborationOperationsControllerContractTest {
  @Test
  void approvedResultInvalidationRequiresDedicatedCompensationPermission() throws Exception {
    Method invalidate = CollaborationOperationsController.class.getMethod(
        "invalidateApprovedResult", Long.class,
        com.sanhua.marketingcost.dto.collaboration.CollaborationOperationsResponse.CompensationRequest.class);
    assertThat(invalidate.getAnnotation(PostMapping.class)).isNotNull();
    assertThat(invalidate.getAnnotation(PreAuthorize.class).value())
        .contains("collaboration:operations:compensate");
  }
}
