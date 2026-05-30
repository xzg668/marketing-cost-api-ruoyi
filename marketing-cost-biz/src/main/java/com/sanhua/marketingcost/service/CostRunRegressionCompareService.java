package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunRegressionCompareReport;
import java.util.List;

public interface CostRunRegressionCompareService {

  List<CostRunObjectResult> loadStoredSnapshots(String oaNo);

  CostRunObjectResult loadStoredSnapshot(String oaNo, String productCode);

  CostRunRegressionCompareReport compare(
      CostRunObjectResult baselineSnapshot, CostRunObjectResult candidateResult);

  List<CostRunRegressionCompareReport> compareOaSnapshots(
      List<CostRunObjectResult> baselineSnapshots, List<CostRunObjectResult> candidateSnapshots);

  String renderMarkdownReport(String title, List<CostRunRegressionCompareReport> reports);

  CostRunRegressionCompareReport compareStoredSnapshot(
      String oaNo, String productCode, CostRunObjectResult candidateResult);
}
