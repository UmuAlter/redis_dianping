package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 *  服务类
 */
public interface IUserService extends IService<User> {
    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 登录
     * @param loginForm
     * @param session
     * @return
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 根据userDTO查询user
     * @param userDTO
     * @return
     */
    User queryUser(UserDTO userDTO);

    /**
     * 签到
     * @return
     */
    Result sign();

    /**
     * 签到统计
     * @return
     */
    Result signCount();
}
