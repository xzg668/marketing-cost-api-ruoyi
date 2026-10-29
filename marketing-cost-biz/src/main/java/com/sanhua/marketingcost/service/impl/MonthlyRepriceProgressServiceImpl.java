package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunTaskStatusCount;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import com.sanhua.marketingcost.service.MonthlyRepriceProgressService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceProgressServiceImpl implements MonthlyRepriceProgressService {

  private static final String STATUS_PENDING = "PENDING";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILED = "FAILED";
  private static final String STATUS_RETRYABLE = "RETRYABLE";

  private final MonthlyRepriceBatchMapper batchMapper;
  private final CostRunTaskMapper taskMapper;
  private final MonthlyRepriceResultMapper resultMapper;

  public MonthlyRepriceProgressServiceImpl(
      MonthlyRepriceBatchMapper batchMapper,
      CostRunTaskMapper taskMapper,
      MonthlyRepriceResultMapper resultMapper) {
    this.batchMapper = batchMapper;
    this.taskMapper = taskMapper;
    this.resultMapper = resultMapper;
  }



  @Override
  public MonthlyRepriceProgressSnapshot getProgress(String repriceNo) {
    String normalizedRepriceNo = required("repriceNo", repriceNo);
    MonthlyRepriceBatch batch = findBatch(normalizedRepriceNo);
    TaskCounts counts = loadTaskCounts(normalizedRepriceNo);
    int resultCount = safeLong(resultMapper.countByRepriceNo(normalizedRepriceNo));
    return snapshot(batch, counts, resultCount);
  }



  private MonthlyRepriceBatch findBatch(String repriceNo) {
    MonthlyRepriceBatch batch = batchMapper.selectOne(
        Wrappers.lambdaQuery(MonthlyRepriceBatch.class)
            .eq(MonthlyRepriceBatch::getRepriceNo, repriceNo));
    if (batch == null) {
      throw new IllegalArgumentException("月度调价批次不存在：" + repriceNo);
    }
    return batch;
  }

  private TaskCounts loadTaskCounts(String repriceNo) {
    int pending = 0;
    int running = 0;
    int success = 0;
    int failed = 0;
    List<CostRunTaskStatusCount> rows = taskMapper.selectMonthlyRepriceStatusCounts(repriceNo);
    if (rows == null) {
      return new TaskCounts(0, 0, 0, 0);
    }
    for (CostRunTaskStatusCount row : rows) {
      if (row == null) {
        continue;
      }
      int count = safeLong(row.getCount());
      String status = normalize(row.getStatus());
      if (STATUS_PENDING.equals(status) || STATUS_RETRYABLE.equals(status)) {
        pending += count;
      } else if (STATUS_RUNNING.equals(status)) {
        running += count;
      } else if (STATUS_SUCCESS.equals(status)) {
        success += count;
      } else if (STATUS_FAILED.equals(status)) {
        failed += count;
      }
    }
    return new TaskCounts(pending, running, success, failed);
  }







  private MonthlyRepriceProgressSnapshot snapshot(
      MonthlyRepriceBatch batch, TaskCounts counts, int resultCount) {
    return MonthlyRepriceProgressSnapshot.of(
        batch, counts.pendingCount(), counts.runningCount(), resultCount);
  }





  private String required(String field, String value) {
    String normalized = normalize(value);
    if (!StringUtils.hasText(normalized)) {
      throw new IllegalArgumentException(field + " 必填");
    }
    return normalized;
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }



  private int safeLong(long value) {
    return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
  }

  private record TaskCounts(
      int pendingCount, int runningCount, int successCount, int failedCount) {
  }
}
