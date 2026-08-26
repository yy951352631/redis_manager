package com.shcj.cache.constant;

import com.shcj.cache.util.StringUtil;

/**
 * @Author: rucao
 * @Date: 2020/6/9 17:11
 */
public enum DiagnosticTypeEnum {
    SCAN_KEY(0, "scan", "扫描键", "AppScanKeyTask"),
    // type 1(memoryUsed) / 2(idlekey) / 3(hotkey) 已下线，key 分析统一走「键值分析」。
    // 序号不复用：diagnostic_task_record 里仍有这三类的历史行，重新占用会让旧数据显示成别的类型。
    DEL_KEY(4, "deleteKey", "删除键", "AppDelKeyTask"),
    SLOT_ANALYSIS(5, "slotAnalysis", "集群槽分析", "AppSlotAnalysisTask"),
    SCAN_CLEAN(6, "scanClean", "数据分析清理", "AppScanCleanKeyTask");

    int type;
    String desc;
    String more;
    /**
     * 提交后落在 task_queue 里的父任务类名。父任务负责把集群拆成每个节点一个子任务，
     * 子任务真正开跑时才会写 diagnostic_task_record，所以查询"执行中"的任务要靠它。
     */
    String parentTaskClassName;

    DiagnosticTypeEnum(int type, String desc, String more, String parentTaskClassName) {
        this.type = type;
        this.desc = desc;
        this.more = more;
        this.parentTaskClassName = parentTaskClassName;
    }

    public String getParentTaskClassName() {
        return parentTaskClassName;
    }

    public static DiagnosticTypeEnum getByType(int type) {
        for (DiagnosticTypeEnum e : DiagnosticTypeEnum.values()) {
            if (e.getType() == type) {
                return e;
            }
        }
        return null;
    }

    public int getType() {
        return type;
    }

    public String getDesc() {
        return desc;
    }

    public String getMore() {
        return more;
    }

    public static int getDescKey(String desc) {
        for (DiagnosticTypeEnum diagnosticTypeEnum : DiagnosticTypeEnum.values()) {
            if (!StringUtil.isBlank(desc) && diagnosticTypeEnum.getDesc().equals(desc)) {
                return diagnosticTypeEnum.getType();
            }
        }
        return -1;
    }

    public static String getKeyDesc(int type) {
        for (DiagnosticTypeEnum diagnosticTypeEnum : DiagnosticTypeEnum.values()) {
            if (type == diagnosticTypeEnum.getType()) {
                return diagnosticTypeEnum.getDesc();
            }
        }
        return "";
    }
}
