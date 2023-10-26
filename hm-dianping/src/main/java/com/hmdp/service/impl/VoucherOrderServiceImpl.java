package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *  服务实现类
 */
@Slf4j
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


    //线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //确保一旦初始化结束就开始执行线程处理
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //处理线程的处理器
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                try {
                    //从Redis消息队列中取出一个优惠卷订单信息
                    //XREADGROUP g1 .....
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),  //确定消费者组名和消费者的名称
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),    //每次读一个，阻塞两秒
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())   //指定消息队列名称， ReadOffset.lastConsumed()从最新的开始读
                    );
                    //判断消息获取是否成功
                    if(read == null || read.isEmpty()){
                        //获取失败
                        continue;
                    }
                    //解析消息 String 为标识， 存入时存的就是键值对形式
                    MapRecord<String, Object, Object> record = read.get(0);    //每次只读1个，用get（0）就可以拿到
                    //拿到存入的键值对
                    Map<Object, Object> value = record.getValue();
                    //转成订单对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //下单
                    handlerVoucherOrder(voucherOrder);
                    //ACK确认 SACK 队列名称 消费者组 消息id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                }catch (Exception e){
                    log.error("处理订单异常");
                    //处理异常消息
                    handlePendingList();
                }
            }
        }
        //处理异常消息
        private void handlePendingList() {
            while (true){
                try {
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),  //确定消费者组名和消费者的名称
                            StreamReadOptions.empty().count(1),    //每次读一个
                            StreamOffset.create(queueName, ReadOffset.from("0"))   //指定消息队列名称， 0
                    );
                    //判断消息获取是否成功
                    if(read == null || read.isEmpty()){
                        //获取失败,说明pending list无消息
                        break;
                    }
                    //解析消息 String 为标识， 存入时存的就是键值对形式
                    MapRecord<String, Object, Object> record = read.get(0);    //每次只读1个，用get（0）就可以拿到
                    //拿到存入的键值对
                    Map<Object, Object> value = record.getValue();
                    //转成订单对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //下单
                    handlerVoucherOrder(voucherOrder);
                    //ACK确认 SACK 队列名称 消费者组 消息id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                }catch (Exception e){
                    log.error("pending-list读取异常");
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }
//    //阻塞队列
//    private BlockingQueue<VoucherOrder> blockingQueue  = new ArrayBlockingQueue<>(1024*1024);
//    //处理线程的处理器
//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while (true){
//                try {
//                    //取出一个优惠卷订单信息
//                    VoucherOrder order = blockingQueue.take();
//                    //创建订单
//                    handlerVoucherOrder(order);
//                }catch (Exception e){
//                    log.error("处理订单异常");
//                }
//            }
//        }
//    }
    //主线程的代理对象

    private IVoucherOrderService proxy;
    //脚本
    private static final DefaultRedisScript<Long> seckill_script;

    static {
        seckill_script = new DefaultRedisScript<>();
        seckill_script.setLocation(new ClassPathResource("seckill.lua"));
        seckill_script.setResultType(Long.class);
    }

    /**
     * 利用lua脚本完成对优惠卷下单的操作
     * @param voucherId
     * @return
     */
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //获得订单id
        long order = redisIDWorker.nextId("order");
        //执行脚本
        Long result = stringRedisTemplate.execute(
                seckill_script,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(order)
        );
        //判断结果
        int i = result.intValue();
        if( i != 0){
            if(i == 1){
                return Result.fail("库存不足");
            }else {
                return Result.fail("不允许重复下单");
            }
        }

//        //保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setId(order);

        //获取事务代理对象--只能在主线程中获取
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //添加到阻塞队列
        //blockingQueue.add(voucherOrder);

        //返回id
        return Result.ok(order);
    }

    //处理订单
    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        //多线程情况下无法从主线程中取userId
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("order:" + userId);
        //获取锁
        boolean success = lock.tryLock();
        if(!success){
            log.error("");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            //释放
            lock.unlock();
        }
    }

//    /**
//     * 优惠卷下单
//     * 返回登录id
//     * @param voucherId
//     * @return
//     */
//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀尚未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束");
//        }
//        Integer stock = voucher.getStock();
//        if (stock < 1) {
//            return Result.fail("库存不足");
//        }
//
//        Long ID = UserHolder.getUser().getId();
//
////        synchronized(ID.toString().intern() ) {
////            //获取事务代理对象
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        //创建锁对象
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + ID, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order:" + ID);
//        //获取锁
//        boolean success = lock.tryLock();
//        if(!success){
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            //获取事务代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }finally {
//            //释放
//            lock.unlock();
//        }
//    }

    //创建优惠卷订单
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long ID = voucherOrder.getUserId();
        //一人一单
        int count = query()
                .eq("user_id", ID)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count();
        if (count > 0) {
            log.error("用户已经购买过一次了");
            return;
        }
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }
//        //创建一个订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        //代金卷id
//        voucherOrder.setVoucherId(voucherId);
//        //订单id
//        long orderId = redisIDWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        //用户id
//        Long userId = UserHolder.getUser().getId();
//        voucherOrder.setUserId(userId);

        save(voucherOrder);
    }
}
