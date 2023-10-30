package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 */
public interface IBlogService extends IService<Blog> {

    Result queryHotBlog(Integer current);

    /**
     * 根据博客id查询博客
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 点赞功能
     * @param id
     * @return
     */
    Result like(Long id);

    /**
     * 查看点赞列表
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 提交点评
     * 并且将刚发布的消息推送给粉丝们
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 滚动分页查询
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
