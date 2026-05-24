package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.U9MaterialImportResponse;
import com.sanhua.marketingcost.dto.U9MaterialMasterIngestRequest;
import com.sanhua.marketingcost.enums.U9MaterialMasterSourceType;

/**
 * U9 料品主档统一接入适配器。
 *
 * <p>后续 U9 接口、中台、MQ、定时任务接入只替换数据来源 adapter，仍统一写入
 * lp_material_master_raw，并复用同一套差异对比和非空覆盖同步链路。
 */
public interface U9MaterialMasterIngestAdapter {

  U9MaterialMasterSourceType sourceType();

  U9MaterialImportResponse ingest(U9MaterialMasterIngestRequest request);
}
