package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonthlyRepriceProgressServiceImplTest {
  @ParameterizedTest
  @ValueSource(strings={"RUNNING","FAILED","WAIT_CONFIRM","CONFIRMED","CANCELLED"})
  void readingHistoricalProgressNeverChangesBatchOrAudits(String status) {
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),MonthlyRepriceBatch.class);
    var batches=mock(MonthlyRepriceBatchMapper.class);
    var tasks=mock(CostRunTaskMapper.class);
    var results=mock(MonthlyRepriceResultMapper.class);
    var batch=new MonthlyRepriceBatch();batch.setRepriceNo("MRP-HISTORY");batch.setStatus(status);batch.setTotalCount(2);batch.setSuccessCount(1);batch.setFailedCount(1);
    when(batches.selectOne(any(Wrapper.class))).thenReturn(batch);
    var view=new MonthlyRepriceProgressServiceImpl(batches,tasks,results).getProgress("MRP-HISTORY");
    assertThat(view.getStatus()).isEqualTo(status);
    assertThat(view.getSuccessCount()).isEqualTo(1);
    verify(batches).selectOne(any(Wrapper.class));
    verify(tasks).selectMonthlyRepriceStatusCounts("MRP-HISTORY");
    verify(results).countByRepriceNo("MRP-HISTORY");
    verifyNoMoreInteractions(batches,tasks,results);
  }
}
