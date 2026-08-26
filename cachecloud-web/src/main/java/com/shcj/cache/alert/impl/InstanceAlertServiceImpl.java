package com.shcj.cache.alert.impl;

import com.shcj.cache.alert.InstanceAlertService;
import com.shcj.cache.dao.InstanceFaultDao;
import com.shcj.cache.entity.InstanceFault;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 实例报警
 *
 * @author leifu
 */
@Service
public class InstanceAlertServiceImpl implements InstanceAlertService {

    @Autowired
    private InstanceFaultDao instanceFaultDao;

    @Override
    public List<InstanceFault> getListByInstId(int instId) {
        return instanceFaultDao.getListByInstId(instId);
    }

}
