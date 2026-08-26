package com.shcj.cache.web.service;

import com.shcj.cache.entity.ServerInfo;
import com.shcj.cache.entity.ServerStatus;
import com.shcj.cache.web.controller.api.dto.*;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
public class ServerMonitorApiService {

    @Autowired
    private ServerDataService serverDataService;

    private final DecimalFormat df = new DecimalFormat("0.0");

    public ServerMonitorOverviewDto getOverview(String ip, String date) {
        String searchDate = resolveDate(date);
        ServerMonitorOverviewDto dto = new ServerMonitorOverviewDto();
        dto.setIp(ip);
        dto.setDate(searchDate);
        dto.setServerInfo(toServerInfo(serverDataService.queryServerInfo(ip)));

        List<ServerStatus> list = serverDataService.queryServerOverview(ip, searchDate);
        if (list == null || list.isEmpty()) {
            return dto;
        }

        List<String> xAxis = new ArrayList<>();
        float maxLoad1 = 0;
        double totalLoad1 = 0;
        float maxUser = 0;
        float maxSys = 0;
        float maxWa = 0;
        float curFree = 0;
        float maxUse = 0;
        float maxCache = 0;
        float maxBuffer = 0;
        float maxSwapUse = 0;
        float maxNetIn = 0;
        float maxNetOut = 0;
        int maxConn = 0;
        int maxWait = 0;
        int maxOrphan = 0;
        float maxRead = 0;
        float maxWrite = 0;
        float maxBusy = 0;
        float maxIops = 0;

        List<Float> load1 = new ArrayList<>();
        List<Float> load5 = new ArrayList<>();
        List<Float> load15 = new ArrayList<>();
        List<Float> user = new ArrayList<>();
        List<Float> sys = new ArrayList<>();
        List<Float> wa = new ArrayList<>();
        List<Float> mtotal = new ArrayList<>();
        List<Float> muse = new ArrayList<>();
        List<Float> mcache = new ArrayList<>();
        List<Float> mbuffer = new ArrayList<>();
        List<Float> mswap = new ArrayList<>();
        List<Float> mswapUse = new ArrayList<>();
        List<Float> nin = new ArrayList<>();
        List<Float> nout = new ArrayList<>();
        List<Integer> estab = new ArrayList<>();
        List<Integer> twait = new ArrayList<>();
        List<Integer> orphan = new ArrayList<>();
        List<Float> dread = new ArrayList<>();
        List<Float> dwrite = new ArrayList<>();
        List<Float> dbusy = new ArrayList<>();
        List<Float> diops = new ArrayList<>();

        for (int i = 0; i < list.size(); i++) {
            ServerStatus ss = list.get(i);
            String ctime = ss.getCtime();
            if (ctime != null && ctime.length() >= 4) {
                xAxis.add(ctime.substring(0, 2) + ":" + ctime.substring(2));
            } else {
                xAxis.add(ctime);
            }
            load1.add(ss.getCload1());
            load5.add(ss.getCload5());
            load15.add(ss.getCload15());
            maxLoad1 = getBigger(maxLoad1, ss.getCload1());
            totalLoad1 += ss.getCload1();

            user.add(ss.getCuser());
            sys.add(ss.getCsys());
            wa.add(ss.getCwio());
            maxUser = getBigger(maxUser, ss.getCuser());
            maxSys = getBigger(maxSys, ss.getCsys());
            maxWa = getBigger(maxWa, ss.getCwio());

            mtotal.add(ss.getMtotal());
            float use = ss.getMtotal() - ss.getMfree() - ss.getMcache() - ss.getMbuffer();
            muse.add(use);
            mcache.add(ss.getMcache());
            mbuffer.add(ss.getMbuffer());
            maxUse = getBigger(maxUse, use);
            maxCache = getBigger(maxCache, ss.getMcache());
            maxBuffer = getBigger(maxBuffer, ss.getMbuffer());
            if (i == list.size() - 1) {
                curFree = ss.getMtotal() - use;
            }

            mswap.add(ss.getMswap());
            float swapUse = floor(ss.getMswap() - ss.getMswapFree());
            mswapUse.add(swapUse);
            maxSwapUse = getBigger(maxSwapUse, swapUse);

            nin.add(ss.getNin());
            nout.add(ss.getNout());
            maxNetIn = getBigger(maxNetIn, ss.getNin());
            maxNetOut = getBigger(maxNetOut, ss.getNout());

            estab.add(ss.getTuse());
            twait.add(ss.getTwait());
            orphan.add(ss.getTorphan());
            maxConn = getBigger(maxConn, ss.getTuse());
            maxWait = getBigger(maxWait, ss.getTwait());
            maxOrphan = getBigger(maxOrphan, ss.getTorphan());

            dread.add(ss.getDread());
            dwrite.add(ss.getDwrite());
            dbusy.add(ss.getDbusy());
            diops.add(ss.getDiops());
            maxRead = getBigger(maxRead, ss.getDread());
            maxWrite = getBigger(maxWrite, ss.getDwrite());
            maxBusy = getBigger(maxBusy, ss.getDbusy());
            maxIops = getBigger(maxIops, ss.getDiops());
        }

        dto.getCharts().add(buildChart("load",
                "1-min-max:" + maxLoad1 + " 1-min-avg:" + format(totalLoad1, list.size()),
                xAxis,
                series("1-min", "line", null, load1),
                series("5-min", "line", null, load5),
                series("15-min", "line", null, load15)));

        dto.getCharts().add(buildChart("cpu",
                "max user:" + maxUser + "% sys:" + maxSys + "% wa:" + maxWa + "%",
                xAxis,
                series("user", "line", null, user),
                series("sys", "line", null, sys),
                series("wa", "line", null, wa)));

        dto.getCharts().add(buildChart("memory",
                "now free:" + format(curFree, 1024) + "G max use:" + format(maxUse, 1024)
                        + "G cache:" + format(maxCache, 1024) + "G buffer:" + format(maxBuffer, 1024) + "G",
                xAxis,
                series("total", "line", null, mtotal),
                series("use", "area", null, muse),
                series("cache", "area", null, mcache),
                series("buffer", "area", null, mbuffer)));

        dto.getCharts().add(buildChart("swap",
                "max use:" + maxSwapUse + "M",
                xAxis,
                series("total", "line", null, mswap),
                series("use", "line", null, mswapUse)));

        dto.getCharts().add(buildChart("net",
                "max in:" + format(maxNetIn, 1024) + "M/s out:" + format(maxNetOut, 1024) + "M/s",
                xAxis,
                series("in", "line", null, nin),
                series("out", "line", null, nout)));

        dto.getCharts().add(buildChart("tcp connection",
                "max estab:" + maxConn + " tw:" + maxWait + " orphan:" + maxOrphan,
                xAxis,
                series("established", "line", null, toNumberList(estab)),
                series("time wait", "line", null, toNumberList(twait)),
                series("orphan", "line", null, toNumberList(orphan))));

        ServerMonitorChartDto diskChart = buildChart("disk",
                "max read:" + format(maxRead, 1024) + "M/s write:" + format(maxWrite, 1024)
                        + "M/s busy:" + maxBusy + "% iops:" + maxIops + "次/s",
                xAxis,
                series("read", "bar", 0, dread),
                series("write", "bar", 0, dwrite),
                series("busy", "line", 1, dbusy),
                series("iops", "line", 2, diops));
        diskChart.setYAxisCount(3);
        dto.getCharts().add(diskChart);
        return dto;
    }

