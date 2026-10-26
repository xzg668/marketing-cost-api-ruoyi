package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceLinkMapper;
import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceStorageMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Same database, bounded acquisition for short independent monthly transactions. */
public final class MonthlyPriceDatabase implements AutoCloseable {
  static final Duration ACQUISITION_BUDGET=Duration.ofMillis(250);
  private final HikariDataSource pool;
  private final MonthlyPriceStorage storage;
  private final MonthlyPriceDocumentCodec codec;
  private final DataSourceTransactionManager transactions;
  public MonthlyPriceDatabase(HikariDataSource application,MonthlyPriceDocumentCodec codec) throws Exception {
    this.codec=Objects.requireNonNull(codec);
    var config=new HikariConfig();
    config.setJdbcUrl(boundedUrl(application.getJdbcUrl()));config.setUsername(application.getUsername());config.setPassword(application.getPassword());
    if(application.getDriverClassName()!=null)config.setDriverClassName(application.getDriverClassName());
    var properties=new Properties();properties.putAll(application.getDataSourceProperties());properties.setProperty("connectTimeout","250");properties.setProperty("socketTimeout","2000");config.setDataSourceProperties(properties);
    config.setPoolName(application.getPoolName()+"-monthly");config.setMaximumPoolSize(Math.min(8,application.getMaximumPoolSize()));config.setMinimumIdle(0);
    config.setConnectionTimeout(ACQUISITION_BUDGET.toMillis());config.setValidationTimeout(250);config.setInitializationFailTimeout(-1);config.setAutoCommit(true);
    if(application.getConnectionInitSql()!=null)config.setConnectionInitSql(application.getConnectionInitSql());
    if(application.getCatalog()!=null)config.setCatalog(application.getCatalog());
    pool=new HikariDataSource(config);
    try{
      var configuration=new org.apache.ibatis.session.Configuration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(MonthlyPriceStorageMapper.class);configuration.addMapper(MonthlyPriceLinkMapper.class);
      var factory=new SqlSessionFactoryBean();factory.setDataSource(pool);factory.setConfiguration(configuration);
      var sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
      storage=new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class));transactions=new DataSourceTransactionManager(pool);
    }catch(Exception constructionFailure){pool.close();throw constructionFailure;}
  }
  public MonthlyMaterialPriceService createService(MonthlyPriceCandidateResolver sources){return new MonthlyMaterialPriceService(storage,transactions,codec,sources,ACQUISITION_BUDGET);}
  HikariDataSource pool(){return pool;}
  @Override public void close(){pool.close();}

  private static String boundedUrl(String original){
    if(original==null || !original.startsWith("jdbc:mysql:"))throw new IllegalArgumentException("Monthly transactions require the configured MySQL JDBC identity");
    int split=original.indexOf('?');String base=split<0?original:original.substring(0,split);
    var parts=new ArrayList<String>();
    if(split>=0)for(String parameter:original.substring(split+1).split("&")){
      String key=parameter.split("=",2)[0];if(!parameter.isEmpty() && !key.equalsIgnoreCase("connectTimeout") && !key.equalsIgnoreCase("socketTimeout"))parts.add(parameter);
    }
    parts.add("connectTimeout=250");parts.add("socketTimeout=2000");return base+"?"+String.join("&",parts);
  }
}
