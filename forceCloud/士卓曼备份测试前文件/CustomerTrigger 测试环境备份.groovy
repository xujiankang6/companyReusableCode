//package com.forceclouds.crm.local.straumann

import com.aliyuncs.utils.StringUtils
import com.forceclouds.context.ContextHolder
import com.forceclouds.context.IContext
import com.forceclouds.crm.anno.DebugCondition
import com.forceclouds.crm.anno.ObjectDescribeTrigger
import com.forceclouds.crm.data.model.DataRecord
import com.forceclouds.crm.data.model.IDataRecord
import com.forceclouds.crm.metadata.model.IObjectDescribe
import com.forceclouds.crm.trigger.AbstractTrigger
import com.google.common.collect.Maps
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component
import java.util.stream.Collectors

/**
 * Created by QY.gao 2019.12.11
 */
//@Component
//@ObjectDescribeTrigger(target = 'customer')
//@Conditional(DebugCondition.class)
class CustomerTrigger extends AbstractTrigger{

    @Override
    void beforeUpdate(IContext context, IObjectDescribe objectDescribe, IDataRecord newValue) {

        if("trainee" == newValue.getRecordType()){
            String sapcode = newValue.get("sapcode")
            IDataRecord record = dataRecordService.findById(objectDescribe, newValue.getId())
            if(sapcode!=null && !StringUtils.isEmpty(sapcode) && sapcode != record.get("sapcode")){

                IDataRecord dataRecord = new DataRecord()
                dataRecord.set("sap_code",sapcode)
                dataRecord.set("belong_territory", ContextHolder.territoryId)
                dataRecord.set("trainee_id", record.getId())

                IObjectDescribe objectDescribeSAP = objectDescribeService.findByApiName("sap_date_record")
                dataRecordService.create( objectDescribeSAP, dataRecord)
            }
            IObjectDescribe objectDescribeEvent = objectDescribeService.findByApiName("event_attendee")
//            List<IDataRecord> eventAttendList = dataRecordRepository.findByExample(objectDescribeEvent)
            List<IDataRecord> eventAttendList = queryEventAttendee(context, objectDescribeEvent, newValue.getId())

            if (eventAttendList != null && eventAttendList.size() > 0) {
                IDataRecord eventAttendRecord = new DataRecord()
                eventAttendRecord.put("ated_name", newValue.getName())
                eventAttendRecord.put("ated_gender", newValue.get("gender"))
                eventAttendRecord.put("ated_dep", newValue.get("s_work_dep"))
                eventAttendRecord.put("ated_dep_sapcode", newValue.get("sapcode"))
                eventAttendRecord.put("ated_dep_type", newValue.get("dep_type"))
                for (IDataRecord data : eventAttendList) {
                    data.putAll(eventAttendRecord)
                }
                dataRecordService.batchUpdate(context, objectDescribeEvent, eventAttendList)
            }

        }

    }

    def queryEventAttendee(IContext context,IObjectDescribe objectDescribeEvent, Long Id){
        String tenantId = context.currentUser().getTenantId()
        String sql = "SELECT  ( ext->>'ated_content') as ated_content, ( ext->>'ated_dep') as ated_dep, ( ext->>'ated_dep_sapcode') as ated_dep_sapcode, ( ext->>'ated_dep_type') as ated_dep_type, ( ext->>'ated_gender') as ated_gender, ( ext->>'ated_join_method') as ated_join_method, ( ext->>'ated_name') as ated_name, ( ext->>'ated_phone') as ated_phone, ( ext->>'attendee_event')::bigint as attendee_event, ( ext->>'belonged_region') as belonged_region, belong_territory, ( ext->>'city') as city, client, ( ext->>'company') as company, ( ext->>'contact_name') as contact_name, create_by, create_time, ( ext->>'customer')::bigint as customer, ( ext->>'event_date') as event_date, ( ext->>'event_type') as event_type, external_id, id, is_deleted, last_sync, name, object_describe_id, object_describe_name, ( ext->>'order_number')::bigint as order_number, owner, record_type, ( ext->>'shared_owner')::json as shared_owner, ( ext->>'stundent_event')::bigint as stundent_event, tenant_id, update_by, update_time, version " +
                " FROM \""+tenantId+"\".event_attendee WHERE tenant_id =:tenant_id  and is_deleted = false and  (ext->>'customer')::bigint =:customer and object_describe_id =:object_describe_id "
        Map<String, String> params = Maps.newHashMap()
        params.put("tenant_id",tenantId)
        params.put("customer",Id)
        params.put("object_describe_id",objectDescribeEvent.getId())
        List<IDataRecord> dataRecords = dataRecordRepository.findBySql(sql, params)
        return dataRecords
    }

