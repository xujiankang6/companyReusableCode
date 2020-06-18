package com.forceclouds.crm.local.schedule

import com.forceclouds.context.ContextHolder
import com.forceclouds.context.IContext
import com.forceclouds.crm.data.model.Criteria
import com.forceclouds.crm.data.model.IDataRecord
import com.forceclouds.crm.data.model.Query
import com.forceclouds.crm.data.repository.IDataRecordRepository
import com.forceclouds.crm.data.service.IDataRecordService
import com.forceclouds.crm.metadata.model.IObjectDescribe
import com.forceclouds.crm.metadata.service.IObjectDescribeService
import com.forceclouds.crm.scheduler.CRMJob
import com.forceclouds.crm.trigger.support.AlertUtils
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment

import java.time.*

/*
*@create by jiankang
*@date 2019/11/12 time 11:21
*/

class SendEmailFromEventJob implements CRMJob {

    def logger = LoggerFactory.getLogger(SendEmailFromEventJob.class)
    def dataRecordRepository
    def dataRecordService
    def objectDescribeService
    private Environment env;

    @Override
    void execute(Map<String, Object> params) {
        dataRecordRepository = getBean(IDataRecordRepository.class) as IDataRecordRepository
        dataRecordService = getBean(IDataRecordService.class) as IDataRecordService
        objectDescribeService = getBean(IObjectDescribeService.class) as IObjectDescribeService
        env = getBean(Environment.class)
        def result = queryNeedSentEmail() as List<IDataRecord>
        if (result) {
            result.each {
                def record = it as IDataRecord
                sendEmail(record)
            }
        }
    }

    def sendEmail(IDataRecord record) {
        def create_by = record?.create_by as Long
        def user_info = dataRecordRepository.findById(ContextHolder.getHolder().get() as IContext, objectDescribeService.findByApiName("user_info") as IObjectDescribe, create_by) as IDataRecord
        Map<String, Object> emailTemplate = new HashMap<String, Object>()
        emailTemplate.put("subject", "【培训管理系统通知：活动执行已结束，请及时维护】")
        def address = getProfilePreAddress() + record?.id + "/detail_page" as String
        def eventName = "<a href='" + address + "' target=\"_blank\" title='【" + record?.name + "】'>" + record?.name + "</a>" as String
        emailTemplate.put("content", "您的【" + eventName + "】培训活动执行结束已超过三天，请尽快维护活动信息，完成该培训。 ")
        AlertUtils.sendEmail([:], emailTemplate, user_info)
    }

    def getProfilePreAddress() {
        String active = env.getProperty("spring.profiles.active");
        switch (active) {
            case "dev": return "https://dev.crmpower.cn/#/object_page/event/"
                break
            case "stage": return "https://stg.crmpower.cn/#/object_page/event/"
                break
            case "prod": return "https://prod.crmpower.cn/#/object_page/event/"
                break
            default:
                break
        }
    }


    def queryNeedSentEmail() {
        def query = new Query()
        query.setPageNo(1)
        query.setPageSize(Integer.MAX_VALUE)
        query.setJoiner("and")
        query.setObjectApiName("event")
        query.getCriterias().add(new Criteria("event_status", "<>", "已完成"))
        query.getCriterias().add(new Criteria("is_deleted", "==", false))
        query.getCriterias().add(new Criteria("real_end_time", "<=", daysToSubtractThreeTimestamp()))
        def result = dataRecordRepository.query(query).result as List<IDataRecord>
        if (result)
            return result
        null
    }


    //发送邮件只需改动这里
    def daysToSubtractThreeTimestamp() {
        String active = env.getProperty("spring.profiles.active");
        if("stage".equals(active)){
            ZoneId zone = ZoneId.of("UTC+0");
            LocalDateTime startTime = LocalDateTime.of(LocalDate.now().minusDays(3), LocalTime.now());
            Instant startinstant = startTime.toInstant(ZoneOffset.of("+0"));
            LocalDateTime startDateTime = LocalDateTime.ofInstant(startinstant, zone);
            long startTimestamp = startDateTime.atZone(zone).toInstant().toEpochMilli();
            return startTimestamp
        }else{
            ZoneId zone = ZoneId.of("UTC+8");
            LocalDateTime startTime = LocalDateTime.of(LocalDate.now().minusDays(3), LocalTime.now());
            Instant startinstant = startTime.toInstant(ZoneOffset.of("+8"));
            LocalDateTime startDateTime = LocalDateTime.ofInstant(startinstant, zone);
            long startTimestamp = startDateTime.atZone(zone).toInstant().toEpochMilli();
            return startTimestamp
        }

    }

}