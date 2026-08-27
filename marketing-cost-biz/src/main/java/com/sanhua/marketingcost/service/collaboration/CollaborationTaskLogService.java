package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/** 报价系统内部的协作任务轨迹；不承担任何 OA 推送或回调职责。 */
@Service
public class CollaborationTaskLogService {

  static final String DOMAIN = "QUOTE_COLLABORATION";
  static final String TYPE = "PRODUCT_TASK_EVENT";

  private final BusinessChangeLogMapper mapper;

  public CollaborationTaskLogService(BusinessChangeLogMapper mapper) {
    this.mapper = mapper;
  }

  public void record(
      QuoteCollaborationProductTask task, String eventType, String description) {
    if (task == null || task.getId() == null || eventType == null || eventType.isBlank()) {
      throw new IllegalArgumentException("协作任务轨迹缺少必要信息");
    }
    BusinessChangeLog log = new BusinessChangeLog();
    log.setBizDomain(DOMAIN);
    log.setBizType(TYPE);
    log.setBizId(task.getId());
    log.setTaskId(task.getId());
    log.setFieldName(eventType.trim());
    log.setFieldLabel("协作任务状态");
    log.setAfterValue(task.getTaskStatus());
    log.setChangeReason(description);
    log.setChangedBy(task.getUpdatedBy() == null ? task.getCreatedBy() : task.getUpdatedBy());
    log.setChangedByName(task.getUpdatedByName() == null
        ? task.getCreatedByName() : task.getUpdatedByName());
    log.setChangedAt(LocalDateTime.now());
    log.setChangeSource("SYSTEM");
    if (mapper.insert(log) != 1) {
      throw new CollaborationPersistenceException("保存协作任务轨迹失败");
    }
  }

  public List<BusinessChangeLog> findByProductTask(Long productTaskId) {
    return mapper.selectList(Wrappers.<BusinessChangeLog>lambdaQuery()
        .eq(BusinessChangeLog::getBizDomain, DOMAIN)
        .eq(BusinessChangeLog::getBizType, TYPE)
        .eq(BusinessChangeLog::getBizId, productTaskId)
        .orderByAsc(BusinessChangeLog::getChangedAt)
        .orderByAsc(BusinessChangeLog::getId));
  }
}
