package com.shcj.cache.entity;

import lombok.Data;

/**
 * 命令分布
 */
@Data
public class AppCommandGroup {
	/**
	 * 命令名
	 */
	private String commandName;
	
	/**
	 * 调用次数
	 */
	private long count;
	
}
