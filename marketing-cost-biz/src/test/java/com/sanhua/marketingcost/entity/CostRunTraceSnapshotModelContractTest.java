package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceDetailDto;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceListItemDto;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceListResponse;
import com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("成本核算底稿快照模型契约")
class CostRunTraceSnapshotModelContractTest {

  @Test
  @DisplayName("实体绑定底稿快照表并覆盖版本隔离字段")
  void entityBindsTraceSnapshotTableAndVersionBoundary() {
    TableName tableName = CostRunTraceSnapshot.class.getAnnotation(TableName.class);
    assertThat(tableName).isNotNull();
    assertThat(tableName.value()).isEqualTo("lp_cost_run_trace_snapshot");
    assertFields(
        CostRunTraceSnapshot.class,
        "costRunVersionId",
        "costRunNo",
        "versionNo",
        "oaNo",
        "oaFormItemId",
        "productCode",
        "pricingMonth",
        "traceType",
        "traceKey");
  }

  @Test
  @DisplayName("实体覆盖部品、费用和来源引用字段")
  void entityCoversPartCostAndSourceReferences() {
    assertFields(
        CostRunTraceSnapshot.class,
        "partItemId",
        "costItemId",
        "bomRowId",
        "pricePrepareItemId",
        "materialCode",
        "materialName",
        "costCode",
        "costName",
        "sourceType",
        "sourceBatchNo",
        "sourceRefId");
  }

  @Test
  @DisplayName("金额字段使用 BigDecimal，JSON 字段保持 String")
  void entityUsesBigDecimalAndStringJsonFields() throws NoSuchFieldException {
    assertFieldType("unitPrice", BigDecimal.class);
    assertFieldType("quantity", BigDecimal.class);
    assertFieldType("baseAmount", BigDecimal.class);
    assertFieldType("rate", BigDecimal.class);
    assertFieldType("amount", BigDecimal.class);
    assertFieldType("sourceSnapshotJson", String.class);
    assertFieldType("formulaSnapshotJson", String.class);
    assertFieldType("variablesJson", String.class);
    assertFieldType("stepsJson", String.class);
    assertFieldType("childrenJson", String.class);
  }

  @Test
  @DisplayName("Mapper 使用基础 CRUD 且基础列表查询带业务单元隔离")
  void mapperExtendsBaseMapperAndKeepsDataScope() throws NoSuchMethodException {
    assertThat(BaseMapper.class).isAssignableFrom(CostRunTraceSnapshotMapper.class);
    Method selectList = CostRunTraceSnapshotMapper.class.getMethod("selectList", com.baomidou.mybatisplus.core.conditions.Wrapper.class);
    assertThat(selectList.getAnnotation(DataScope.class)).isNotNull();
  }

  @Test
  @DisplayName("DTO 覆盖列表和详情展示契约")
  void dtoFieldsCoverListAndDetailContracts() throws NoSuchFieldException {
    assertFields(
        CostRunTraceListResponse.class,
        "costRunVersionId",
        "costRunNo",
        "versionNo",
        "oaNo",
        "oaFormItemId",
        "productCode",
        "pricingMonth",
        "total",
        "records");
    assertFieldType(CostRunTraceListResponse.class, "records", java.util.List.class);
    assertFields(
        CostRunTraceListItemDto.class,
        "id",
        "costRunNo",
        "traceType",
        "traceKey",
        "materialCode",
        "costCode",
        "sourceType",
        "amount",
        "summary",
        "businessUnitType",
        "createdAt");
    assertFieldType(CostRunTraceListItemDto.class, "amount", BigDecimal.class);
    assertFieldType(CostRunTraceListItemDto.class, "createdAt", LocalDateTime.class);
    assertFields(
        CostRunTraceDetailDto.class,
        "bomRowId",
        "pricePrepareItemId",
        "sourceSnapshotJson",
        "formulaSnapshotJson",
        "variablesJson",
        "stepsJson",
        "childrenJson");
    assertFieldType(CostRunTraceDetailDto.class, "sourceSnapshotJson", String.class);
    assertFieldType(CostRunTraceDetailDto.class, "formulaSnapshotJson", String.class);
    assertFieldType(CostRunTraceDetailDto.class, "variablesJson", String.class);
    assertFieldType(CostRunTraceDetailDto.class, "stepsJson", String.class);
    assertFieldType(CostRunTraceDetailDto.class, "childrenJson", String.class);
  }

  private static void assertFieldType(String fieldName, Class<?> type) throws NoSuchFieldException {
    assertFieldType(CostRunTraceSnapshot.class, fieldName, type);
  }

  private static void assertFieldType(Class<?> owner, String fieldName, Class<?> type)
      throws NoSuchFieldException {
    Field field = owner.getDeclaredField(fieldName);
    assertThat(field.getType()).isEqualTo(type);
  }

  private static void assertFields(Class<?> type, String... fields) {
    Set<String> names =
        Arrays.stream(type.getDeclaredFields()).map(Field::getName).collect(Collectors.toSet());
    assertThat(names).contains(fields);
  }
}
