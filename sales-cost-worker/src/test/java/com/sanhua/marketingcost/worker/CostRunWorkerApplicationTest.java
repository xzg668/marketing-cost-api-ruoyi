package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.config.ApprovedResultReuseProperties;
import com.sanhua.marketingcost.config.ElectronicDrawingBomProperties;
import com.sanhua.marketingcost.service.collaboration.CollaborationPortalLinkService;
import com.sanhua.marketingcost.service.impl.BusinessUnitRepriceLockGuardImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceBatchServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceConfirmServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceOperationServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceQueryServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceStartServiceImpl;
import com.sanhua.marketingcost.service.impl.QuoteBatchCostRunServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
  void workerDoesNotCreateServletOnlyCollaborationPortalLinkService() {
    new ApplicationContextRunner()
        .withUserConfiguration(CollaborationPortalLinkService.class)
        .run(context -> assertThat(context.getBeansOfType(CollaborationPortalLinkService.class))
            .isEmpty());
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
                  MonthlyRepriceBatchServiceImpl.class,
                  MonthlyRepriceConfirmServiceImpl.class,
                  MonthlyRepriceOperationServiceImpl.class,
                  MonthlyRepriceQueryServiceImpl.class,
                  MonthlyRepriceStartServiceImpl.class,
                  QuoteBatchCostRunServiceImpl.class);
        });
  }

  @Test
  void workerApplicationImportsCollaborationConfigurationProperties() {
    Import annotation = CostRunWorkerApplication.class.getAnnotation(Import.class);

    assertThat(annotation.value())
        .contains(
            ElectronicDrawingBomProperties.class,
            ApprovedResultReuseProperties.class);
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
