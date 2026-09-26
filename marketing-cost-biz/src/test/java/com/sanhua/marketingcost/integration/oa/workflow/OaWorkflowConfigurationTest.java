package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.oa.auth.OaAccessTokenProvider;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OaWorkflowConfigurationTest {
  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withUserConfiguration(Binding.class).withBean(ObjectMapper.class, ObjectMapper::new)
      .withBean(OaAuthProperties.class).withBean(OaAccessTokenProvider.class)
      .withBean(OaWorkflowProperties.class).withBean(OaWorkflowClient.class);

  @Test void defaultStartupDoesNotRequireOaCredentialsOrEnableDebug() {
    context.run(application -> {
      assertThat(application).hasNotFailed();
      assertThat(application.getBean(OaWorkflowProperties.class).isDebugEnabled()).isFalse();
    });
  }

  @Test void bindsWorkflowSeparatelyFromSharedAuth() {
    context.withPropertyValues("integration.oa-workflow.base-url=http://127.0.0.1:9001/OA",
        "integration.oa-workflow.debug-enabled=true", "integration.oa-workflow.read-timeout-ms=1200")
        .run(application -> {
          assertThat(application).hasNotFailed();
          var properties = application.getBean(OaWorkflowProperties.class);
          assertThat(properties.isDebugEnabled()).isTrue();
          assertThat(properties.resolveBaseUrl(null)).isEqualTo("http://127.0.0.1:9001/OA");
          assertThat(properties.getReadTimeoutMs()).isEqualTo(1200);
        });
  }

  @Test void tokenInBaseUrlAndInvalidTimeoutCannotStart() {
    context.withPropertyValues("integration.oa-workflow.base-url=http://127.0.0.1/OA?access_token=secret")
        .run(application -> assertThat(application).hasFailed());
    context.withPropertyValues("integration.oa-workflow.read-timeout-ms=0")
        .run(application -> assertThat(application).hasFailed());
  }

  @Configuration(proxyBeanMethods = false) @EnableConfigurationProperties
  static class Binding {}
}
