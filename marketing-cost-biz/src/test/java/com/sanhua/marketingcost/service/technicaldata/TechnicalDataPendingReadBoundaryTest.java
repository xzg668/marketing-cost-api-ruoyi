package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** A list is a read of recorded work, never an invocation of source/business validation. */
class TechnicalDataPendingReadBoundaryTest {
  @Test void readingOnePageDoesNotValidateAnyProductsOrReadRowsOutsideThatPage() throws Exception {
    var jdbc=mock(JdbcTemplate.class);
    var json=new ObjectMapper().findAndRegisterModules();
    var query=new TechnicalDataPendingProductQuery(jdbc,json);
    var rowsRead=new ArrayList<Long>();
    when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(60L);
    when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call -> {
      RowMapper<?> mapper=call.getArgument(1);
      Object[] args=(Object[])call.getRawArguments()[2];
      int offset=((Number)args[args.length-2]).intValue();
      int size=((Number)args[args.length-1]).intValue();
      var result=new ArrayList<Object>();
      for(long id=offset+1;id<=Math.min(60,offset+size);id++) {
        var row=mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(id);
        when(row.getString("period_month")).thenReturn("2026-11");
        when(row.getString("technical_check_json")).thenReturn(json.writeValueAsString(Map.of("check",saved(id))));
        rowsRead.add(id);
        result.add(mapper.mapRow(row,result.size()));
      }
      return result;
    });
    var page=query.readPage("ALL",null,"2026-11",null,null,20,20);
    assertThat(page.total()).isEqualTo(60);
    assertThat(page.records()).hasSize(20);
    assertThat(rowsRead).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(21,40).boxed().toList());
  }

  private static TechnicalDataSourceCheckResponse saved(long id) {
    var now=LocalDateTime.of(2026,11,16,10,0);
    return new TechnicalDataSourceCheckResponse(id,"2026-11","saved-"+id,now,List.of(
        new TechnicalDataModuleRequirement("PRICE",true,"PRICE_SOURCE_MISSING","已记录缺价",
            TechnicalDataAvailability.MISSING,"saved-price",now)),null,List.of());
  }
}
