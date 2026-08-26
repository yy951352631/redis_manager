package com.shcj.cache.entity;

import lombok.Data;

@Data
public class MachineInstanceStat {

	private String ip;

    private long maxMemory;
	
	private long usedMemory;

}