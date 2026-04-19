package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.dto.system.SysDictDataRequest;
import com.sanhua.marketingcost.entity.system.SysDictData;
import com.sanhua.marketingcost.mapper.SysDictDataMapper;
import com.sanhua.marketingcost.service.SysDictDataService;
import com.sanhua.marketingcost.vo.DictDataSimpleVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 字典数据 Controller
 * <p>
 * 包含两部分接口：
 * 1. 公开只读接口 /type/{dictType} — 登录页等无鉴权场景使用（T16）
 * 2. 管理 CRUD 接口 — 需要权限认证（T25）
 */
@RestController
@RequestMapping("/api/v1/system/dict-data")
public class SysDictDataController {

    /** 字典状态：0 正常 */
    private static final String STATUS_NORMAL = "0";

    private final SysDictDataMapper sysDictDataMapper;
    private final SysDictDataService sysDictDataService;

    public SysDictDataController(SysDictDataMapper sysDictDataMapper, SysDictDataService sysDictDataService) {
        this.sysDictDataMapper = sysDictDataMapper;
        this.sysDictDataService = sysDictDataService;
    }

    // ==================== 公开只读接口（无需登录） ====================

    /**
     * 根据字典类型拉取正常状态的字典项，按 dict_sort 升序返回
     * 登录页业务单元下拉等场景使用
     */
    @GetMapping("/type/{dictType}")
    public CommonResult<List<DictDataSimpleVO>> listByType(@PathVariable("dictType") String dictType) {
        LambdaQueryWrapper<SysDictData> wrapper = new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getDictType, dictType)
                .eq(SysDictData::getStatus, STATUS_NORMAL)
                .orderByAsc(SysDictData::getDictSort);
        List<DictDataSimpleVO> items = sysDictDataMapper.selectList(wrapper).stream()
                .map(this::toSimpleVO)
                .toList();
        return CommonResult.success(items);
    }

    // ==================== 管理 CRUD 接口（需要权限） ====================

    /**
     * 分页查询字典数据列表
     */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('system:dict:list')")
    public CommonResult<IPage<SysDictData>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String dictType,
            @RequestParam(required = false) String dictLabel,
            @RequestParam(required = false) String status) {
        return CommonResult.success(sysDictDataService.listPage(pageNum, pageSize, dictType, dictLabel, status));
    }

    /**
     * 根据ID查询字典数据详情
     */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dict:query')")
    public CommonResult<SysDictData> get(@PathVariable("id") Long id) {
        SysDictData data = sysDictDataService.getById(id);
        if (data == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "字典数据不存在");
        }
        return CommonResult.success(data);
    }

    /**
     * 新增字典数据
     */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:dict:add')")
    // 字典数据新增
    @OperationLog(module = "字典数据管理", operationType = OperationType.INSERT, recordDiff = true)
    public CommonResult<Void> create(@Valid @RequestBody SysDictDataRequest req) {
        SysDictData entity = toEntity(req);
        sysDictDataService.create(entity);
        return CommonResult.success(null);
    }

    /**
     * 修改字典数据
     */
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dict:edit')")
    // 字典数据编辑
    @OperationLog(module = "字典数据管理", operationType = OperationType.UPDATE,
            recordDiff = true, targetIdParam = "id")
    public CommonResult<Void> update(@PathVariable("id") Long id,
                                     @Valid @RequestBody SysDictDataRequest req) {
        SysDictData existing = sysDictDataService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "字典数据不存在");
        }
        SysDictData entity = toEntity(req);
        entity.setDictCode(id);
        sysDictDataService.update(entity);
        return CommonResult.success(null);
    }

    /**
     * 删除字典数据
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:dict:remove')")
    // 字典数据删除
    @OperationLog(module = "字典数据管理", operationType = OperationType.DELETE, targetIdParam = "id")
    public CommonResult<Void> delete(@PathVariable("id") Long id) {
        SysDictData existing = sysDictDataService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "字典数据不存在");
        }
        sysDictDataService.delete(id);
        return CommonResult.success(null);
    }

    // ==================== 私有方法 ====================

    /** 转换为简单 VO（公开接口用） */
    private DictDataSimpleVO toSimpleVO(SysDictData data) {
        DictDataSimpleVO vo = new DictDataSimpleVO();
        vo.setValue(data.getDictValue());
        vo.setLabel(data.getDictLabel());
        return vo;
    }

    /** 将请求 DTO 转换为实体 */
    private SysDictData toEntity(SysDictDataRequest req) {
        SysDictData entity = new SysDictData();
        entity.setDictType(req.getDictType());
        entity.setDictLabel(req.getDictLabel());
        entity.setDictValue(req.getDictValue());
        entity.setDictSort(req.getDictSort() != null ? req.getDictSort() : 0);
        entity.setCssClass(req.getCssClass());
        entity.setListClass(req.getListClass());
        entity.setIsDefault(req.getIsDefault() != null ? req.getIsDefault() : "N");
        entity.setStatus(req.getStatus() != null ? req.getStatus() : "0");
        entity.setRemark(req.getRemark());
        return entity;
    }
}
