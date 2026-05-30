package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.service.impl.BusinessUnitRepriceLockGuardImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceBatchServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceConfirmServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceOperationServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceQueryServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceStartServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

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
                  MonthlyRepriceBatchServiceImpl.class,
                  MonthlyRepriceConfirmServiceImpl.class,
                  MonthlyRepriceOperationServiceImpl.class,
                  MonthlyRepriceQueryServiceImpl.class,
                  MonthlyRepriceStartServiceImpl.class);
        });
  }
}
