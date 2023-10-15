package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //获取session
//        HttpSession session = request.getSession();
//        Object user = session.getAttribute("user");

        //1.获取token
        // authorization来自与前端约定
        String token = request.getHeader("authorization");
        if(StrUtil.isBlankIfStr(token)){
            //如果为空
            response.setStatus(401);
            return false;
        }
        //获取用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate
                .opsForHash()
                .entries(key);
        if(userMap.isEmpty()){
            //拦截
            response.setStatus(401);
            return false;
        }
        //保存在ThreadLocal

//        UserDTO userDTO = new UserDTO();
//        User tuser = (User)user;
//        //组装userDTO
//        userDTO.setId(tuser.getId());
//        userDTO.setIcon(tuser.getIcon());
//        userDTO.setNickName(tuser.getNickName());

        //将查询到的数据转换为UserDTO对象并存储 false:不忽略错误
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        //刷新到期时间
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //放行的逻辑
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
