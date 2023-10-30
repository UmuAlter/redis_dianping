package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;
    /**
     * 关注
     * @param followedId 被关注的id
     * @param isFollow
     * @return
     */
    @PostMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followedId, @PathVariable("isFollow") Boolean isFollow){
        return followService.follow(followedId, isFollow);
    }

    /**
     * 查看是否关注
     * @return
     */
    @PostMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followedId){
        return followService.isFollowed(followedId);
    }

    /**
     * 查找共同关注
     * @param id
     * @return
     */
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id){
        return followService.followCommons(id);
    }
}
