package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.registry.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.monthly.MonthlyPrivateFactorInputMapper;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real private-factor SQL/registry differential; does not claim full source-adapter acceptance. */
@Tag("integration")
class PreparedPrivateFactorMysqlTest {
  static final MySQLContainer<?> MYSQL=new MySQLContainer<>(DockerImageName.parse(System.getProperty("mpp.mysql.image","mysql:8.4")).asCompatibleSubstituteFor("mysql")).withDatabaseName("mpp_private_factors").withUsername("root").withPassword("mpp-private-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static java.util.concurrent.atomic.AtomicInteger statements=new java.util.concurrent.atomic.AtomicInteger();static MonthlyPrivateFactorInputMapper captures;
  static JdbcTemplate jdbc;static FactorMonthlyPriceMapper prices;static FactorIdentityMapper identities;static PriceVariableMapper variables;static AtomicLong sequence=new AtomicLong(1000);static ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  @BeforeAll static void start() throws Exception {
    MYSQL.start();var original=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var ds=new org.springframework.jdbc.datasource.DelegatingDataSource(original){@Override public java.sql.Connection getConnection() throws java.sql.SQLException{var c=super.getConnection();return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{if(method.getName().equals("prepareStatement"))statements.incrementAndGet();try{return method.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}});}};jdbc=new JdbcTemplate(ds);assertThat(jdbc.queryForObject("SELECT @@version",String.class)).startsWith(System.getProperty("mpp.mysql.expected-version","8.4"));new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/legacy-range-source-schema.sql")).execute(ds);
    var config=new com.baomidou.mybatisplus.core.MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.addMapper(MonthlyPrivateFactorInputMapper.class);config.addMapper(FactorMonthlyPriceMapper.class);config.addMapper(FactorIdentityMapper.class);config.addMapper(PriceVariableMapper.class);var factory=new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);var sql=new SqlSessionTemplate(factory.getObject());captures=sql.getMapper(MonthlyPrivateFactorInputMapper.class);prices=sql.getMapper(FactorMonthlyPriceMapper.class);identities=sql.getMapper(FactorIdentityMapper.class);variables=sql.getMapper(PriceVariableMapper.class);
  }
  @AfterAll static void stop(){MYSQL.stop();}
  static long identity(){long id=sequence.incrementAndGet();jdbc.update("INSERT INTO lp_factor_identity(id,business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES(?,'COMMERCIAL',?,'private steel','private steel','AVG','AVG|PRIVATE_STEEL')",id,"factor-"+id);return id;}
  static long price(long identity,String month,String value,String status,String scheme){long id=sequence.incrementAndGet();jdbc.update("INSERT INTO lp_factor_monthly_price(id,factor_identity_id,price_month,price,status,scheme_code,revision_no,unit,source_upload_batch_id) VALUES(?,?,?,?,?,?,?,'CNY/KG',204)",id,identity,month,value==null?null:new BigDecimal(value),status,scheme,(int)id);return id;}
  static PriceVariable variable(long identity,Long snapshot){String code="mpp_private_"+identity;String params="{\"factorIdentityId\":"+identity+(snapshot==null?"":",\"factorMonthlyPriceId\":"+snapshot)+"}";jdbc.update("INSERT INTO lp_price_variable(variable_code,variable_name,source_type,status,resolver_kind,resolver_params,created_at,updated_at) VALUES(?,?,'FINANCE','active','FINANCE',?,NOW(),NOW())",code,code,params);return variables.selectOne(Wrappers.<PriceVariable>lambdaQuery().eq(PriceVariable::getVariableCode,code));}
  Optional<BigDecimal> original(PriceVariable variable){
    var query=mock(FinanceFactorVariableQuery.class);when(query.query(any(),any())).thenReturn(Optional.empty());var registry=new FactorVariableRegistryImpl(variables,query,json,RowLocalPlaceholderRegistry.captured(List.of(),json));registry.setFactorMonthlyPriceMapper(prices);var context=new VariableContext().linkedItem(PreparedFormulaInputsTest.rule()).materialCode("RAW-001").pricingMonth("2026-10").financeQuoteBase(true);return registry.resolve(variable.getVariableCode(),context);
  }
  com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse prepared(long identity,PriceVariable variable){
    // Exact existing SQL predicate/order; preserve raw returned scheme/status without normalizing them.
    var captured=captures.read("COMMERCIAL","2026-10",Set.of(identity));
    var inputs=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(List.of(variable),List.of(),List.of()),new PreparedFormulaInputs.Financial(PreparedFormulaInputsTest.financial(),Map.of(identity,identities.selectById(identity)),Set.of(PriceFactorContext.COPPER),captured));var rule=PreparedFormulaInputsTest.rule();rule.setFormulaExpr("["+variable.getVariableCode()+"]");rule.setTaxIncluded(1);var fixture=new PreparedFormulaInputsTest();var result=fixture.service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),inputs);verifyNoInteractions(fixture.variables,fixture.rules,fixture.bindings,fixture.placeholders,fixture.identities,fixture.prices,fixture.finance,fixture.scrapReferences,fixture.scrapPrices);return result;
  }
  @ParameterizedTest @ValueSource(strings={"active","Áctivé","ACTIVE "})
  void sqlMatchedStatusAndSchemeDoNotDisappearDuringCapturedCalculation(String status){
    long identity=identity();price(identity,"2026-09","7","ACTIVE","LEGACY");long chosen=price(identity,"2026-10","11",status,"léGACY");price(identity,"2026-11","99","ACTIVE","LEGACY");var variable=variable(identity,null);assertThat(original(variable)).contains(new BigDecimal("11.0000000000000000"));var result=prepared(identity,variable);assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("11");assertThat(result.getInputSnapshotJson()).contains(status,"léGACY",Long.toString(chosen));assertThat(jdbc.queryForObject("SELECT status FROM lp_factor_monthly_price WHERE id=?",String.class,chosen)).isEqualTo(status);
  }
  @Test void sqlMatchedNullHeadCannotBeDroppedInFavorOfAnOlderNonNullMonth(){
    long identity=identity();price(identity,"2026-09","7","ACTIVE","LEGACY");price(identity,"2026-10",null,"active","legacy");var variable=variable(identity,null);assertThat(original(variable)).isEmpty();var result=prepared(identity,variable);assertThat(result.getError()).isNotBlank();assertThat(result.getResult()).isNull();
  }
  @Test void snapshotFallbackKeepsItsOriginalStrictJavaStatusCheck(){
    long identity=identity();long snapshot=price(identity,"2026-10","11","active","LEGACY");price(identity,"2026-10",null,"ACTIVE","LEGACY");var variable=variable(identity,snapshot);assertThat(original(variable)).isEmpty();var result=prepared(identity,variable);assertThat(result.getError()).isNotBlank();assertThat(result.getResult()).isNull();
  }
  @Test void thousandDistinctPrivateIdentitiesUseFourBatchedSqlReadsWithoutWrites(){
    var ids=new LinkedHashSet<Long>();for(int i=0;i<1000;i++){long id=identity();price(id,"2026-10","11","ACTIVE","LEGACY");ids.add(id);}long before=jdbc.queryForObject("SELECT COUNT(*) FROM lp_factor_monthly_price",Long.class);statements.set(0);var result=captures.read("COMMERCIAL","2026-10",ids);int count=statements.get();assertThat(result).hasSize(1000);assertThat(count).isEqualTo(4);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_factor_monthly_price",Long.class)).isEqualTo(before);
  }

}
