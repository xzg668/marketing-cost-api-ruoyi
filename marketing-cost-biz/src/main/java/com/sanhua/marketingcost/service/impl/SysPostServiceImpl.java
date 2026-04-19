package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.system.SysPost;
import com.sanhua.marketingcost.mapper.SysPostMapper;
import com.sanhua.marketingcost.service.SysPostService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 岗位管理 Service 实现类
 */
@Service
public class SysPostServiceImpl implements SysPostService {

    private final SysPostMapper sysPostMapper;

    public SysPostServiceImpl(SysPostMapper sysPostMapper) {
        this.sysPostMapper = sysPostMapper;
    }

    @Override
    public IPage<SysPost> listPosts(int pageNum, int pageSize, String postCode, String postName, String status) {
        // 构建分页查询条件
        LambdaQueryWrapper<SysPost> wrapper = Wrappers.lambdaQuery(SysPost.class);
        if (StringUtils.hasText(postCode)) {
            wrapper.like(SysPost::getPostCode, postCode);
        }
        if (StringUtils.hasText(postName)) {
            wrapper.like(SysPost::getPostName, postName);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SysPost::getStatus, status);
        }
        wrapper.orderByAsc(SysPost::getPostSort);
        return sysPostMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    public List<SysPost> listAll() {
        // 查询所有正常状态的岗位，按排序字段升序
        return sysPostMapper.selectList(
                Wrappers.lambdaQuery(SysPost.class)
                        .eq(SysPost::getStatus, "0")
                        .orderByAsc(SysPost::getPostSort)
        );
    }

    @Override
    public SysPost getById(Long postId) {
        return sysPostMapper.selectById(postId);
    }

    @Override
    @Transactional
    public void createPost(SysPost post) {
        sysPostMapper.insert(post);
    }

    @Override
    @Transactional
    public void updatePost(SysPost post) {
        sysPostMapper.updateById(post);
    }

    @Override
    @Transactional
    public void deletePost(Long postId) {
        sysPostMapper.deleteById(postId);
    }

    @Override
    public boolean isPostCodeUnique(String postCode, Long excludeId) {
        // 查询是否存在相同编码的岗位（排除指定ID）
        LambdaQueryWrapper<SysPost> wrapper = Wrappers.lambdaQuery(SysPost.class)
                .eq(SysPost::getPostCode, postCode);
        if (excludeId != null) {
            wrapper.ne(SysPost::getPostId, excludeId);
        }
        Long count = sysPostMapper.selectCount(wrapper);
        return count == null || count == 0;
    }
}
