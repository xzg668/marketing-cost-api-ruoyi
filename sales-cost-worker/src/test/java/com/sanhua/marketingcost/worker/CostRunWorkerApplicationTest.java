package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.integration.drawing.DrawingVerificationProperties;
import com.sanhua.marketingcost.config.PasswordEncodingConfig;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectorySyncScheduler;
import com.sanhua.marketingcost.service.impl.BusinessUnitRepriceLockGuardImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceQueryServiceImpl;
import com.sanhua.marketingcost.service.impl.QuoteBatchCostRunServiceImpl;
import com.sanhua.marketingcost.service.technicaldata.EffectiveTechnicalDataQueryServiceImpl;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

class CostRunWorkerApplicationTest {

  @Test
  void workerApplicationDoesNotStartWebServer() {
    assertThat(CostRunWorkerApplication.buildApplication().getWebApplicationType())
        .isEqualTo(WebApplicationType.NONE);
  }

  @Test
  void workerApplicationScansOnlyWorkerServiceAndFormulaPackages() {
    ComponentScan annotation =
        CostRunWorkerApplication.class.getAnnotation(ComponentScan.class);

    assertThat(annotation.basePackages())
        .containsExactly(
            "com.sanhua.marketingcost.worker",
            "com.sanhua.marketingcost.service",
            "com.sanhua.marketingcost.integration",
            "com.sanhua.marketingcost.formula");
    assertThat(annotation.excludeFilters())
        .anySatisfy(filter -> {
          assertThat(filter.type()).isEqualTo(FilterType.ASSIGNABLE_TYPE);
          assertThat(filter.classes())
              .contains(
                  BusinessUnitRepriceLockGuardImpl.class,
                  MonthlyRepriceQueryServiceImpl.class,
                  com.sanhua.marketingcost.service.ingest.QuoteCostResultHistoryServiceImpl.class,
                  QuoteBatchCostRunServiceImpl.class,
                  com.sanhua.marketingcost.integration.oa.OaWorkflowNotificationController.class,
                  com.sanhua.marketingcost.integration.oa.OaWorkflowNotificationHandler.class,
                  com.sanhua.marketingcost.integration.oa.OaWorkflowNotificationService.class);
        })
        .anySatisfy(filter -> {
          assertThat(filter.type()).isEqualTo(FilterType.REGEX);
          assertThat(filter.pattern())
              .contains("com\\.sanhua\\.marketingcost\\.service\\.technicaldata\\..*");
        });
  }

  @Test
  void workerApplicationImportsSharedBomConfigurationProperties() {
    Import annotation = CostRunWorkerApplication.class.getAnnotation(Import.class);

    assertThat(annotation.value())
        .contains(
            DrawingVerificationProperties.class,
            com.sanhua.marketingcost.config.MonthlyRepriceProperties.class,
            EffectiveTechnicalDataQueryServiceImpl.class,
            PasswordEncodingConfig.class,
            TechnicalDataVersionContentCodec.class);
  }

  @Test
  void workerPreparationRegistersTheRealPendingSourceReaderDependency() {
    // Use the worker's explicit registration list: technicaldata package scanning is excluded.
    try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context.registerBean(org.springframework.jdbc.core.JdbcTemplate.class,
          () -> org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class));
      context.registerBean(com.fasterxml.jackson.databind.ObjectMapper.class,
          () -> new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
      context.registerBean(com.sanhua.marketingcost.service.technicaldata.TechnicalDataSharedModuleQuery.class,
          () -> org.mockito.Mockito.mock(com.sanhua.marketingcost.service.technicaldata.TechnicalDataSharedModuleQuery.class));
      context.registerBean(com.sanhua.marketingcost.service.technicaldata.TechnicalDataQuoteSourceReader.class,
          () -> org.mockito.Mockito.mock(com.sanhua.marketingcost.service.technicaldata.TechnicalDataQuoteSourceReader.class));
      for (var type : QuotePreparationWorkerConfiguration.class.getAnnotation(Import.class).value()) {
        if (type == com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingProductQuery.class
            || type == com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingSourceReads.class)
          context.register(type);
      }
      context.refresh();
      assertThat(context.getBean(com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingProductQuery.class)).isNotNull();
      assertThat(context.getBean(com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingSourceReads.class)).isNotNull();
    }
  }

  @Test
  void oaDirectorySchedulerRunsOnlyInServletApiProcess() {
    ConditionalOnWebApplication annotation =
        OaPersonDirectorySyncScheduler.class.getAnnotation(ConditionalOnWebApplication.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.type()).isEqualTo(ConditionalOnWebApplication.Type.SERVLET);
  }

  @Test
  void workerUsesSameEffectiveBomMainChainAsApi() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    var properties = yaml.getObject();

    assertThat(properties).isNotNull();
    assertThat(properties).doesNotContainKey("cost.quote-bom.effective-bom.enabled");
  }
}
