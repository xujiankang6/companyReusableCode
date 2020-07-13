package com.forceclouds.crm.local

import com.forceclouds.context.ContextHolder
import com.forceclouds.context.chain.AbstractHandler
import com.forceclouds.context.impl.MapRestResponse
import com.forceclouds.context.transaction.TransactionalHolderUtil
import com.forceclouds.context.transaction.annotation.BeginTransactional
import com.forceclouds.crm.action.AbstractAction
import com.forceclouds.crm.anno.DebugCondition
import com.forceclouds.crm.anno.ObjectDescribeAction
import com.forceclouds.crm.data.model.IDataRecord
import com.forceclouds.crm.data.model.Query
import com.forceclouds.crm.data.repository.IDataRecordRepository
import com.forceclouds.crm.data.service.IDataRecordService
import com.forceclouds.crm.metadata.service.IObjectDescribeService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component

/*
*@create by jiankang
*@date 2020/6/10 time 10:07
*/

//@Component
//@ObjectDescribeAction(target = 'call', api_name = 'check_table_field', path = "D:\\yunshihouduan\\fc-crm-tenant-management-application\\src\\main\\java\\com\\forceclouds\\crm\\local\\TableFieldCheckAction.groovy")
//@Conditional(DebugCondition.class)
class TableFieldCheckAction extends AbstractAction {
    def logger = LoggerFactory.getLogger(TableFieldCheckAction.class)
    def dataRecordRepository
    def dataRecordService
    def objectDescribeService
    def transactionalHolderUtil

    @Override
    MapRestResponse exec(List<Long> ids, Map<String, Object> requestParams, Map<String, ?> customActionDefinition) {

        transactionalHolderUtil = this.applicationContext.getBean(TransactionalHolderUtil.class) as TransactionalHolderUtil
        dataRecordRepository = this.applicationContext.getBean(IDataRecordRepository.class) as IDataRecordRepository
        dataRecordService = this.applicationContext.getBean(IDataRecordService.class) as IDataRecordService
        objectDescribeService = this.applicationContext.getBean(IObjectDescribeService.class) as IObjectDescribeService
        def question_api_names = queryAllApiName() as HashMap
        question_api_names.put("question_size", question_api_names.size())
        return MapRestResponse.build().data(question_api_names)
    }


    def queryAllApiName() {
        def tenantId = ContextHolder.tenantId
        String user_sql = """
        select distinct api_name from "gm".object_describe where tenant_id='${tenantId}' and is_deleted=false order by api_name
"""
        def userMap = new HashMap();
        userMap.put("tenantId", tenantId)

        def api_names = dataRecordRepository.findBySql(user_sql, userMap) as List<IDataRecord>
        def question_api_name = new HashMap()
        if (api_names) {
            api_names.forEach({
                try {
                    Class<? extends AbstractHandler> nextHandlerClass = TableFieldCheckAction.getClass();
                    BeginTransactional beginTransactional = (BeginTransactional) nextHandlerClass.getAnnotation(BeginTransactional.class);
                    this.transactionalHolderUtil.start(beginTransactional);
                    queryTable(it?.api_name as String)
                } catch (Exception e) {
                    this.transactionalHolderUtil.rollback()
                    //事务回滚或者提交
//                    JdbcInitTable.connection.rollback()
//                    TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
//                    DataSource ds = this.applicationContext.get    getBean("myDataSource");
//                    Connection conn = ds.getConnection();
//                    transaction = transactionFactory.newTransaction(dataSource, null, false);
//                    DataSourceUtils.getConnection(JdbcAccessor.getDataSource()).rollback()
                    println(e.toString())
                    question_api_name.put(it?.api_name, e.toString())
                }
            })
        };
        return question_api_name;
    }


    def queryTable(String api_name) {
        def query = new Query()
        query.setPageNo(1)
        query.setPageSize(1)
        query.setJoiner("and")
        query.setObjectApiName(api_name)
        query.setNeedRelationQuery(false)
        dataRecordRepository.query(query)
    }
}

