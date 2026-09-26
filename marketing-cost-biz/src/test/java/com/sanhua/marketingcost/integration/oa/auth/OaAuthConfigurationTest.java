package com.sanhua.marketingcost.integration.oa.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.oa.directory.HttpOaPersonDirectoryGateway;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OaAuthConfigurationTest {
  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withUserConfiguration(Binding.class)
      .withBean(ObjectMapper.class, ObjectMapper::new)
      .withBean(OaAuthProperties.class)
      .withBean(OaPersonDirectoryProperties.class)
      .withBean(OaAccessTokenProvider.class)
      .withBean(HttpOaPersonDirectoryGateway.class);

  @Test
  void disabledDirectoryCanStartWithoutExternalCredentials() {
    context.run(application -> assertThat(application).hasNotFailed());
  }

  @Test
  void enabledDirectoryRequiresSharedAuthConfiguration() {
    context.withPropertyValues(
        "integration.oa-person-directory.enabled=true",
        "integration.oa-person-directory.query-base-url=http://127.0.0.1:9001",
        "integration.oa-person-directory.detail-base-url=http://127.0.0.1:9001")
        .run(application -> assertThat(application).hasFailed());
  }

  @Test
  void bindsSharedAuthSeparatelyFromDirectorySettings() {
    context.withPropertyValues(
        "integration.oa-auth.base-url=http://127.0.0.1:9000",
        "integration.oa-auth.call-sys-code=CALLER",
        "integration.oa-auth.corp-id=CORP",
        "integration.oa-auth.app-key=KEY",
        "integration.oa-auth.app-secret=SECRET",
        "integration.oa-person-directory.enabled=true",
        "integration.oa-person-directory.query-base-url=http://127.0.0.1:9001",
        "integration.oa-person-directory.detail-base-url=http://127.0.0.1:9002")
        .run(application -> {
          assertThat(application).hasNotFailed().hasSingleBean(OaAccessTokenProvider.class);
          assertThat(application.getBean(OaAuthProperties.class).getBaseUrl())
              .isEqualTo("http://127.0.0.1:9000");
          assertThat(application.getBean(OaPersonDirectoryProperties.class).getQueryBaseUrl())
              .isEqualTo("http://127.0.0.1:9001");
        });
  }
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties
  static class Binding {}
}
