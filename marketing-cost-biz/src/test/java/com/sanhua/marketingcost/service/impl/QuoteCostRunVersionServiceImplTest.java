package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.CostingAlgorithmVersionProvider;
import com.sanhua.marketingcost.service.QuoteCostRunVersionNoGenerator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
  @DisplayName("cost_run_no 跨进程唯一，version_no 保留每日序号及产品 Vn")
  void generatorBuildsRunNoAndVersionNo() {
    QuoteCostRunVersionMapper mapper = mock(QuoteCostRunVersionMapper.class);
    when(mapper.selectCount(any())).thenReturn(2L);
    var sequence = mock(com.sanhua.marketingcost.service.costing.QuoteCostVersionSequence.class);
    when(sequence.next(any(), any())).thenReturn(5L);
    QuoteCostRunVersionNoGenerator generator =
        new QuoteCostRunVersionNoGeneratorImpl(mapper, sequence);

    String costRunNo = generator.nextCostRunNo();
    String versionNo = generator.nextVersionNo(101L, " P-001 ");

    assertThat(costRunNo).matches("TRIAL-[0-9]{8}-[0-9]{6}-[a-f0-9]{32}");
    assertThat(generator.nextCostRunNo()).isNotEqualTo(costRunNo);
    assertThat(versionNo).startsWith("COST-").endsWith("-0005-V3");
  }

  @Test
  @DisplayName("意外唯一键冲突原样退出，不在同一事务中反复写入")
  void unexpectedUniqueConflictPropagates() {
    QuoteCostRunVersionMapper mapper = mock(QuoteCostRunVersionMapper.class);
    QuoteCostRunVersionNoGenerator generator = mock(QuoteCostRunVersionNoGenerator.class);
    when(generator.nextCostRunNo()).thenReturn("RUN-1");
    when(mapper.insert(any(QuoteCostRunVersion.class)))
        .thenThrow(new DuplicateKeyException("duplicate"));
    var service = new QuoteCostRunVersionServiceImpl(mapper, generator, algorithmVersion());
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.createTrial(
                    "OA-001", 101L, "P-001", "2026-06", "2026-06", "PPR-1", "COMMERCIAL"))
        .isInstanceOf(DuplicateKeyException.class);
    verify(mapper).insert(any(QuoteCostRunVersion.class));
  }

  @Test
  @DisplayName("自动流水线创建 RUNNING 版本")
  void createRunningUsesRunningStatus() {
    QuoteCostRunVersionMapper mapper = mock(QuoteCostRunVersionMapper.class);
    QuoteCostRunVersionNoGenerator generator = mock(QuoteCostRunVersionNoGenerator.class);
    when(generator.nextCostRunNo()).thenReturn("RUN-AUTO-1");
    when(mapper.insert(any(QuoteCostRunVersion.class)))
        .thenAnswer(
            invocation -> {
              QuoteCostRunVersion version = invocation.getArgument(0);
              version.setId(99L);
              return 1;
            });
    QuoteCostRunVersionServiceImpl service =
        new QuoteCostRunVersionServiceImpl(mapper, generator, algorithmVersion());

    QuoteCostRunVersion version =
        service.createRunning(
            "OA-001",
            101L,
            "P-001",
            "2026-08",
            "2026-08",
            "PPR-1",
            "COMMERCIAL");

    assertThat(version.getId()).isEqualTo(99L);
    assertThat(version.getAlgorithmVersion())
        .isEqualTo(CostingAlgorithmVersionProvider.DEFAULT_VERSION);
    assertThat(version.getStatus()).isEqualTo("RUNNING");
  }

  private CostingAlgorithmVersionProvider algorithmVersion() {
    return new CostingAlgorithmVersionProvider(CostingAlgorithmVersionProvider.DEFAULT_VERSION);
  }
}
