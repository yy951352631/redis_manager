package com.shcj.cache.dao;

import com.shcj.cache.task.entity.TaskStepMeta;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TaskStepMetaDao {

    /**
     * @param taskStepMeta
     */
    void save(TaskStepMeta taskStepMeta);

    /**
     * @param className
     * @return
     */
    List<TaskStepMeta> getTaskStepMetaList(@Param("className") String className);

}
