package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest;
import com.sanhua.marketingcost.enums.U9MaterialMasterSourceType;
import java.util.List;

public interface U9MaterialMasterIngestService {

  U9MaterialImportResponse ingest(
      U9MaterialMasterSourceType sourceType,
      U9MaterialMasterIngestRequest request);

  List<U9MaterialMasterSourceType> supportedSourceTypes();
}
