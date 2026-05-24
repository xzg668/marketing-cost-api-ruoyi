package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest;
import com.sanhua.marketingcost.enums.U9MaterialMasterSourceType;
import com.sanhua.marketingcost.service.U9MaterialMasterIngestAdapter;
import com.sanhua.marketingcost.service.U9MaterialMasterIngestService;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class U9MaterialMasterIngestServiceImpl implements U9MaterialMasterIngestService {
  private final Map<U9MaterialMasterSourceType, U9MaterialMasterIngestAdapter> adapters =
      new EnumMap<>(U9MaterialMasterSourceType.class);

  public U9MaterialMasterIngestServiceImpl(List<U9MaterialMasterIngestAdapter> adapters) {
    for (U9MaterialMasterIngestAdapter adapter : adapters) {
      this.adapters.put(adapter.sourceType(), adapter);
    }
  }

  @Override
  public U9MaterialImportResponse ingest(
      U9MaterialMasterSourceType sourceType,
      U9MaterialMasterIngestRequest request) {
    U9MaterialMasterSourceType actualSourceType = sourceType == null
        ? U9MaterialMasterSourceType.EXCEL
        : sourceType;
    U9MaterialMasterIngestAdapter adapter = adapters.get(actualSourceType);
    if (adapter == null) {
      throw new IllegalArgumentException("未注册 U9 料品主档接入适配器: " + actualSourceType.getCode());
    }
    return adapter.ingest(request);
  }

  @Override
  public List<U9MaterialMasterSourceType> supportedSourceTypes() {
    return List.copyOf(adapters.keySet());
  }
}
