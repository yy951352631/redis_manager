package com.shcj.cache.risk.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.shcj.cache.dao.StandardStatsDao;
import com.shcj.cache.entity.InstanceInfo;
import com.shcj.cache.entity.StandardStats;
import com.shcj.cache.util.ConstUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 {@code instance_minute_stats} 读取窗口内的命令调用次数增量。
 *
 * <p>该表存的是每分钟的差值（保留 5 天），且包含<b>全部</b>命令的 cmdstat_*，
 * 正好满足危险命令维度的需要——不必依赖只存白名单命令的新表。
 */
@Component
public class RiskCommandCountLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(RiskCommandCountLoader.class);

    private static final String CMD_PREFIX = "cmdstat_";

    @Autowired
    private StandardStatsDao standardStatsDao;

    /**
     * @return instanceId -> command -> 窗口内调用次数
     */
    public Map<Long, Map<String, Long>> load(List<InstanceInfo> instances, long beginTime, long endTime) {
        Map<Long, Map<String, Long>> result = new HashMap<>();
        for (InstanceInfo instance : instances) {
            try {
                List<StandardStats> rows = standardStatsDao.getDiffJsonList(
                        beginTime, endTime, instance.getIp(), instance.getPort(), ConstUtils.REDIS);
                if (rows == null || rows.isEmpty()) {
                    continue;
                }
                Map<String, Long> counts = new HashMap<>();
                for (StandardStats row : rows) {
                    accumulate(counts, row.getDiffJson());
                }
                if (!counts.isEmpty()) {
                    result.put((long) instance.getId(), counts);
                }
            } catch (Exception e) {
                LOGGER.warn("load command counts failed {}:{}: {}",
                        instance.getIp(), instance.getPort(), e.getMessage());
            }
        }
        return result;
    }

    private void accumulate(Map<String, Long> counts, String diffJson) {
        if (StringUtils.isBlank(diffJson)) {
            return;
        }
        JSONObject json = JSON.parseObject(diffJson);
        for (String key : json.keySet()) {
            if (key == null || !key.startsWith(CMD_PREFIX)) {
                continue;
            }
            Long value = json.getLong(key);
            if (value == null || value <= 0) {
                continue;
            }
            counts.merge(key.substring(CMD_PREFIX.length()).toLowerCase(), value, Long::sum);
        }
    }
}
