package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest;
import com.sanhua.marketingcost.enums.U9MaterialMasterSourceType;
import com.sanhua.marketingcost.service.U9MaterialMasterIngestAdapter;
import org.springframework.stereotype.Service;

@Service
public class ScheduleU9MaterialMasterAdapter implements U9MaterialMasterIngestAdapter {

  @Override
  public U9MaterialMasterSourceType sourceType() {
    return U9MaterialMasterSourceType.SCHEDULE;
  }

  @Override
  public U9MaterialImportResponse ingest(U9MaterialMasterIngestRequest request) {
    throw new UnsupportedOperationException("U9 料品主档定时同步尚未实现，请继续使用 Excel 导入");
  }
}
