package com.sanhua.marketingcost.service.pricing.monthly;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Both API and worker assemble the same module; the costing entry points switch in MPP-12. */
@Configuration(proxyBeanMethods=false)
public class MonthlyPriceConfiguration {
  @Bean(destroyMethod="close")
  MonthlyPriceDatabase monthlyPriceDatabase(DataSource application,MonthlyPriceDocumentCodec codec) throws Exception {
    return new MonthlyPriceDatabase(application.unwrap(HikariDataSource.class),codec);
  }
  @Bean
  MonthlyMaterialPrices monthlyMaterialPrices(MonthlyPriceDatabase database,MonthlyPriceSources sources) {
    return database.createService(sources);
  }
  @Bean
  MonthlyAdoptionExecutor monthlyAdoptionExecutor(MonthlyMaterialPrices prices) {
    return new MonthlyAdoptionExecutor(prices);
  }
}
