package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

/** Opt-in captured-data regression: inputs stay outside the source repository. */
@EnabledIfSystemProperty(named="make.capture.dir",matches=".+")
class MakePartFinancialPricesMysqlTest {
  @Test void capturedRowsRefreshAtomicallyAndRemainExactAfterMysqlDecimalRounding() throws Exception {
    var directory=Path.of(System.getProperty("make.capture.dir"));
    var json=new ObjectMapper().findAndRegisterModules();
    List<LinkedHashMap<String,Object>> captured=json.readValue(directory.resolve("captured-rows.private.json").toFile(),new TypeReference<>(){});
    try(var mysql=new MySQLContainer<>("mysql:8.0.36")) {
      mysql.start();
      var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
      var jdbc=new JdbcTemplate(ds);jdbc.execute(Files.readString(directory.resolve("make-table.before.sql")));
      var columns=new ArrayList<>(captured.getFirst().keySet());
      columns.remove("source_costing_row_key"); // MySQL derives this stored generated column.
      String insert="INSERT INTO lp_make_part_price_calc_row (`"+String.join("`,`",columns)+"`) VALUES ("+String.join(",",Collections.nCopies(columns.size(),"?"))+")";
      for(var row:captured)jdbc.update(insert,columns.stream().map(row::get).toArray());
      var mapper=new BeanPropertyRowMapper<>(MakePartPriceCalcRow.class);
      var before=jdbc.queryForList("SELECT * FROM lp_make_part_price_calc_row ORDER BY id");
      var input=jdbc.query("SELECT * FROM lp_make_part_price_calc_row ORDER BY id",mapper);
      var projection=new MakePartFinancialPrices(new MonthlyPriceDocumentCodec(json));
      var projected=projection.project(input);
      for(int i=0;i<input.size();i++)assertThat(projected.get(i).getStatus()).isEqualTo(input.get(i).getStatus());
      var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
      assertThatThrownBy(()->tx.executeWithoutResult(s->{write(jdbc,projected.getFirst());throw new IllegalStateException("rollback probe");})).hasMessageContaining("rollback probe");
      assertThat(jdbc.queryForList("SELECT * FROM lp_make_part_price_calc_row ORDER BY id")).isEqualTo(before);
      tx.executeWithoutResult(s->projected.forEach(row->write(jdbc,row)));
      var after=jdbc.queryForList("SELECT * FROM lp_make_part_price_calc_row ORDER BY id");
      var changed=Set.of("price_scenario_type","raw_unit_price","scrap_unit_price","cost_price","parent_total_cost_price","price_complete","remark");
      for(int i=0;i<before.size();i++)for(String column:before.get(i).keySet())
        if(!changed.contains(column))assertThat(after.get(i).get(column)).as("row %s column %s",i,column).isEqualTo(before.get(i).get(column));
      var reloaded=jdbc.query("SELECT * FROM lp_make_part_price_calc_row ORDER BY id",mapper);
      assertThat(reloaded.stream().filter(r->"203240168".equals(r.getParentMaterialNo())).toList()).isNotEmpty()
          .allSatisfy(r->assertThat(r.getCostPrice()).isEqualByComparingTo("1.86645971"));
      tx.executeWithoutResult(s->projection.project(reloaded).forEach(row->write(jdbc,row)));
      var repeated=jdbc.queryForList("SELECT * FROM lp_make_part_price_calc_row ORDER BY id");
      assertThat(repeated).hasSameSizeAs(after);
      for(int i=0;i<after.size();i++)for(String column:after.get(i).keySet())
        assertThat(repeated.get(i).get(column)).as("repeat row id %s column %s",after.get(i).get("id"),column).isEqualTo(after.get(i).get(column));
      assertThat(projected).hasSameSizeAs(captured);
    }
  }
  private void write(JdbcTemplate jdbc,MakePartPriceCalcRow row) {
    assertThat(jdbc.update("UPDATE lp_make_part_price_calc_row SET price_scenario_type=?,raw_unit_price=?,scrap_unit_price=?,cost_price=?,parent_total_cost_price=?,price_complete=?,remark=? WHERE id=?",
        row.getPriceScenarioType(),row.getRawUnitPrice(),row.getScrapUnitPrice(),row.getCostPrice(),row.getParentTotalCostPrice(),row.getPriceComplete(),row.getRemark(),row.getId())).isEqualTo(1);
  }
}
