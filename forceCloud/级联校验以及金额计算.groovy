package com.forceclouds.crm.local


import com.forceclouds.context.IContext
import com.forceclouds.context.exception.IllegalCustomizationException
import com.forceclouds.crm.anno.DebugCondition
import com.forceclouds.crm.anno.ObjectDescribeTrigger
import com.forceclouds.crm.data.model.Criteria
import com.forceclouds.crm.data.model.DataRecord
import com.forceclouds.crm.data.model.IDataRecord
import com.forceclouds.crm.data.model.Query
import com.forceclouds.crm.metadata.model.IObjectDescribe
import com.forceclouds.crm.trigger.AbstractTrigger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component

import java.text.DecimalFormat

/**
 * Created by QY.gao 2019.12.26
 */
@Component
@ObjectDescribeTrigger(target = 'event_closure', path = 'D:\\yunshihouduan\\fc-crm-tenant-management-application\\src\\main\\java\\com\\forceclouds\\crm\\local\\EventClosureTrigger.groovy')
@Conditional(DebugCondition.class)
class EventClosureTrigger extends AbstractTrigger {

    private def logger = LoggerFactory.getLogger(EventClosureTrigger.class)


    @Override
    void beforeCreate(IContext context, IObjectDescribe objectDescribe, IDataRecord record) {
        CheckEventClosureWhenCreate(context, record)
        calculateVoucherAmountWhenCreate(context, objectDescribe, record)
    }

    /**
     * crm-6514 by jiankang
     * @param newValue
     */
    def CheckEventClosureWhenCreate(IContext context, IDataRecord newValue) {
        def chainAndTheme = newValue.record_type == 'closure_chain' || newValue.record_type == 'closure_ordinary' || newValue.record_type == 'closure_theme'
        if (chainAndTheme) {
            String deviceType = context.currentUser().getDeviceType()
            String msg = "请先维护活动使用费用和活动使用物料信息再进行保存！"
            if (deviceType.equalsIgnoreCase("pc")) {
                if ((newValue?._cascade.create.event_closure_event_usage_cost_list.size() == 0) ||
                        (newValue?._cascade.create.event_closure_event_usage_product_list.size() == 0)) {
                    throw new IllegalCustomizationException(msg, new IllegalCustomizationException(msg));
                }
            } else {
                if (newValue?._cascade.create.size() == 0) {
                    throw new IllegalCustomizationException(msg, new IllegalCustomizationException(msg));
                }
                if (newValue?._cascade.create.event_closure_event_usage_cost_list == null ||
                        newValue?._cascade.create.event_closure_event_usage_product_list == null) {
                    throw new IllegalCustomizationException(msg, new IllegalCustomizationException(msg));
                }
            }
        }
    }

    /**
     * crm-6514 by jiankang
     * @param newValue
     */
    def CheckEventClosureWhenUpdate(IContext context, IObjectDescribe objectDescribe, IDataRecord newValue) {
        def type = dataRecordRepository.findById(context, objectDescribe, newValue?.id as long)?.record_type as String
        def chainAndTheme = type == 'closure_chain' || type == 'closure_ordinary' || type == 'closure_theme'
        if (chainAndTheme && newValue?._cascade) {


            String deviceType = context.currentUser().getDeviceType()
            def event_cost_create_size = 0
            def event_cost_delete_size = 0
            def event_product_delete_size = 0
            def event_product_create_size = 0
            if (deviceType.equalsIgnoreCase("pc")) {
                event_cost_create_size = newValue?._cascade.create.event_closure_event_usage_cost_list.size()
                event_cost_delete_size = newValue?._cascade.delete.event_closure_event_usage_cost_list.size()
                event_product_create_size = newValue?._cascade.create.event_closure_event_usage_product_list.size()
                event_product_delete_size = newValue?._cascade.delete.event_closure_event_usage_product_list.size()
            } else {
                if (newValue?._cascade.create.size() != 0) {
                    event_cost_create_size = newValue?._cascade.create.event_closure_event_usage_cost_list == null ? 0 : newValue?._cascade.create.event_closure_event_usage_cost_list.size()
                    event_product_create_size = newValue?._cascade.create.event_closure_event_usage_product_list == null ? 0 : newValue?._cascade.create.event_closure_event_usage_product_list.size()
                }
                if (newValue?._cascade.delete.size() != 0) {
                    event_cost_delete_size = newValue?._cascade.delete.event_closure_event_usage_cost_list == null ? 0 : newValue?._cascade.delete.event_closure_event_usage_cost_list.size()
                    event_product_delete_size = newValue?._cascade.delete.event_closure_event_usage_product_list == null ? 0 : newValue?._cascade.delete.event_closure_event_usage_product_list.size()
                }
            }
            def event_costs_in_db_size = queryEventUsageCost(newValue?.id as long, newValue?.event as long)
            def eventCostSize = event_cost_create_size - event_cost_delete_size + event_costs_in_db_size
            def event_product_in_db_size = queryEventUsageProduct(newValue?.id as long, newValue?.event as long)
            def eventProductSize = event_product_create_size - event_product_delete_size + event_product_in_db_size
            if ((eventCostSize <= 0) || (eventProductSize <= 0)) {
                String msg = "请先维护活动使用费用和活动使用物料信息再进行保存！"
                throw new IllegalCustomizationException(msg, new IllegalCustomizationException(msg));
            }


        }
    }

