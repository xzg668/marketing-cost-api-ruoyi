package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.config.AsyncConfig;
import com.sanhua.marketingcost.config.CacheConfig;
import com.sanhua.marketingcost.config.CostRunExecutionProperties;
import com.sanhua.marketingcost.config.LinkedParserProperties;
import com.sanhua.marketingcost.config.MetaObjectHandlerConfig;
import com.sanhua.marketingcost.config.MybatisPlusConfig;
import com.sanhua.marketingcost.service.impl.BusinessUnitRepriceLockGuardImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceBatchServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceConfirmServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceOperationServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceQueryServiceImpl;
import com.sanhua.marketingcost.service.impl.MonthlyRepriceStartServiceImpl;
import java.util.Map;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = SecurityAutoConfiguration.class)
@ComponentScan(
    basePackages = {
        "com.sanhua.marketingcost.worker",
        "com.sanhua.marketingcost.service",
        "com.sanhua.marketingcost.integration",
        "com.sanhua.marketingcost.formula"
    },
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {
            BusinessUnitRepriceLockGuardImpl.class,
            MonthlyRepriceBatchServiceImpl.class,
            MonthlyRepriceConfirmServiceImpl.class,
            MonthlyRepriceOperationServiceImpl.class,
            MonthlyRepriceQueryServiceImpl.class,
            MonthlyRepriceStartServiceImpl.class
        }))
@MapperScan("com.sanhua.marketingcost.mapper")
@EnableScheduling
@Import({
    AsyncConfig.class,
    CacheConfig.class,
    CostRunExecutionProperties.class,
    LinkedParserProperties.class,
    MetaObjectHandlerConfig.class,
    MybatisPlusConfig.class
})
public class CostRunWorkerApplication {

  public static void main(String[] args) {
    buildApplication().run(args);
  }

  static SpringApplication buildApplication() {
    return new SpringApplicationBuilder(CostRunWorkerApplication.class)
        .web(WebApplicationType.NONE)
        .properties(Map.of("spring.application.name", "sales-cost-worker"))
        .build();
  }
}
