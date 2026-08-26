package com.shcj.cache.dao;

import com.shcj.cache.entity.InstanceRuntimeProfile;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InstanceRuntimeProfileDao {

    int upsert(InstanceRuntimeProfile profile);

    List<InstanceRuntimeProfile> listAll();
}
