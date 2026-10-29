package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MonthlyRepriceControllerTest {

  @ParameterizedTest
  @ValueSource(strings = {"/batches", "/batches/EXISTING/expand",
      "/batches/EXISTING/prepare-linked-price", "/batches/EXISTING/confirm",
      "/batches/EXISTING/cancel", "/batches/EXISTING/retry-failed"})
  void retiredWriteRoutesDoNotInvokeOldWorkflowEvenWithOldSwitchEnabled(String path) {
    new ApplicationContextRunner().withPropertyValues("monthly.reprice.enabled=true")
        .withBean(MonthlyRepriceController.class)
        .run(context -> {
          var controller = context.getBean(MonthlyRepriceController.class);
          var mvc = MockMvcBuilders.standaloneSetup(controller).build();
          mvc.perform(post("/api/v1/monthly-reprice" + path)
                  .contentType(MediaType.APPLICATION_JSON).content("{\"pricingMonth\":\"2026-10\"}"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.code").value(400))
              .andExpect(jsonPath("$.msg").value("旧月度调价操作已停用，历史结果仍可查询"));
          for (var method : MonthlyRepriceController.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class) != null)
              assertThat(method.getAnnotation(PreAuthorize.class).value())
                  .contains("BU_DIRECTOR", "price:monthly-reprice:operate");
          }
        });
  }
}
