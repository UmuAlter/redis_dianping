package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户,以及是否被点赞
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    //给博客添加作者信息
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 根据id查询博客
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        //根据id查
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("博客不存在");
        }
        queryBlogUser(blog);
        //查询bLog是否被点赞了
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    //查询bLog是否被点赞了
    private void isBlogLiked(Blog blog) {
        if(UserHolder.getUser() == null){
            //用户未登录，无需查看是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double isSuccess = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(isSuccess != null);
    }

    /**
     * 点赞功能
     * @param id
     * @return
     */
    @Override
    public Result like(Long id) {
        //判断当前用户是否点赞
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Double isLiked = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(isLiked == null) {
            //没点赞为点赞操作 数据库点赞+1 Redis添加当前用户
            boolean isSuccess = update().setSql("liked = liked +1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            //点赞过为取消点赞
            boolean isSuccess = update().setSql("liked = liked -1").eq("id", id).update();
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查看点赞列表
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //查前五条
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> tops = top5.stream().map(Long::valueOf).collect(Collectors.toList());

        String strIds = StrUtil.join(",", tops);
        //解决了排序的问题
        List<UserDTO> userDTOS = userService.query().in("id", tops)
                .last("ORDER BY FIELD(id,"+ strIds + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
