package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.print.attribute.standard.MediaSize;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;    //业务名称
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> unlock_script;

    static {
        unlock_script = new DefaultRedisScript<>();
        unlock_script.setLocation(new ClassPathResource("unlock.lua"));
        unlock_script.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String key = KEY_PREFIX + name;
        //获取线程的唯一标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        Boolean absent = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);
        //解决自动拆箱导致的空指针问题
        return Boolean.TRUE.equals(absent);
    }

    //调用lua脚本的方法
    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                unlock_script,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
                );
    }

//    @Override
//    public void unlock() {
//        //获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁内标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//
//        if(threadId.equals(id)) {
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
