package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.QuoteCostRunVersionNoGenerator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

@DisplayName("QWB-06 成本试算版本服务")
class QuoteCostRunVersionServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, QuoteCostRunVersion.class);
  }

  @Test
  @DisplayName("cost_run_no 按当前时间前缀序号递增，version_no 按产品行递增 Vn")
  void generatorBuildsRunNoAndVersionNo() {
    QuoteCostRunVersionMapper mapper = mock(QuoteCostRunVersionMapper.class);
    when(mapper.selectCount(any())).thenReturn(0L, 4L, 2L);
    QuoteCostRunVersionNoGenerator generator = new QuoteCostRunVersionNoGeneratorImpl(mapper);

    String costRunNo = generator.nextCostRunNo();
    String versionNo = generator.nextVersionNo(101L, " P-001 ");

    assertThat(costRunNo).startsWith("TRIAL-").endsWith("-0001");
    assertThat(versionNo).startsWith("COST-").endsWith("-0005-V3");
  }

  @Test
  @DisplayName("数据库唯一约束冲突时重新生成 cost_run_no 并重试")
  void createTrialRetriesWhenCostRunNoConflicts() {
    QuoteCostRunVersionMapper mapper = mock(QuoteCostRunVersionMapper.class);
    QuoteCostRunVersionNoGenerator generator = mock(QuoteCostRunVersionNoGenerator.class);
    when(generator.nextCostRunNo()).thenReturn("RUN-1", "RUN-2");
    when(mapper.insert(any(QuoteCostRunVersion.class)))
        .thenThrow(new DuplicateKeyException("duplicate"))
        .thenAnswer(invocation -> {
          QuoteCostRunVersion version = invocation.getArgument(0);
          version.setId(88L);
          return 1;
        });
    QuoteCostRunVersionServiceImpl service = new QuoteCostRunVersionServiceImpl(mapper, generator);

    QuoteCostRunVersion version =
        service.createTrial(
            " OA-001 ",
            101L,
            " P-001 ",
            "2026-06",
            "2026-06",
            "PPR-1",
            "QPTC-1",
            "QBC-1",
            "COMMERCIAL");

    assertThat(version.getId()).isEqualTo(88L);
    assertThat(version.getCostRunNo()).isEqualTo("RUN-2");
    assertThat(version.getOaNo()).isEqualTo("OA-001");
    assertThat(version.getProductCode()).isEqualTo("P-001");
    verify(generator, times(2)).nextCostRunNo();
    ArgumentCaptor<QuoteCostRunVersion> captor = ArgumentCaptor.forClass(QuoteCostRunVersion.class);
    verify(mapper, times(2)).insert(captor.capture());
    assertThat(captor.getAllValues().get(1).getPricePrepareNo()).isEqualTo("PPR-1");
  }
}
