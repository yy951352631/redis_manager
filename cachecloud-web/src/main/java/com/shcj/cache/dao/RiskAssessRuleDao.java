package com.shcj.cache.dao;

import com.shcj.cache.entity.RiskAssessRule;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiskAssessRuleDao {

    List<RiskAssessRule> listAll();

    List<RiskAssessRule> listByDimension(@Param("dimension") String dimension);

    int insertIfAbsent(RiskAssessRule rule);

    int update(RiskAssessRule rule);

    int count();
}
