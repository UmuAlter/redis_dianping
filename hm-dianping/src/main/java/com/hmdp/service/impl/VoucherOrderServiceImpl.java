package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIDWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 *  服务实现类
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIDWorker redisIDWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    /**
     * 优惠卷下单
     * 返回登录id
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        //查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //判断
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        Integer stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }

        Long ID = UserHolder.getUser().getId();

//        synchronized(ID.toString().intern() ) {
//            //获取事务代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }

        //创建锁对象
//        SimpleRedisLock lock = new SimpleRedisLock("order:" + ID, stringRedisTemplate);
        RLock lock = redissonClient.getLock("order:" + ID);
        //获取锁
        boolean success = lock.tryLock();
        if(!success){
            return Result.fail("不允许重复下单");
        }
        try {
            //获取事务代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            //释放
            lock.unlock();
        }
    }

    //创建优惠卷订单
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long ID = UserHolder.getUser().getId();
        //一人一单
        int count = query()
                .eq("user_id", ID)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            return Result.fail("用户已经购买过一次了");
        }
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //创建一个订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //代金卷id
        voucherOrder.setVoucherId(voucherId);
        //订单id
        long orderId = redisIDWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);

        save(voucherOrder);
        return Result.ok(orderId);
    }
}
