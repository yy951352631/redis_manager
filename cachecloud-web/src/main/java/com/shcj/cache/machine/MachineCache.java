package com.shcj.cache.machine;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.shcj.cache.common.Tuple;
import com.shcj.cache.dao.MachineDao;
import com.shcj.cache.entity.MachineInfo;
import com.shcj.cache.exception.BizException;
import com.shcj.cache.task.util.SpringContextUtil;
import com.shcj.cache.web.util.AESCoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

/**
 * 机器信息缓存类
 *
 * @author zoushunqing 2023/2/21 14:24
 * @since Dev_1.0.1
 */
@Component
public class MachineCache {

    private static String key = "97c9d9de0a2dbd64";

    private static Cache<String, MachineInfo> machineInfoCache;

    @Autowired
    private MachineDao machineDao;

    @PostConstruct
    public void init() {
        machineInfoCache = CacheBuilder.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .recordStats()
                .build(new CacheLoader<String, MachineInfo>() {
                    @Override
                    public MachineInfo load(String ip) {
                        return machineDao.getMachineInfoByIp(ip);
                    }
                });
    }

    public static Tuple<String, String> getCredByIp(String ip) {
        MachineInfo machineInfo;
        try {
            machineInfo = machineInfoCache.get(ip, () -> {
                MachineDao machineDao = (MachineDao) SpringContextUtil.getBeanByClass(MachineDao.class);
                MachineInfo infoByIp = machineDao.getMachineFullInfoByIp(ip);
                infoByIp.setSshPasswd(AESCoder.decrypt(infoByIp.getSshPasswd(), key));
                return infoByIp;
            });
        } catch (Exception ex) {
            throw new BizException("获取ip={}的机器信息缓存失败，maybe密码设置失败", ip, ex);
        }
        if (machineInfo == null) {
            throw new BizException("没有ip={}的机器", ip);
        }

        return new Tuple<>(machineInfo.getSshUser(), machineInfo.getSshPasswd());
    }

    public static String getUser(String ip) {
        return getCredByIp(ip).v1();
    }

    public static String getPwd(String ip) {
        return getCredByIp(ip).v2();
    }

    public static void refresh() {
        machineInfoCache.cleanUp();
    }

    public String getEncrytPwd(String plaintext) throws Exception {
        return AESCoder.encrypt(plaintext, key);
    }
}