    def queryEventUsageCost(long event_closure, long event) {
        def query = new Query()
        query.setPageNo(1)
        query.setPageSize(Integer.MAX_VALUE)
        query.setJoiner("and")
        query.setObjectApiName("event_usage_cost")
        query.getCriterias().add(new Criteria("is_deleted", "==", false))
        query.getCriterias().add(new Criteria("event", "==", event_closure))
        def result = dataRecordRepository.query(query).result as List<IDataRecord>
        if (result)
            return result.size()
        0
    }

    def queryEventUsageProduct(long event_closure, long event) {
        def query = new Query()
        query.setPageNo(1)
        query.setPageSize(Integer.MAX_VALUE)
        query.setJoiner("and")
        query.setObjectApiName("event_usage_product")
        query.getCriterias().add(new Criteria("is_deleted", "==", false))
        query.getCriterias().add(new Criteria("event", "==", event_closure))
        def result = dataRecordRepository.query(query).result as List<IDataRecord>
        if (result)
            return result.size()
        0
    }


    def queryEventUsageProductByEventClosureId(long event_closure) {
        def query = new Query()
        query.setPageNo(1)
        query.setPageSize(Integer.MAX_VALUE)
        query.setJoiner("and")
        query.setObjectApiName("event_usage_product")
        query.getCriterias().add(new Criteria("is_deleted", "==", false))
        query.getCriterias().add(new Criteria("event_closure", "==", event_closure))
        def result = dataRecordRepository.query(query).result as List<IDataRecord>
        if (result)
            return result
        null
    }

    def queryEventUsageCostByEventClosureId(long event_closure) {
        def query = new Query()
        query.setPageNo(1)
        query.setPageSize(Integer.MAX_VALUE)
        query.setJoiner("and")
        query.setObjectApiName("event_usage_cost")
        query.getCriterias().add(new Criteria("is_deleted", "==", false))
        query.getCriterias().add(new Criteria("event_closure", "==", event_closure))
        def result = dataRecordRepository.query(query).result as List<IDataRecord>
        if (result)
            return result
        null
    }


    @Override
    void beforeUpdate(IContext context, IObjectDescribe objectDescribe, IDataRecord newValue) {
        CheckEventClosureWhenUpdate(context, objectDescribe, newValue)
        calculateVoucherAmountWhenUpdate(context, objectDescribe, newValue)
    }

/**
 * create by jiankang  6676
 * @param context
 * @param objectDescribe
 * @param newValue
 * @return
 */
    def calculateVoucherAmountWhenUpdate(IContext context, IObjectDescribe objectDescribe, IDataRecord newValue) {
        def eventCreateUsageCosts = newValue?._cascade?.create?.event_closure_event_usage_cost_list == null ? new ArrayList<IDataRecord>() : newValue?._cascade.create.event_closure_event_usage_cost_list as ArrayList<IDataRecord>
        def eventCreateUsageProducts = newValue?._cascade?.create?.event_closure_event_usage_product_list == null ? new ArrayList<IDataRecord>() : newValue?._cascade.create.event_closure_event_usage_product_list as ArrayList<IDataRecord>

        def eventUpdateUsageCosts = newValue?._cascade?.update?.event_closure_event_usage_cost_list == null ? new ArrayList<IDataRecord>() : newValue?._cascade.update.event_closure_event_usage_cost_list as ArrayList<IDataRecord>
        def eventUpdateUsageProducts = newValue?._cascade?.update?.event_closure_event_usage_product_list == null ? new ArrayList<IDataRecord>() : newValue?._cascade.update.event_closure_event_usage_product_list as ArrayList<IDataRecord>

        def eventDeleteUsageCosts = newValue?._cascade?.delete?.event_closure_event_usage_cost_list == null ? new ArrayList<IDataRecord>() : newValue?._cascade.delete.event_closure_event_usage_cost_list as ArrayList<IDataRecord>
        def eventDeleteUsageProducts = newValue?._cascade?.delete?.event_closure_event_usage_product_list == null ? new ArrayList<IDataRecord>() : newValue?._cascade.delete.event_closure_event_usage_product_list as ArrayList<IDataRecord>

        def event_usage_products = queryEventUsageProductByEventClosureId(newValue?.id as long) as ArrayList<IDataRecord>
        def event_usage_costs = queryEventUsageCostByEventClosureId(newValue?.id as long) as ArrayList<IDataRecord>


        UpdateAndDeleteSonEvent(event_usage_costs, eventUpdateUsageCosts, eventDeleteUsageCosts)
        UpdateAndDeleteSonEvent(event_usage_products, eventUpdateUsageProducts, eventDeleteUsageProducts)

        event_usage_products.addAll(eventCreateUsageProducts)
        event_usage_costs.addAll(eventCreateUsageCosts)

        calculateVoucherAmount(event_usage_products, event_usage_costs, newValue)

    }