    @Override
    void beforeBatchUpdate(IContext context, IObjectDescribe objectDescribe, List<IDataRecord> batchRecords) {
        batchUpdateSap(context, objectDescribe, batchRecords)
    }

    def batchUpdateSap(IContext context, IObjectDescribe objectDescribe, List<IDataRecord> batchRecords){
        List<IDataRecord> filterDatchRecords = batchRecords.stream().filter(){e-> e.get("sapcode")!=null && !StringUtils.isEmpty(e.get("sapcode"))}.collect(Collectors.toList())

        if(filterDatchRecords.size() > 0 ){
            List<Long> ids = new ArrayList<>()
            filterDatchRecords.forEach(){e-> ids.add(e.getId())}

            List<IDataRecord> dataRecords = dataRecordRepository.findByIds(context, objectDescribe, ids)
            Map<Long, String> map = new HashMap<>()
            dataRecords.forEach(){e-> map.put(e.getId(), e.get("sapcode"))}

            List<IDataRecord> sapDataRecord = new ArrayList<>()
            for(IDataRecord record : filterDatchRecords){
                if(record.get("sapcode") != map.get(record.getId())){
                    IDataRecord dataRecord = new DataRecord()
                    dataRecord.set("sap_code",record.get("sapcode"))
                    dataRecord.set("belong_territory", ContextHolder.territoryId)
                    dataRecord.set("trainee_id", record.getId())
                    sapDataRecord.add(dataRecord)
                }
            }
            if(sapDataRecord.size() > 0){
                IObjectDescribe objectDescribeSAP = objectDescribeService.findByApiName("sap_date_record")
                dataRecordService.batchInsert(context, objectDescribeSAP, sapDataRecord)
            }
        }
    }

    @Override
    void afterBatchInsert(IContext context, IObjectDescribe objectDescribe, List<IDataRecord> batchRecords) {
        saveSapDateRecord(context, batchRecords)
    }
    /**
     * 保存SAP编码保存时间
     */
    def saveSapDateRecord(IContext context, List<IDataRecord> batchRecords){
        //过滤 SAP编码是空的情况
        List<IDataRecord> dataRecords = batchRecords.stream().filter(){e-> e.get("sapcode")!=null && !StringUtils.isEmpty(e.get("sapcode"))}.collect(Collectors.toList())
        List<IDataRecord> sapDataRecord = new ArrayList<>()
        for(IDataRecord record : dataRecords){
            IDataRecord dataRecord = new DataRecord()
            dataRecord.set("sap_code",record.get("sapcode"))
            dataRecord.set("belong_territory", ContextHolder.territoryId)
            dataRecord.set("trainee_id", record.getId())
            sapDataRecord.add(dataRecord)
        }
        if(sapDataRecord.size() > 0){
            IObjectDescribe objectDescribeSAP = objectDescribeService.findByApiName("sap_date_record")
            dataRecordService.batchInsert(context,objectDescribeSAP , sapDataRecord)
        }
    }

    @Override
    void afterCreate(IContext context, IObjectDescribe objectDescribe, IDataRecord record, Object o) {
        if("trainee" == record.getRecordType()) {
            String sapcode = record.get("sapcode")
            if (sapcode != null && !StringUtils.isEmpty(sapcode)) {

                IDataRecord dataRecord = new DataRecord()
                dataRecord.set("sap_code", sapcode)
                dataRecord.set("belong_territory", ContextHolder.territoryId)
                IDataRecord customer = (DataRecord)o
                dataRecord.set("trainee_id", customer.getId())

                IObjectDescribe objectDescribeSAP = objectDescribeService.findByApiName("sap_date_record")
                dataRecordService.create(objectDescribeSAP, dataRecord)
            }
        }
    }

    @Override
    void beforeCreate(IContext context, IObjectDescribe objectDescribe, IDataRecord record) {
        if("trainee" == record.getRecordType()){
            record.put("external_id", record.get("phone"))
        }
    }
}
