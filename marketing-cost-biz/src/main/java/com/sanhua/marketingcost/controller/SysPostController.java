package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.dto.system.SysPostRequest;
import com.sanhua.marketingcost.entity.system.SysPost;
import com.sanhua.marketingcost.service.SysPostService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 岗位管理 Controller
 */
@RestController
@RequestMapping("/api/v1/system/post")
public class SysPostController {

    private final SysPostService sysPostService;

    public SysPostController(SysPostService sysPostService) {
        this.sysPostService = sysPostService;
    }

    /**
     * 分页查询岗位列表
     */
    @GetMapping
    @PreAuthorize("@ss.hasPermi('system:post:list')")
    public CommonResult<IPage<SysPost>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String postCode,
            @RequestParam(required = false) String postName,
            @RequestParam(required = false) String status) {
        return CommonResult.success(sysPostService.listPosts(pageNum, pageSize, postCode, postName, status));
    }

    /**
     * 查询所有岗位（下拉选择用）
     */
    @GetMapping("/all")
    @PreAuthorize("@ss.hasAnyPermi('system:post:list','system:user:list')")
    public CommonResult<List<SysPost>> listAll() {
        return CommonResult.success(sysPostService.listAll());
    }

    /**
     * 根据ID查询岗位详情
     */
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:post:query')")
    public CommonResult<SysPost> get(@PathVariable("id") Long id) {
        SysPost post = sysPostService.getById(id);
        if (post == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "岗位不存在");
        }
        return CommonResult.success(post);
    }

    /**
     * 新增岗位（校验编码唯一性）
     */
    @PostMapping
    @PreAuthorize("@ss.hasPermi('system:post:add')")
    // 岗位新增
    @OperationLog(module = "岗位管理", operationType = OperationType.INSERT, recordDiff = true)
    public CommonResult<Void> create(@Valid @RequestBody SysPostRequest req) {
        // 校验岗位编码唯一性
        if (!sysPostService.isPostCodeUnique(req.getPostCode(), null)) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                    "岗位编码'" + req.getPostCode() + "'已存在");
        }
        SysPost post = toEntity(req);
        sysPostService.createPost(post);
        return CommonResult.success(null);
    }

    /**
     * 修改岗位（校验编码唯一性，排除自身）
     */
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:post:edit')")
    // 岗位编辑
    @OperationLog(module = "岗位管理", operationType = OperationType.UPDATE,
            recordDiff = true, targetIdParam = "id")
    public CommonResult<Void> update(@PathVariable("id") Long id,
                                     @Valid @RequestBody SysPostRequest req) {
        SysPost existing = sysPostService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "岗位不存在");
        }
        // 校验岗位编码唯一性（排除当前岗位）
        if (!sysPostService.isPostCodeUnique(req.getPostCode(), id)) {
            return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
                    "岗位编码'" + req.getPostCode() + "'已存在");
        }
        SysPost post = toEntity(req);
        post.setPostId(id);
        sysPostService.updatePost(post);
        return CommonResult.success(null);
    }

    /**
     * 删除岗位
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('system:post:remove')")
    // 岗位删除
    @OperationLog(module = "岗位管理", operationType = OperationType.DELETE, targetIdParam = "id")
    public CommonResult<Void> delete(@PathVariable("id") Long id) {
        SysPost existing = sysPostService.getById(id);
        if (existing == null) {
            return CommonResult.error(GlobalErrorCodeConstants.NOT_FOUND.getCode(), "岗位不存在");
        }
        sysPostService.deletePost(id);
        return CommonResult.success(null);
    }

    /**
     * 将请求 DTO 转换为实体对象
     */
    private SysPost toEntity(SysPostRequest req) {
        SysPost post = new SysPost();
        post.setPostCode(req.getPostCode());
        post.setPostName(req.getPostName());
        post.setPostSort(req.getPostSort() != null ? req.getPostSort() : 0);
        post.setStatus(req.getStatus() != null ? req.getStatus() : "0");
        post.setRemark(req.getRemark());
        return post;
    }
}
