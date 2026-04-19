package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.dto.system.SysDeptRequest;
import com.sanhua.marketingcost.entity.system.SysDept;
import com.sanhua.marketingcost.service.SysDeptService;
import com.sanhua.marketingcost.vo.DeptTreeSelectVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/system/dept")
public class SysDeptController {

    private final SysDeptService sysDeptService;

    public SysDeptController(SysDeptService sysDeptService) {
        this.sysDeptService = sysDeptService;
    }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('system:dept:list')")
    public CommonResult<List<SysDept>> list() {
        return CommonResult.success(sysDeptService.listAll());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dept:query')")
    public CommonResult<SysDept> get(@PathVariable("id") Long id) {
        SysDept dept = sysDeptService.getById(id);
        if (dept == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "部门不存在");
        }
        return CommonResult.success(dept);
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:dept:add')")
    // 部门新增
    @OperationLog(module = "部门管理", operationType = OperationType.INSERT, recordDiff = true)
    public CommonResult<Void> create(@Valid @RequestBody SysDeptRequest req) {
        SysDept dept = toEntity(req);
        sysDeptService.createDept(dept);
        return CommonResult.success(null);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dept:edit')")
    // 部门编辑
    @OperationLog(module = "部门管理", operationType = OperationType.UPDATE,
            recordDiff = true, targetIdParam = "id")
    public CommonResult<Void> update(@PathVariable("id") Long id,
                                     @Valid @RequestBody SysDeptRequest req) {
        SysDept existing = sysDeptService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "部门不存在");
        }
        SysDept dept = toEntity(req);
        dept.setDeptId(id);
        sysDeptService.updateDept(dept);
        return CommonResult.success(null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dept:remove')")
    // 部门删除
    @OperationLog(module = "部门管理", operationType = OperationType.DELETE, targetIdParam = "id")
    public CommonResult<Void> delete(@PathVariable("id") Long id) {
        if (sysDeptService.hasChildren(id)) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                    "存在子部门，不允许删除");
        }
        sysDeptService.deleteDept(id);
        return CommonResult.success(null);
    }

    @GetMapping("/tree-select")
    @PreAuthorize("@ss.hasAnyPermi('system:dept:list','system:user:list')")
    public CommonResult<List<DeptTreeSelectVO>> treeSelect() {
        List<SysDept> depts = sysDeptService.listAll();
        return CommonResult.success(buildTree(depts));
    }

    private SysDept toEntity(SysDeptRequest req) {
        SysDept dept = new SysDept();
        dept.setDeptName(req.getDeptName());
        dept.setParentId(req.getParentId() != null ? req.getParentId() : 0L);
        dept.setOrderNum(req.getOrderNum() != null ? req.getOrderNum() : 0);
        dept.setOrgType(req.getOrgType());
        dept.setLeader(req.getLeader());
        dept.setPhone(req.getPhone());
        dept.setEmail(req.getEmail());
        dept.setStatus(req.getStatus() != null ? req.getStatus() : "0");
        return dept;
    }

    private List<DeptTreeSelectVO> buildTree(List<SysDept> depts) {
        Map<Long, DeptTreeSelectVO> map = new LinkedHashMap<>();
        for (SysDept d : depts) {
            DeptTreeSelectVO vo = new DeptTreeSelectVO();
            vo.setId(d.getDeptId());
            vo.setLabel(d.getDeptName());
            map.put(d.getDeptId(), vo);
        }
        List<DeptTreeSelectVO> roots = new ArrayList<>();
        for (SysDept d : depts) {
            DeptTreeSelectVO node = map.get(d.getDeptId());
            Long pid = d.getParentId();
            if (pid == null || pid == 0L || !map.containsKey(pid)) {
                roots.add(node);
            } else {
                DeptTreeSelectVO parent = map.get(pid);
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(node);
            }
        }
        return roots;
    }
}
