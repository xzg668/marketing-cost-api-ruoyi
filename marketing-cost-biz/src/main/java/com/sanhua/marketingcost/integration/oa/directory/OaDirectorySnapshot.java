package com.sanhua.marketingcost.integration.oa.directory;

import java.util.List;

public record OaDirectorySnapshot(
    int organizationCount,
    int employeeCount,
    List<OaDirectoryPerson> people,
    List<String> missingDepartments) {}
