package com.shcj.cache.server.data;

import org.apache.commons.lang.math.NumberUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


/**
 * 网络流量
 */
public class Net implements LineParser{
	public static final String FLAG = "NET,";
	/** nmon 网卡列：{iface}-read-KB/s 或 {iface}-write-KB/s */
	private static final Pattern NET_COLUMN = Pattern.compile(
			"^(lo|eth\\d+|ens\\d+|enp\\S+|eno\\d+|em\\d+|bond\\d+)-(read|write)-KB/s$");
	
	private float nin;
	private float nout;
	private StringBuilder ninDetail = new StringBuilder();
	private StringBuilder noutDetail = new StringBuilder();
	
	private List<NetworkInterfaceCard> ncList = new ArrayList<NetworkInterfaceCard>();
	
	/**
	 * line format:
	 * NET,Network I/O bx-50-13,lo-read-KB/s,eth0-read-KB/s,eth1-read-KB/s,eth2-read-KB/s,eth3-read-KB/s,lo-write-KB/s,eth0-write-KB/s,eth1-write-KB/s,eth2-write-KB/s,eth3-write-KB/s,
	 * NET,T0001,190.3,3317.8,0.0,0.0,0.0,190.3,3377.7,0.0,0.0,0.0,
	 */
	@Override
    public void parse(String line, String timeKey) throws Exception{
		if(line.startsWith(FLAG)) {
			String[] items = line.split(",");
			if(items[1].startsWith("Network")) {
				ncList.clear();
				for(int i = 0; i < items.length; ++i) {
					if(isNetworkInterfaceColumn(items[i])) {
						NetworkInterfaceCard nic = new NetworkInterfaceCard();
						nic.setName(items[i]);
						nic.setIdx(i);
						ncList.add(nic);
					}
				}
			} else {
				if(ncList.isEmpty()) {
					return;
				}
				for(NetworkInterfaceCard nic : ncList) {
					if(nic.getIdx() < items.length) {
						nic.setValue(NumberUtils.toFloat(items[nic.getIdx()]));
					}
				}
				caculate();
			}
		}
	}

	/**
	 * 识别 nmon NET 表头中的网卡列，按常见命名规则分情况匹配：
	 * lo        - 回环
	 * eth*      - 传统命名（CentOS6、老云主机如阿里云 eth0）
	 * ens*      - systemd 可预测命名（CentOS7+ / RHEL7+）
	 * enp*      - PCI 拓扑命名（Ubuntu 18+ 等）
	 * eno*      - 板载网卡命名
	 * em*       - 部分 VMware / 物理机
	 * bond*     - 网卡绑定
	 */
	private boolean isNetworkInterfaceColumn(String column) {
		return column != null && NET_COLUMN.matcher(column).matches();
	}
	
	private void caculate() {
		ninDetail.setLength(0);
		noutDetail.setLength(0);
		float totalIn = 0;
		float totalOut = 0;
		for(NetworkInterfaceCard nic : ncList) {
			String column = nic.getName();
			int dash = column.indexOf('-');
			if(dash <= 0) {
				continue;
			}
			String iface = column.substring(0, dash);
			String direction = column.substring(dash + 1, column.indexOf("-", dash + 1));
			if("read".equals(direction)) {
				ninDetail.append(iface);
				ninDetail.append(",");
				ninDetail.append(nic.getValue());
				ninDetail.append(";");
				totalIn += nic.getValue();
			} else if("write".equals(direction)) {
				noutDetail.append(iface);
				noutDetail.append(",");
				noutDetail.append(nic.getValue());
				noutDetail.append(";");
				totalOut += nic.getValue();
			}
		}
		nin = BigDecimal.valueOf(totalIn).setScale(2, BigDecimal.ROUND_HALF_UP).floatValue();
		nout = BigDecimal.valueOf(totalOut).setScale(2, BigDecimal.ROUND_HALF_UP).floatValue();
	}
	
	public float getNin() {
		return nin;
	}

	public float getNout() {
		return nout;
	}
	public String getNinDetail() {
		return ninDetail.toString();
	}

	public String getNoutDetail() {
		return noutDetail.toString();
	}

	static class NetworkInterfaceCard{
		private String name;
		private float value;
		private int idx;
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public float getValue() {
			return value;
		}
		public void setValue(float value) {
			this.value = value;
		}
		public int getIdx() {
			return idx;
		}
		public void setIdx(int idx) {
			this.idx = idx;
		}
	}
}
