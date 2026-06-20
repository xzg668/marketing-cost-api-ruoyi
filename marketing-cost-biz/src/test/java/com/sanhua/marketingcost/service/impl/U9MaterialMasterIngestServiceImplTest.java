package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest;
import com.sanhua.marketingcost.enums.U9MaterialMasterSourceType;
import com.sanhua.marketingcost.service.U9MaterialMasterIngestAdapter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("U9MaterialMasterIngestServiceImpl")
class U9MaterialMasterIngestServiceImplTest {

  @Test
  @DisplayName("ingest：EXCEL 来源委托 Excel adapter，API 占位失败不影响 Excel")
  void excelAdapterIsIndependentFromApiPlaceholder() {
    U9MaterialMasterIngestAdapter excelAdapter = new StubAdapter(U9MaterialMasterSourceType.EXCEL);
    U9MaterialMasterIngestServiceImpl service = new U9MaterialMasterIngestServiceImpl(List.of(
        excelAdapter,
        new ApiU9MaterialMasterAdapter()));

    U9MaterialImportResponse response =
        service.ingest(
            U9MaterialMasterSourceType.EXCEL,
            new U9MaterialMasterIngestRequest(null, null, null, "COMMERCIAL", null));

    assertThat(response.getSourceType()).isEqualTo("EXCEL");
    assertThatThrownBy(() ->
        service.ingest(
            U9MaterialMasterSourceType.API,
            new U9MaterialMasterIngestRequest(null, null, null, "COMMERCIAL", null)))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("API 接入尚未实现");
  }

  @Test
  @DisplayName("supportedSourceTypes：预留 EXCEL/API/MQ/SCHEDULE 四类 source_type")
  void supportedSourceTypesContainsFutureSources() {
    U9MaterialMasterIngestServiceImpl service = new U9MaterialMasterIngestServiceImpl(List.of(
        new StubAdapter(U9MaterialMasterSourceType.EXCEL),
        new ApiU9MaterialMasterAdapter(),
        new MqU9MaterialMasterAdapter(),
        new ScheduleU9MaterialMasterAdapter()));

    assertThat(service.supportedSourceTypes())
        .containsExactly(
            U9MaterialMasterSourceType.EXCEL,
            U9MaterialMasterSourceType.API,
            U9MaterialMasterSourceType.MQ,
            U9MaterialMasterSourceType.SCHEDULE);
  }

  private record StubAdapter(U9MaterialMasterSourceType sourceType)
      implements U9MaterialMasterIngestAdapter {
    @Override
    public U9MaterialImportResponse ingest(U9MaterialMasterIngestRequest request) {
      U9MaterialImportResponse response = new U9MaterialImportResponse();
      response.setSourceType(sourceType.getCode());
      return response;
    }
  }
}