    public ServerMonitorTabDto getCpu(String ip, String date) {
        String searchDate = resolveDate(date);
        ServerMonitorTabDto dto = baseTab(ip, searchDate);
        List<ServerStatus> list = serverDataService.queryServerCpu(ip, searchDate);
        if (list == null || list.isEmpty()) {
            dto.setEmpty(true);
            return dto;
        }

        Map<String, CpuChart> subcpuMap = new TreeMap<>();
        List<String> xAxis = new ArrayList<>();
        for (ServerStatus ss : list) {
            xAxis.add(ss.getCtime());
            String subcpuString = ss.getCExt();
            if (subcpuString == null) {
                continue;
            }
            for (String subcpu : subcpuString.split(";")) {
                if (StringUtils.isEmpty(subcpu)) {
                    continue;
                }
                String[] cpu = subcpu.split(",");
                CpuChart cpuChart = subcpuMap.computeIfAbsent(cpu[0], CpuChart::new);
                float user = NumberUtils.toFloat(cpu[1]);
                float sys = NumberUtils.toFloat(cpu[2]);
                float wa = NumberUtils.toFloat(cpu[3]);
                cpuChart.user.add(user);
                cpuChart.sys.add(sys);
                cpuChart.wa.add(wa);
                cpuChart.maxUser = getBigger(cpuChart.maxUser, user);
                cpuChart.maxSys = getBigger(cpuChart.maxSys, sys);
                cpuChart.maxWa = getBigger(cpuChart.maxWa, wa);
                cpuChart.totalUser += user;
                cpuChart.totalSys += sys;
                cpuChart.totalWa += wa;
                cpuChart.count++;
            }
        }

        if (subcpuMap.isEmpty()) {
            dto.setEmpty(true);
            return dto;
        }

        for (CpuChart item : subcpuMap.values()) {
            dto.getCharts().add(buildChart(item.name,
                    "max user:" + item.maxUser + "% sys:" + item.maxSys + "% wa:" + item.maxWa + "% avg user:"
                            + format(item.totalUser, item.count) + "% sys:" + format(item.totalSys, item.count)
                            + "% wa:" + format(item.totalWa, item.count) + "%",
                    xAxis,
                    series("user", "line", null, item.user),
                    series("sys", "line", null, item.sys),
                    series("wa", "line", null, item.wa)));
        }
        return dto;
    }

