package com.newegg.ec.redis.plugin.rct.thread;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.newegg.ec.redis.config.RCTConfig;
import com.newegg.ec.redis.entity.*;
import com.newegg.ec.redis.plugin.rct.cache.AppCache;
import com.newegg.ec.redis.plugin.rct.report.EmailSendReport;
import com.newegg.ec.redis.plugin.rct.report.IAnalyzeDataConverse;
import com.newegg.ec.redis.plugin.rct.report.converseFactory.ReportDataConverseFacotry;
import com.newegg.ec.redis.service.impl.RdbAnalyzeResultService;
import com.newegg.ec.redis.service.impl.RdbAnalyzeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * @author：Truman.P.Du
 * @createDate: 2018年10月19日 下午1:48:52
 * @version:1.0
 * @description:
 */
public class AnalyzerStatusThread implements Runnable {
	private static final Logger LOG = LoggerFactory.getLogger(AnalyzerStatusThread.class);
	private List<ScheduleDetail> scheduleDetails;
	private List<AnalyzeInstance> analyzeInstances;
	private RestTemplate restTemplate;
	private RDBAnalyze rdbAnalyze;
	private RCTConfig.Email emailInfo;
	private RdbAnalyzeResultService rdbAnalyzeResultService;
	private RdbAnalyzeService rdbAnalyzeService;

	public AnalyzerStatusThread(RdbAnalyzeService rdbAnalyzeService, RestTemplate restTemplate,
								RDBAnalyze rdbAnalyze, RCTConfig.Email emailInfo, RdbAnalyzeResultService rdbAnalyzeResultService) {
		this.rdbAnalyzeService = rdbAnalyzeService;
		this.restTemplate = restTemplate;
		this.rdbAnalyze = rdbAnalyze;
		this.emailInfo = emailInfo;
		this.rdbAnalyzeResultService = rdbAnalyzeResultService;
	}

	@Override
	public void run() {
		JSONObject res = rdbAnalyzeService.assignAnalyzeJob(rdbAnalyze);
		Long scheduleID =  res.getLongValue("scheduleID");
		if(!res.getBoolean("status")){
			LOG.warn("Assign job fail.");
			// 执行失败，删除任务
			AppCache.scheduleDetailMap.remove(rdbAnalyze.getId());
			if(scheduleID != 0L){
				rdbAnalyzeService.deleteResult(rdbAnalyze, scheduleID);
			}
			return;
		}
		this.analyzeInstances = (List<AnalyzeInstance>)res.get("needAnalyzeInstances");
		scheduleDetails = AppCache.scheduleDetailMap.get(rdbAnalyze.getId());
		// 获取所有analyzer运行状态
		while (AppCache.isNeedAnalyzeStastus(rdbAnalyze.getId())) {

			// 更新分析器状态
			rdbAnalyzeService.queryAnalyzeStatus(rdbAnalyze.getId(), analyzeInstances);
			try {
				// 每次循环休眠一次
				Thread.sleep(500);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

		}

		AppCache.scheduleDetailMap.get(rdbAnalyze.getId()).forEach(s -> {
			if(AnalyzeStatus.ERROR.equals(s.getStatus())){
				rdbAnalyzeService.deleteResult(rdbAnalyze, s.getScheduleID());
				return;
			}
		});

		// 当所有analyzer运行完成，获取所有analyzer报表分析结果
		if (AppCache.isAnalyzeComplete(rdbAnalyze)) {
			Map<String, Set<String>> reportData = new HashMap<>();
			Map<String,Long>temp = new HashMap<>();
			for (AnalyzeInstance analyzeInstance : analyzeInstances) {
				try {
					Map<String, Set<String>> instanceReportData = getAnalyzerReportRest(analyzeInstance);
					if (reportData.size() == 0) {
						reportData.putAll(instanceReportData);
					} else {
						for(String key:instanceReportData.keySet()) {
							Set<String> newData = instanceReportData.get(key);
							if(reportData.containsKey(key)) {
								Set<String> oldData = reportData.get(key);
								oldData.addAll(newData);
								reportData.put(key, oldData);
							}else {
								reportData.put(key, newData);
							}
						}
					}
				} catch (Exception e) {
					LOG.error("get analyzer report has error.", e);
				}
			}
			try {
				Map<String, ReportData> latestPrefixData = rdbAnalyzeResultService.getReportDataLatest(rdbAnalyze.getClusterId(), scheduleID);
				Map<String, String> dbResult = new HashMap<>();
				IAnalyzeDataConverse analyzeDataConverse = null;
				for (Map.Entry<String, Set<String>> entry : reportData.entrySet()) {
					analyzeDataConverse = ReportDataConverseFacotry.getReportDataConverse(entry.getKey());
					if (null != analyzeDataConverse) {
						dbResult.putAll(analyzeDataConverse.getMapJsonString(entry.getValue()));
					}
				}
				dbResult = rdbAnalyzeResultService.combinePrefixKey(dbResult);
			    try {
                    rdbAnalyzeResultService.reportDataWriteToDb(rdbAnalyze, dbResult);
                }catch (Exception e) {
                    LOG.error("reportDataWriteToDb has error.", e);
                }
				if(rdbAnalyze.isReport()) {
					EmailSendReport emailSendReport = new EmailSendReport();
					emailSendReport.sendEmailReport(rdbAnalyze, emailInfo, dbResult, latestPrefixData);
				}
			} catch (Exception e) {
				LOG.error("email report has error.", e);
			}finally {
				for (AnalyzeInstance analyzeInstance : analyzeInstances) {
					String url = "http://"+analyzeInstance.getHost()+":"+analyzeInstance.getPort()+"/clear";
					@SuppressWarnings("unused")
                    ResponseEntity<String> responseEntity = restTemplate.getForEntity(url, String.class);
				}
				
			}
		}

	}

	private Map<String, Set<String>> getAnalyzerReportRest(AnalyzeInstance instance) {
		if (null == instance) {
			LOG.warn("analyzeInstance is null!");
			return null;
		}
		// String url = "http://127.0.0.1:8082/report";
		String host = instance.getHost();

		String url = "http://" + host + ":" + instance.getPort() + "/report";
		try {
			ResponseEntity<String> responseEntity = restTemplate.getForEntity(url, String.class);
			String str = responseEntity.getBody();
			if (null == str) {
				LOG.warn("get report URL :" + url + " no response!");
				return null;
			} else {
				return handleAnalyzerReportMessage(str);
			}

		} catch (Exception e) {
			LOG.error("getAnalyzerReportRest failed!", e);
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Set<String>> handleAnalyzerReportMessage(String message) {
		Map<String, JSONArray> reportMessage = (Map<String, JSONArray>) JSON.parse(message);
		Map<String, Set<String>> result = new HashMap<>();
		if (reportMessage != null && reportMessage.size() > 0) {
			for (String type : reportMessage.keySet()) {
				JSONArray array = reportMessage.get(type);
				List<String> list = JSONObject.parseArray(array.toJSONString(), String.class);
				Set<String> set = new HashSet<>(list);
				result.put(type, set);
			}
		}
		return result;
	}

}