    /**
     * create by jiankang  6676
     * @param context
     * @param objectDescribe
     * @param newValue
     * @return
     */
    def calculateVoucherAmountWhenCreate(IContext context, IObjectDescribe objectDescribe, IDataRecord newValue) {
        def eventCreateUsageCosts = newValue?._cascade?.create?.event_closure_event_usage_cost_list == null ? new ArrayList<IDataRecord>() : newValue?._cascade?.create?.event_closure_event_usage_cost_list as ArrayList<IDataRecord>
        def eventCreateUsageProducts = newValue?._cascade?.create?.event_closure_event_usage_product_list == null ? new ArrayList<IDataRecord>() : newValue?._cascade?.create?.event_closure_event_usage_product_list as ArrayList<IDataRecord>
        calculateVoucherAmount(eventCreateUsageProducts, eventCreateUsageCosts, newValue)

    }

    def calculateVoucherAmount(event_usage_products, event_usage_costs, IDataRecord newValue) {

        def sum_event_usage_product = new BigDecimal(0);
        def sum_event_usage_cost = new BigDecimal(0);
        if (event_usage_products) {
            event_usage_products.stream().forEach({
                def money = formatMoney(it.usage_product_amount as String) as BigDecimal
                sum_event_usage_product = moneyAdd(sum_event_usage_product, money)
            })
        }
        if (event_usage_costs) {
            event_usage_costs.stream().forEach({
                def money = formatMoney(it.usage_cash_amount as String) as BigDecimal
                sum_event_usage_cost = moneyAdd(sum_event_usage_cost, money)
            })
        }
        def voucher_amount = sum_event_usage_product.add(sum_event_usage_cost)
        newValue.voucher_amount = voucher_amount.setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();

    }

    def UpdateAndDeleteSonEvent(ArrayList<IDataRecord> eventCosts, ArrayList<IDataRecord> eventUpdateCosts, ArrayList<IDataRecord> eventDeleteCosts) {
        if (eventCosts) {
            Iterator<IDataRecord> iterator = eventCosts.iterator()
            def records = new ArrayList<IDataRecord>()
            while (iterator.hasNext()) {
                IDataRecord it = iterator.next()
                if (eventUpdateCosts) {
                    for (Map linkedMap : eventUpdateCosts) {
                        def record = new DataRecord()
                        if (it.id == linkedMap.id) {
                            iterator.remove()
                            for (Map.Entry<String, Object> entry : linkedMap.entrySet()) {
                                record.set(entry.getKey(), entry.getValue())
                            }
                            records.add(record)
                        }
                    }
                }
            }
            eventCosts.addAll(records)
            if (eventDeleteCosts) {
                Iterator<IDataRecord> iterator2 = eventCosts.iterator()
                while (iterator2.hasNext()) {
                    IDataRecord it = iterator2.next()
                    for (Map linkedMap : eventDeleteCosts) {
                        if (it.id == linkedMap.id) {
                            iterator2.remove()
                        }
                    }
                }
            }
        }
    }

    /*
     * 格式化金额
     * @param value
     * @return
     */

    public static BigDecimal formatMoney(String value) {
        DecimalFormat fnum = new DecimalFormat("##0.00000000000000000000");
        if (value == null || value == "") {
            value = "0.00";
        }
        return new BigDecimal(fnum.format(new BigDecimal(value)));
    }

    /**
     * 金额相加
     * @param valueStr 基础值
     * @param minusValueStr 被加数
     * @return
     */
    public static BigDecimal moneyAdd(BigDecimal value, BigDecimal augend) {
        return value.add(augend);
    }
}