    public ServerMonitorTabDto getNet(String ip, String date) {
        String searchDate = resolveDate(date);
        ServerMonitorTabDto dto = baseTab(ip, searchDate);
        List<ServerStatus> list = serverDataService.queryServerNet(ip, searchDate);
        if (list == null || list.isEmpty()) {
            dto.setEmpty(true);
            return dto;
        }

        Map<String, NetChart> subnetMap = new TreeMap<>();
        List<String> xAxis = new ArrayList<>();
        for (ServerStatus ss : list) {
            xAxis.add(ss.getCtime());
            addNetMap(ss.getNinExt(), subnetMap, true);
            addNetMap(ss.getNoutExt(), subnetMap, false);
        }

        if (subnetMap.isEmpty()) {
            dto.setEmpty(true);
            return dto;
        }

        for (NetChart item : subnetMap.values()) {
            dto.getCharts().add(buildChart(item.name,
                    "max in:" + item.maxIn + "k/s out:" + item.maxOut + "k/s avg in:" + format(item.totalIn, item.inData.size())
                            + "k/s out:" + format(item.totalOut, item.outData.size()) + "k/s",
                    xAxis,
                    series("in", "line", null, item.inData),
                    series("out", "line", null, item.outData)));
        }
        return dto;
    }

    public ServerMonitorTabDto getDisk(String ip, String date) {
        String searchDate = resolveDate(date);
        ServerMonitorTabDto dto = baseTab(ip, searchDate);
        List<ServerStatus> list = serverDataService.queryServerDisk(ip, searchDate);
        if (list == null || list.isEmpty()) {
            dto.setEmpty(true);
            return dto;
        }

        DiskChart readChart = new DiskChart();
        DiskChart writeChart = new DiskChart();
        DiskChart busyChart = new DiskChart();
        DiskChart iopsChart = new DiskChart();
        DiskChart spaceChart = new DiskChart();
        List<String> xAxis = new ArrayList<>();

        for (ServerStatus ss : list) {
            xAxis.add(ss.getCtime());
            String dext = ss.getDExt();
            if (!StringUtils.isEmpty(dext)) {
                for (String item : dext.split(";")) {
                    String[] sds = item.split("=");
                    if (sds.length == 2) {
                        if ("DISKXFER".equals(sds[0])) {
                            addToChart(sds[1], iopsChart);
                        } else if ("DISKREAD".equals(sds[0])) {
                            addToChart(sds[1], readChart);
                        } else if ("DISKWRITE".equals(sds[0])) {
                            addToChart(sds[1], writeChart);
                        } else if ("DISKBUSY".equals(sds[0])) {
                            addToChart(sds[1], busyChart);
                        }
                    }
                }
            }
            addToChart(ss.getDspace(), spaceChart);
        }

        dto.getCharts().add(diskPartitionChart("read", readChart, xAxis, "k/s"));
        dto.getCharts().add(diskPartitionChart("write", writeChart, xAxis, "k/s"));
        dto.getCharts().add(diskPartitionChart("busy", busyChart, xAxis, "%"));
        dto.getCharts().add(diskPartitionChart("iops", iopsChart, xAxis, "次/s"));
        dto.getCharts().add(diskPartitionChart("space use", spaceChart, xAxis, "%"));
        dto.setEmpty(dto.getCharts().isEmpty());
        return dto;
    }

    private ServerMonitorTabDto baseTab(String ip, String date) {
        ServerMonitorTabDto dto = new ServerMonitorTabDto();
        dto.setIp(ip);
        dto.setDate(date);
        return dto;
    }

    private ServerMonitorServerInfoDto toServerInfo(ServerInfo info) {
        if (info == null) {
            return null;
        }
        ServerMonitorServerInfoDto dto = new ServerMonitorServerInfoDto();
        dto.setIp(info.getIp());
        dto.setHost(info.getHost());
        dto.setCpus(info.getCpus());
        dto.setNmon(info.getNmon());
        dto.setCpuModel(info.getCpuModel());
        dto.setDist(info.getDist());
        dto.setKernel(info.getKernel());
        String ulimit = info.getUlimit();
        if (!StringUtils.isEmpty(ulimit)) {
            String[] tmp = ulimit.split(";");
            if (tmp.length == 2) {
                String[] a = tmp[0].split(",");
                if (a.length == 2 && "f".equals(a[0])) {
                    dto.setMaxFile(a[1]);
                }
                a = tmp[1].split(",");
                if (a.length == 2 && "p".equals(a[0])) {
                    dto.setMaxProcs(a[1]);
                }
            }
        }
        return dto;
    }

