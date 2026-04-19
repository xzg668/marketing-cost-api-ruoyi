package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.dto.system.SysDictTypeRequest;
import com.sanhua.marketingcost.entity.system.SysDictType;
import com.sanhua.marketingcost.service.SysDictTypeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 字典类型管理 Controller
 */
@RestController
@RequestMapping("/api/v1/system/dict-type")
public class SysDictTypeController {

    private final SysDictTypeService sysDictTypeService;

    public SysDictTypeController(SysDictTypeService sysDictTypeService) {
        this.sysDictTypeService = sysDictTypeService;
    }

    /**
     * 分页查询字典类型列表
     */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('system:dict:list')")
    public CommonResult<IPage<SysDictType>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String dictName,
            @RequestParam(required = false) String dictType,
            @RequestParam(required = false) String status) {
        return CommonResult.success(sysDictTypeService.listPage(pageNum, pageSize, dictName, dictType, status));
    }

    /**
     * 查询所有字典类型（下拉用）
     */
    @GetMapping("/all")
    @PreAuthorize("@ss.hasPermi('system:dict:list')")
    public CommonResult<List<SysDictType>> listAll() {
        return CommonResult.success(sysDictTypeService.listAll());
    }

    /**
     * 根据ID查询字典类型详情
     */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dict:query')")
    public CommonResult<SysDictType> get(@PathVariable("id") Long id) {
        SysDictType dictType = sysDictTypeService.getById(id);
        if (dictType == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "字典类型不存在");
        }
        return CommonResult.success(dictType);
    }

    /**
     * 新增字典类型（校验编码唯一性）
     */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:dict:add')")
    // 字典类型新增
    @OperationLog(module = "字典类型管理", operationType = OperationType.INSERT, recordDiff = true)
    public CommonResult<Void> create(@Valid @RequestBody SysDictTypeRequest req) {
        // 校验字典类型编码唯一性
        if (!sysDictTypeService.isDictTypeUnique(req.getDictType(), null)) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                    "字典类型'" + req.getDictType() + "'已存在");
        }
        SysDictType entity = toEntity(req);
        sysDictTypeService.create(entity);
        return CommonResult.success(null);
    }

    /**
     * 修改字典类型（校验编码唯一性，排除自身）
     */
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dict:edit')")
    // 字典类型编辑
    @OperationLog(module = "字典类型管理", operationType = OperationType.UPDATE,
            recordDiff = true, targetIdParam = "id")
    public CommonResult<Void> update(@PathVariable("id") Long id,
                                     @Valid @RequestBody SysDictTypeRequest req) {
        SysDictType existing = sysDictTypeService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "字典类型不存在");
        }
        // 校验字典类型编码唯一性（排除当前记录）
        if (!sysDictTypeService.isDictTypeUnique(req.getDictType(), id)) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                    "字典类型'" + req.getDictType() + "'已存在");
        }
        SysDictType entity = toEntity(req);
        entity.setDictId(id);
        sysDictTypeService.update(entity);
        return CommonResult.success(null);
    }

    /**
     * 删除字典类型（同时删除该类型下所有字典数据）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dict:remove')")
    // 字典类型删除
    @OperationLog(module = "字典类型管理", operationType = OperationType.DELETE, targetIdParam = "id")
    public CommonResult<Void> delete(@PathVariable("id") Long id) {
        SysDictType existing = sysDictTypeService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "字典类型不存在");
        }
        sysDictTypeService.delete(id);
        return CommonResult.success(null);
    }

    /**
     * 将请求 DTO 转换为实体
     */
    private SysDictType toEntity(SysDictTypeRequest req) {
        SysDictType entity = new SysDictType();
        entity.setDictName(req.getDictName());
        entity.setDictType(req.getDictType());
        entity.setStatus(req.getStatus() != null ? req.getStatus() : "0");
        entity.setRemark(req.getRemark());
        return entity;
    }
}
