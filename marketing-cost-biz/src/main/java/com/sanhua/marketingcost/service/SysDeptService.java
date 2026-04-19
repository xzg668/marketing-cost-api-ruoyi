package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.system.SysDept;

import java.util.List;

public interface SysDeptService {

    List<SysDept> listAll();

    SysDept getById(Long deptId);

    void createDept(SysDept dept);

    void updateDept(SysDept dept);

    void deleteDept(Long deptId);

    boolean hasChildren(Long deptId);
}