    private ServerMonitorChartDto diskPartitionChart(String title, DiskChart chart, List<String> xAxis, String unit) {
        ServerMonitorChartDto dto = new ServerMonitorChartDto();
        dto.setTitle(title);
        dto.setSubtitle("max:" + chart.max + unit + " avg:" + format(chart.total, chart.pointCount) + unit);
        dto.setCategories(xAxis);
        for (Map.Entry<String, List<Float>> entry : chart.seriesMap.entrySet()) {
            dto.getSeries().add(series(entry.getKey(), "line", null, entry.getValue()));
        }
        return dto;
    }

    private void addNetMap(String netString, Map<String, NetChart> subnetMap, boolean isIn) {
        if (netString == null) {
            return;
        }
        for (String subnet : netString.split(";")) {
            if (StringUtils.isEmpty(subnet)) {
                continue;
            }
            String[] net = subnet.split(",");
            NetChart netChart = subnetMap.computeIfAbsent(net[0], NetChart::new);
            float v = NumberUtils.toFloat(net[1]);
            if (isIn) {
                netChart.inData.add(v);
                netChart.totalIn += v;
                netChart.maxIn = getBigger(netChart.maxIn, v);
            } else {
                netChart.outData.add(v);
                netChart.totalOut += v;
                netChart.maxOut = getBigger(netChart.maxOut, v);
            }
        }
    }

    private void addToChart(String line, DiskChart chart) {
        if (StringUtils.isEmpty(line)) {
            return;
        }
        for (String part : line.split(",")) {
            if (StringUtils.isEmpty(part)) {
                continue;
            }
            String[] values = part.split(":");
            if (values.length < 2) {
                continue;
            }
            float d = NumberUtils.toFloat(values[1]);
            chart.seriesMap.computeIfAbsent(values[0], k -> new ArrayList<>()).add(d);
            chart.max = getBigger(chart.max, d);
            chart.total += d;
            chart.pointCount++;
        }
    }

    private ServerMonitorChartDto buildChart(String title, String subtitle, List<String> categories,
                                             ServerMonitorChartSeriesDto... items) {
        ServerMonitorChartDto chart = new ServerMonitorChartDto();
        chart.setTitle(title);
        chart.setSubtitle(subtitle);
        chart.setCategories(categories);
        int maxAxis = 0;
        for (ServerMonitorChartSeriesDto item : items) {
            chart.getSeries().add(item);
            if (item.getYAxisIndex() != null && item.getYAxisIndex() > maxAxis) {
                maxAxis = item.getYAxisIndex();
            }
        }
        chart.setYAxisCount(maxAxis + 1);
        return chart;
    }

    private ServerMonitorChartSeriesDto series(String name, String type, Integer yAxisIndex, List<?> data) {
        ServerMonitorChartSeriesDto dto = new ServerMonitorChartSeriesDto();
        dto.setName(name);
        dto.setType(type);
        dto.setYAxisIndex(yAxisIndex);
        for (Object value : data) {
            if (value instanceof Number) {
                dto.getData().add((Number) value);
            }
        }
        return dto;
    }

    private List<Number> toNumberList(List<Integer> values) {
        List<Number> result = new ArrayList<>();
        for (Integer value : values) {
            result.add(value);
        }
        return result;
    }

    private String resolveDate(String date) {
        if (!StringUtils.isBlank(date)) {
            return date.trim();
        }
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    }

    private String format(double a, int b) {
        if (b <= 0) {
            return "0";
        }
        return df.format(a / b);
    }

    private float getBigger(float a, float b) {
        return a > b ? a : b;
    }

    private int getBigger(int a, int b) {
        return a > b ? a : b;
    }

    private float floor(float v) {
        return new BigDecimal(v).setScale(1, BigDecimal.ROUND_HALF_UP).floatValue();
    }

    private static class CpuChart {
        private final String name;
        private final List<Float> user = new ArrayList<>();
        private final List<Float> sys = new ArrayList<>();
        private final List<Float> wa = new ArrayList<>();
        private float maxUser;
        private float maxSys;
        private float maxWa;
        private float totalUser;
        private float totalSys;
        private float totalWa;
        private int count;

        private CpuChart(String name) {
            this.name = name;
        }
    }

    private static class NetChart {
        private final String name;
        private final List<Float> inData = new ArrayList<>();
        private final List<Float> outData = new ArrayList<>();
        private float maxIn;
        private float maxOut;
        private float totalIn;
        private float totalOut;

        private NetChart(String name) {
            this.name = name;
        }
    }

    private static class DiskChart {
        private final Map<String, List<Float>> seriesMap = new TreeMap<>();
        private float max;
        private float total;
        private int pointCount;
    }
}
