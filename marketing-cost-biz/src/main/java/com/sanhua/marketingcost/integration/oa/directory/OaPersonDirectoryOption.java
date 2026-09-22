package com.sanhua.marketingcost.integration.oa.directory;

public record OaPersonDirectoryOption(
    Long userId,
    String employeeNo,
    String name,
    String position,
    String department,
    String targetDepartment) {}
