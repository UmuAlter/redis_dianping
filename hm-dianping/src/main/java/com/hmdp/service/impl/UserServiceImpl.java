package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //手机号不符合规范
            return Result.fail("手机号格式错误");
        }
        //2.生成验证码
        String code = RandomUtil.randomNumbers(6);

//        //保存验证码到session
//        session.setAttribute(phone, code);

        //保存验证码到Redis并设置有效期
        stringRedisTemplate.opsForValue()
                .set(LOGIN_CODE_KEY +phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送短信 模拟
        log.debug("{}",code);
        return Result.ok();
    }

    /**
     * 登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //检验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            //手机号不符合规范
            return Result.fail("手机号格式错误");
        }
        //从Redis获取并检验验证码
//        Object cacheCode = session.getAttribute(phone);
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //查询——基于mybatis——plus
        User user = query().eq("phone", phone).one();
        if(user == null){
            //新用户_ 创建并保存
            user = createUserWithPhone(phone);
        }
        //保存用户信息到缓存

//        session.setAttribute("user", user);

        //生成随机token
        String token = UUID.randomUUID().toString();
        //将User转化为HashMap
        UserDTO userDTO = new UserDTO();
        BeanUtils.copyProperties(user, userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,
                new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fileName, fileValue)->fileValue.toString())
                );
        //存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        //设置有效期 30min 每次操作后要刷新有效期，只有什么都不做30min才失效
        stringRedisTemplate.expire(LOGIN_USER_KEY+token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);
    }

    @Override
    public User queryUser(UserDTO userDTO) {
        return query().eq("id", userDTO.getId()).one();
    }

    /**
     * 签到
     * @return
     */
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();

        LocalDateTime now = LocalDateTime.now();
        String suffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + suffix;

        int dayOfMonth = now.getDayOfMonth();

        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true);
        return Result.ok();
    }

    /**
     * 签到统计
     * @return
     */
    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();

        LocalDateTime now = LocalDateTime.now();
        String suffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + suffix;

        int dayOfMonth = now.getDayOfMonth();
        //获取本月截止到今天为止所有的签到记录 BITFIELD key GET u14 0
        List<Long> longs = stringRedisTemplate.opsForValue()
                .bitField(
                        key,
                        BitFieldSubCommands.create()
                                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
                );
        if(longs == null || longs.isEmpty()){
            return Result.ok(0);
        }
        Long number = longs.get(0);
        if(number == null || number == 0){
            return Result.ok(0);
        }
        //计算签到结果 最长连续签到数
        int count = 0;
        while (true){
            if((number & 1) == 1){
                //签到了
                count++;
            }else{
                //断签了
                break;
            }
            number = number >> 1;
        }
        return Result.ok(count);
    }

    /**
     * 新用户创建
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        user.setCreateTime(LocalDateTime.now());
        //保存
        save(user);
        return user;
    }
}
