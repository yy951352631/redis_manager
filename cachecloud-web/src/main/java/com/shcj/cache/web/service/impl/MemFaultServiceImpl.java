package com.shcj.cache.web.service.impl;

import java.util.List;

import com.shcj.cache.dao.MemFaultDao;
import com.shcj.cache.entity.InstanceFault;
import com.shcj.cache.web.service.MemFaultService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 故障服务
 * @author leifu
 * @Date 2015-6-6
 * @Time 下午10:03:59
 */
@Service("memFaultService")
public class MemFaultServiceImpl implements MemFaultService {

    /**
     * 故障Dao
     */
    @Autowired
    private MemFaultDao memFaultDao;

    @Override
    public List<InstanceFault> getFaultList() {
        return memFaultDao.getMemFaultList();
    }

}
