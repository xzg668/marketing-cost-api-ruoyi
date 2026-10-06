package com.sanhua.marketingcost.integration.oa.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sanhua.marketingcost.integration.oa.OaIntegrationProperties;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryProperties;
import com.sanhua.marketingcost.security.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class OaOAuthServiceContextTest {
  @Test
  void nonWebWorkerDoesNotRequireLoginDependencies() {
    new ApplicationContextRunner()
        .withUserConfiguration(OaOAuthService.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(OaOAuthService.class);
          assertThat(context).doesNotHaveBean(JwtUtils.class);
        });
  }

  @Test
  void servletApiStillCreatesOAuthService() {
    new WebApplicationContextRunner()
        .withUserConfiguration(OaOAuthService.class)
        .withBean(OaOAuthClient.class, () -> mock(OaOAuthClient.class))
        .withBean(OaIntegrationProperties.class, () -> mock(OaIntegrationProperties.class))
        .withBean(OaPersonDirectoryProperties.class, () -> mock(OaPersonDirectoryProperties.class))
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withBean(JwtUtils.class, () -> mock(JwtUtils.class))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(OaOAuthService.class);
        });
  }
}
