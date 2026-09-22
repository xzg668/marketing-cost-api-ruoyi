package com.sanhua.marketingcost.integration.oa.directory;

import java.util.List;

public record OaDirectoryPerson(
    String oaUserId,
    String employeeNo,
    String name,
    String positionName,
    String positionId,
    String employmentStatus,
    List<String> targetDepartmentPaths,
    List<String> actualDepartmentPaths,
    List<String> oaDepartmentIds,
    List<String> targetDepartmentIds,
    List<String> matchTypes) {}
