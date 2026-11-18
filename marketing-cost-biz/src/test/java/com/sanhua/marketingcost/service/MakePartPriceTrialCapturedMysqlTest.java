package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.containers.MySQLContainer;

/** Private captured prices are supplied explicitly and are never committed as test data. */
@EnabledIfSystemProperty(named="make.trial.capture.dir",matches=".+")
class MakePartPriceTrialCapturedMysqlTest {
  @Test void realMysqlRowsReplayBothCopperPricesAndRetainEveryStoredValue() throws Exception {
    var directory=Path.of(System.getProperty("make.trial.capture.dir"));
    var json=new ObjectMapper().findAndRegisterModules();var codec=new MonthlyPriceDocumentCodec(json);
    List<LinkedHashMap<String,Object>> captured=json.readValue(directory.resolve("captured-rows.private.json").toFile(),new TypeReference<>(){});
    try(var mysql=new MySQLContainer<>("mysql:8.0.36")) {
      mysql.start();var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
      var jdbc=new JdbcTemplate(ds);jdbc.execute(Files.readString(directory.resolve("make-table.before.sql")));
      var columns=new ArrayList<>(captured.getFirst().keySet());columns.remove("source_costing_row_key");
      String insert="INSERT INTO lp_make_part_price_calc_row (`"+String.join("`,`",columns)+"`) VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
      for(var row:captured)jdbc.update(insert,columns.stream().map(row::get).toArray());
      var input=jdbc.query("SELECT * FROM lp_make_part_price_calc_row ORDER BY id",new BeanPropertyRowMapper<>(MakePartPriceCalcRow.class));
      for(var row:new MakePartFinancialPrices(codec).project(input)) jdbc.update("UPDATE lp_make_part_price_calc_row SET price_scenario_type=?,raw_unit_price=?,scrap_unit_price=?,cost_price=?,parent_total_cost_price=?,remark=? WHERE id=?",
          row.getPriceScenarioType(),row.getRawUnitPrice(),row.getScrapUnitPrice(),row.getCostPrice(),row.getParentTotalCostPrice(),row.getRemark(),row.getId());
      var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
      configuration.setEnvironment(new Environment("trial",new JdbcTransactionFactory(),ds));configuration.addMapper(MakePartPriceCalcRowMapper.class);
      var factory=new MybatisSqlSessionFactoryBuilder().build(configuration);
      var before=jdbc.queryForList("SELECT * FROM lp_make_part_price_calc_row ORDER BY id");
      MakePartPriceTrialServiceTest.authenticate("COMMERCIAL");
      try(var session=factory.openSession(true)) {
        var service=new MakePartPriceTrialService(session.getMapper(MakePartPriceCalcRowMapper.class),codec,
            MonthlyScenarioTestSupport.create(null),new MakePartPriceTraceSource(codec));
        int complete=0;
        for(var row:input) {
          var base=service.calculate(row.getId(),null);
          assertThat(base.trialAvailable()).as("row %s: %s",row.getId(),base.message()).isTrue();
          var trial=service.calculate(row.getId(),new MakePartPriceTrialService.Request(base.basis(),new BigDecimal("120000")));
          assertThat(trial.lines()).isNotEmpty();
          if ("203240168".equals(row.getParentMaterialNo())) {
            assertThat(base.originalPrice()).isEqualTo("1.86645971");assertThat(trial.trialPrice()).isEqualTo("2.45847024");
            assertThat(trial.lines().getFirst().scrap().trialCalculation()).contains("120");
          }
          complete++;
        }
        assertThat(complete).isEqualTo(579);
        assertThat(jdbc.queryForList("SELECT * FROM lp_make_part_price_calc_row ORDER BY id")).isEqualTo(before);
      } finally {SecurityContextHolder.clearContext();}
    }
  }
}
