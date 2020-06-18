package com.forceclouds.crm.local.trigger

import com.forceclouds.context.ContextHolder
import com.forceclouds.context.HeadCode
import com.forceclouds.context.IContext
import com.forceclouds.context.impl.MapRestResponse
import com.forceclouds.crm.action.AbstractAction
import com.forceclouds.crm.anno.DebugCondition
import com.forceclouds.crm.anno.ObjectDescribeAction
import com.forceclouds.crm.data.model.DataRecord
import com.forceclouds.crm.data.model.IDataRecord
import com.forceclouds.crm.data.repository.IDataRecordRepository
import com.forceclouds.crm.data.repository.impl.PgDataRecordRepository
import com.forceclouds.crm.data.service.IDataRecordService
import com.forceclouds.crm.metadata.model.IObjectDescribe
import com.forceclouds.crm.metadata.service.impl.ObjectDescribeService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component

/*
*@create by jiankang
*@date 2019/11/5 time 17:29
*/

@Component
@ObjectDescribeAction(target = 'promotional_material', api_name = 'promotional_material_add_clm_presentation')
@Conditional(DebugCondition.class)
class ProMatAddClmPreAction extends AbstractAction {


    Logger logger = LoggerFactory.getLogger(ProMatAddClmPreAction.class)
    private def pgDataRecordRepository
    private def dataRecordRepository
    private def objectDescribeService
    private def dataRecordService

    @Override
    MapRestResponse exec(List<Long> ids, Map<String, Object> requestParams, Map<String, ?> customActionDefinition) {

        logger.info("ProMatAddClmPreAction start")
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("参数不能为空")
        }
        pgDataRecordRepository = applicationContext.getBean(PgDataRecordRepository.class)
        dataRecordRepository = applicationContext.getBean(IDataRecordRepository.class)
        objectDescribeService = applicationContext.getBean(ObjectDescribeService.class)
        dataRecordService = applicationContext.getBean(IDataRecordService.class)
        def headCode = HeadCode.SUCCESS
        def promotional_material_id = ids[0] as Long
        if (promotional_material_id) {
            def result = dataRecordRepository.findById(ContextHolder.getHolder().get() as IContext, objectDescribeService.findByApiName("promotional_material") as IObjectDescribe, promotional_material_id, false) as IDataRecord
            if (result != null) {
                result.status = '2'
                def dataRecord = new DataRecord()
                dataRecord.promotional_material = result.id as Long
                dataRecord.product = result.product
                dataRecord.ta = result.ta
                dataRecord.status = result.status
                dataRecord.type = result.type
                dataRecord.name = result.name
                dataRecord.is_active = result.is_active
                dataRecord.description = result.usage
                dataRecord.external_id = result.external_id
                dataRecordRepository.update(ContextHolder.getHolder().get() as IContext, objectDescribeService.findByApiName("promotional_material") as IObjectDescribe, result)
                dataRecordService.create(objectDescribeService.findByApiName("clm_presentation") as IObjectDescribe, dataRecord) as IDataRecord
            }
        }
        return MapRestResponse.build(headCode)
    }
}

