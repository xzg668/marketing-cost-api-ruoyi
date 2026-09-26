package com.sanhua.marketingcost.integration.oa.directory;

import java.util.List;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import org.springframework.stereotype.Service;

@Service
public class OaPersonDirectoryService {
  private final OaPersonDirectoryGateway gateway;
  private final OaPersonDirectoryRepository repository;

  public OaPersonDirectoryService(
      OaPersonDirectoryGateway gateway, OaPersonDirectoryRepository repository) {
    this.gateway = gateway;
    this.repository = repository;
  }

  /** OA 网络读取在事务外完成；完整快照拿到后，才开启本地替换事务。 */
  public OaPersonDirectoryRepository.SyncResult synchronize() {
    try (var call = OaInterfaceLog.start("OA_DIRECTORY_SYNC")) {
      try {
        OaDirectorySnapshot snapshot = gateway.load();
        var result = repository.replace(snapshot);
        call.field("batchId", result.batchId()).field("total", result.totalPeople()).field("selectable", result.selectablePeople());
        call.success();
        return result;
      } catch (RuntimeException exception) { call.failure(exception); throw exception; }
    }
  }

  public List<OaPersonDirectoryOption> search(String keyword, int limit) {
    return repository.search(keyword, limit);
  }
}
