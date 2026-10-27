package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.integration.drawing.DrawingVerificationProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** 使用 worker 实际 YAML 验证环境变量绑定，避免 API 与 worker 启用行为不同。 */
class DrawingVerificationConfigurationTest {
  @Test
  void workerBindsConfiguredPdfEndpointAndTotalDeadline() {
    var environment = environment().withProperty("DRAWING_PDF_ENABLED", "true")
        .withProperty("DRAWING_PDF_BASE_URL", "http://127.0.0.1:18087")
        .withProperty("DRAWING_PDF_VERIFY_PATH", "/controlled/verify")
        .withProperty("DRAWING_PDF_CONNECT_TIMEOUT", "2s")
        .withProperty("DRAWING_PDF_REQUEST_TIMEOUT", "3m");
    var properties = bind(environment);
    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.endpoint().toString()).isEqualTo("http://127.0.0.1:18087/controlled/verify");
    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofMinutes(3));
    assertThat(properties.isUseMaterialMaster()).isTrue();
  }

  @Test
  void workerCanUseTheQuoteDrawingOnlyWhenExplicitlyConfigured() {
    assertThat(bind(environment()).isUseMaterialMaster()).isTrue();
    assertThat(bind(environment().withProperty("DRAWING_PDF_USE_MATERIAL_MASTER", "false"))
        .isUseMaterialMaster()).isFalse();
  }

  @Test
  void retiredExcelModeCannotEnablePdfAcquisition() {
    var properties = bind(environment().withProperty("QUOTE_ELECTRONIC_DRAWING_MODE", "HTTP"));
    assertThat(properties.isEnabled()).isFalse();
    assertThat(properties.endpoint().toString()).isEqualTo("http://10.10.206.115:8011/api/drawings/verify");
  }

  private MockEnvironment environment() {
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    var environment = new MockEnvironment();
    environment.getPropertySources().addLast(new PropertiesPropertySource("worker-yaml", yaml.getObject()));
    return environment;
  }

  private DrawingVerificationProperties bind(MockEnvironment environment) {
    return Binder.get(environment).bind("quote.drawing-verification", Bindable.of(DrawingVerificationProperties.class)).get();
  }
}
