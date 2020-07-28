//package com.forceclouds.crm.local

import com.aliyuncs.utils.StringUtils
import com.forceclouds.context.ContextHolder
import com.forceclouds.context.IUserContext
import com.forceclouds.context.exception.IllegalCustomizationException
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
import com.forceclouds.crm.metadata.service.IObjectDescribeService
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component
import com.google.common.collect.Maps
import org.springframework.util.CollectionUtils
import org.springframework.web.multipart.MultipartFile
import org.apache.poi.ss.usermodel.*
import java.text.DecimalFormat
import java.text.SimpleDateFormat

/**
 * Created  by QY.gao on 2019.11.12
 */
//@Component
//@ObjectDescribeAction(target = 'customer', api_name = 'batch_import_student_data_action')
//@Conditional(DebugCondition.class)
class BatchImportStudentDataAction extends AbstractAction {

    Logger logger = LoggerFactory.getLogger(BatchImportStudentDataAction.class)
    PgDataRecordRepository pgDataRecordRepository
    IDataRecordRepository dataRecordRepository
    IObjectDescribeService objectDescribeService
    IDataRecordService dataRecordService

    @Override
    MapRestResponse exec(List<Long> ids, Map<String, Object> requestParams, Map<String, ?> customActionDefinition) {
        pgDataRecordRepository = applicationContext.getBean(PgDataRecordRepository.class)
        dataRecordRepository = applicationContext.getBean(IDataRecordRepository.class)
        objectDescribeService = applicationContext.getBean(IObjectDescribeService.class)
        dataRecordService = applicationContext.getBean(IDataRecordService.class)

        String eventId = requestParams.get("customerType") as String
        def file = requestParams.get("file") as MultipartFile

        if(StringUtils.isEmpty(eventId)){
            IllegalCustomizationException innerException=new IllegalCustomizationException("活动Id不能为空")
            throw new IllegalCustomizationException("",innerException,"500")
        }

        IObjectDescribe eventObjectDescribe = objectDescribeService.findByApiName("event")
        IDataRecord dateRecord =  pgDataRecordRepository.findById(ContextHolder.getHolder().get(), eventObjectDescribe, Long.valueOf(eventId))
        if(dateRecord==null || CollectionUtils.isEmpty(dateRecord)){
            IllegalCustomizationException innerException=new IllegalCustomizationException("活动ID不存在")
            throw new IllegalCustomizationException("",innerException,"500")
        }

        //导入的文件转换为list
        List<IDataRecord> excelDataList = new ArrayList<>()
        List<Map<String, Object>> excelData = ImportExcelUtil.parseExcel(file.getInputStream(),file.getOriginalFilename())

        if(CollectionUtils.isEmpty(excelData)){
            IllegalCustomizationException innerException=new IllegalCustomizationException("导入的excel中没有数据!")
            throw new IllegalCustomizationException("",innerException,"500")
        }
        for(int i=0 ; i<excelData.size() ; i++){
            IDataRecord dataRecord = new DataRecord()
            dataRecord.putAll(excelData.get(i))
            excelDataList.add(dataRecord)
        }
        //excelList中每一列增加字段 customer.id
        List<IDataRecord> excelDataRecordList = queryExcelDataAddCustomerId(excelDataList)

        //更新或者添加 event_attendee
        saveEventAttendee(excelDataRecordList, eventId)

        //保存参与培训前 学员跟踪反馈信息
        saveTraineeFeedback(excelData ,eventId)

        Map map = new HashMap()
        return MapRestResponse.build().data(map)
    }

    /**
     * excelList中每一列增加字段 customer.id
     * @param excelDataList
     * @return
     */
    def queryExcelDataAddCustomerId(List<IDataRecord> excelDataList){

        List<String> external_ids = new ArrayList<>()
        for(int i=0 ; i< excelDataList.size() ; i++){
            external_ids.add(excelDataList.get(i).get("phone"))
        }
        if(external_ids.size() != new HashSet<String>(external_ids).size()){
            IllegalCustomizationException innerException=new IllegalCustomizationException("导入Excel数据中,有重复的电话!")
            throw new IllegalCustomizationException("",innerException,"500")
        }

        //查询出来customer中external_id含有excel中phone的数据
        List<Map<String,Object>> customerList = queryCustomerId(external_ids)
        //excelList添加上customer.id的list,即该方法最终返回的list
        List<IDataRecord> dataRecords = new ArrayList<>()
        List<IDataRecord> updateCustomer = new ArrayList<>()

        //查询存在的 customer.id
        if(null != customerList && customerList.size()>0){
            Map<String,Integer> customerMap = new HashMap<>()
            customerList.stream().forEach(){e-> customerMap.put(e.get("external_id"),e.get("id")) }
            Map<String,Integer> customerVersionMap = new HashMap<>()
            customerList.stream().forEach(){e-> customerVersionMap.put(e.get("external_id"),e.get("version")) }

            for(int i=0 ; i<excelDataList.size() ; i++){
                IDataRecord map = excelDataList.get(i)
                if(null != customerMap.get(map.get("phone"))){
                    map.put("id",customerMap.get(map.get("phone")))
                    dataRecords.add(map)
                    excelDataList.remove(map)
                    i--
                }
            }

            for(int i=0 ; i<dataRecords.size() ; i++){
                IDataRecord dataRecord = new DataRecord()
                dataRecord.putAll(dataRecords.get(i))
                dataRecord.put("version",customerVersionMap.get(dataRecord.get("phone")))
                updateCustomer.add(dataRecord)
            }
        }

//        updateCustomer = updateCustomer.stream().filter(){e-> null!=e.get("sapcode") && !StringUtils.isEmpty(e.get("sapcode"))}.collect(Collectors.toList())
        updateCustomer.forEach(){e->
            e.put("external_id",e.get("phone"))
            e.put("record_type","trainee")
            e.remove("join_method")
            e.remove("trainee_comment")
//            e.remove("belonged_region")
//            e.remove("city")
//            e.remove("event_date")
//            e.remove("event_type")
            e.remove("order_number")

            e.remove("feedback_stage")
            e.remove("is_use")
            e.remove("use_num")
            e.remove("straumann_rate")
            e.remove("other_brands")
            e.remove("comment")
            if(null==e.get("sapcode") || StringUtils.isEmpty(e.get("sapcode")) ){
                e.remove("sapcode")
            }
        }
        IObjectDescribe objectDescribe = objectDescribeService.findByApiName("customer")
        if(null != updateCustomer && updateCustomer.size()>0){
            dataRecordService.batchUpdate(ContextHolder.get(), objectDescribe, updateCustomer)
        }
        //没有customer.id,则增加customer
        if(null != excelDataList && excelDataList.size() >0 ){

            //保存到customer表的数据
            List<IDataRecord> customerDataList = new ArrayList<>()
            for(int i=0 ; i<excelDataList.size() ; i++){
                IDataRecord dataRecord = new DataRecord()
                dataRecord.putAll(excelDataList.get(i))
                customerDataList.add(dataRecord)
            }
            customerDataList.forEach(){e->
                e.put("external_id",e.get("phone"))
                e.put("record_type","trainee")
                e.remove("join_method")
                e.remove("trainee_comment")
//                e.remove("belonged_region")
//                e.remove("city")
//                e.remove("event_date")
//                e.remove("event_type")
                e.remove("order_number")

                e.remove("feedback_stage")
                e.remove("is_use")
                e.remove("use_num")
                e.remove("straumann_rate")
                e.remove("other_brands")
                e.remove("comment")
            }

            dataRecordService.batchInsert(ContextHolder.getHolder().get() , objectDescribe, customerDataList)

            List<String> external_ids1 = new ArrayList<>()
            for(int i=0 ; i< excelDataList.size() ; i++){
                external_ids1.add(excelDataList.get(i).get("phone"))
            }
            List<Map<String,Object>> customerList1 = queryCustomerId(external_ids1)
            Map<String,Integer> customerMapInsert = new HashMap<>()
            if(null != customerList1 && customerList1.size()>0 ){
                customerList1.stream().forEach(){e-> customerMapInsert.put(e.get("external_id"),e.get("id")) }

                //遍历,若有customer id,则获取customer id
                for(int i=0 ; i<excelDataList.size() ; i++){

                    IDataRecord map = excelDataList.get(i)
                    if(null != customerMapInsert.get(map.get("phone"))){
                        map.put("id",customerMapInsert.get(map.get("phone")))
                        dataRecords.add(map)
                        excelDataList.remove(map)
                        i--
                    }
                }
            }
        }
        return dataRecords
    }

    /**
     * 查询导入excel中的customer.id
     * @param user_id
     * @return
     */
    def queryCustomerId(List<String> external_ids) {
        def currentUser = ContextHolder.get().currentUser() as IUserContext
        if(currentUser == null){
            throw new IllegalArgumentException("currentUser 不能为空")
        }
        String tenantId = currentUser.getTenantId()
        if (StringUtils.isEmpty(tenantId)) {
            throw new IllegalArgumentException("当前用户的 tenantId 不能为空")
        }
        String sql = "select id,external_id,version from \""+tenantId+"\".customer where external_id in (:external_ids) and is_deleted=false "
        Map<String, String> params = Maps.newHashMap()
        params.put("external_ids",external_ids)
        List<Map<String,Object>> list = pgDataRecordRepository.findBySql(sql,params)

        return list
    }

    /**
     * 向event_attendee对象更新或添加数据
     */
    def saveEventAttendee(List<IDataRecord> excelDataRecordList, String eventId){
        //record 修改 key为event_attendee的字段名称
        List<IDataRecord> excelEventDataRecordList = new ArrayList<>()
        for(int i=0 ; i< excelDataRecordList.size() ; i++){
            IDataRecord dataRecord = new DataRecord()

            Set<String> keySet = excelDataRecordList.get(i).keySet()
            for(int j=0 ; j<keySet.size() ; j++){

                String key = keySet.getAt(j)
                if(null == CustomerDataDict.mapByFiled.get(key)){
                    continue
                }
                String newKey = CustomerDataDict.mapByFiled.get(key).getEventFiled()
                Object value = excelDataRecordList.get(i).get(key)
                dataRecord.put(newKey,value)
            }
            if(null != dataRecord && dataRecord.size()>0 ){
                excelEventDataRecordList.add(dataRecord)
            }
        }

        List<Map<String,Object>> eventAttendeeList = queryEventAttendee( eventId)
        IObjectDescribe eventAttendeeObjectDescribe = objectDescribeService.findByApiName("event_attendee")

        if(null != eventAttendeeList && eventAttendeeList.size()>0){

            Map<String,Object> eventAttendeeMap = new HashMap<>()
            eventAttendeeList.stream().forEach(){e-> eventAttendeeMap.put(e.get("customer"), e.get("id")) }
            Map<String,Object> eventAttendeeVersionMap = new HashMap<>()
            eventAttendeeList.stream().forEach(){e-> eventAttendeeVersionMap.put(e.get("customer"), e.get("version")) }

            List<IDataRecord> updateExcelDataRecord = new ArrayList<>()
            //更新 update的数据
            for(int i=0 ; i<excelEventDataRecordList.size() ; i++){
                IDataRecord dataRecord = excelEventDataRecordList.get(i)
                //event_attendee表中的包含 当前行的customer,就是 当前行数据 存在数据库中

                if( eventAttendeeMap.get(String.valueOf(dataRecord.get("customer"))) != null ){
                    dataRecord.put("id",eventAttendeeMap.get(String.valueOf(dataRecord.get("customer"))))
                    dataRecord.put("version",eventAttendeeVersionMap.get(String.valueOf(dataRecord.get("customer"))))
                    dataRecord.put("attendee_event",eventId)
                    if(dataRecord.get("ated_dep_sapcode") == null || StringUtils.isEmpty(dataRecord.get("ated_dep_sapcode"))){
                        dataRecord.remove("ated_dep_sapcode")
                    }
                    updateExcelDataRecord.add(dataRecord)
                    excelEventDataRecordList.remove(i)
                    i--
                }
            }
            if(null != updateExcelDataRecord && updateExcelDataRecord.size()>0){
                dataRecordService.batchUpdate(ContextHolder.getHolder().get(), eventAttendeeObjectDescribe, updateExcelDataRecord)
            }
        }

        //需要insert的
        if(null != excelEventDataRecordList && excelEventDataRecordList.size()>0){
            for(int i=0 ; i<excelEventDataRecordList.size() ; i++){
                excelEventDataRecordList.get(i).put("attendee_event",eventId)
            }
            dataRecordService.batchInsert(ContextHolder.getHolder().get(), eventAttendeeObjectDescribe, excelEventDataRecordList)
        }
    }

    /**
     * 查询所有的customer.id,external_id
     * @param user_id
     * @return
     */
    def queryEventAttendee(String eventId) {
        def currentUser = ContextHolder.get().currentUser() as IUserContext
        if(currentUser == null){
            throw new IllegalArgumentException("currentUser 不能为空")
        }
        String tenantId = currentUser.getTenantId()
        if (StringUtils.isEmpty(tenantId)) {
            throw new IllegalArgumentException("当前用户的 tenantId 不能为空")
        }
        String sql = "select id,version,ext->>'customer' as customer from \""+tenantId+"\".event_attendee where ext->>'attendee_event'=:event and is_deleted=false "
        Map<String, Object> params = Maps.newHashMap()
        params.put("event",eventId)
        List<Map<String,Object>> list = pgDataRecordRepository.findBySql(sql,params)

        return list
    }

    def saveTraineeFeedback(excelData ,String eventId){
        List<IDataRecord> excelDataList = new ArrayList<>()
        for(int i=0 ; i<excelData.size() ; i++){
            IDataRecord dataRecord = new DataRecord()
            dataRecord.putAll(excelData.get(i))
            dataRecord.put("event",Long.valueOf(eventId))
            dataRecord.put("is_use",excelData.get(i).get("is_use")=="是"?true:false)
            dataRecord.put("ated_phone",dataRecord.get("phone"))
//            dataRecord.remove("belonged_region")
//            dataRecord.remove("city")
//            dataRecord.remove("event_date")
//            dataRecord.remove("event_type")
            dataRecord.remove("order_number")
            dataRecord.remove("gender")
            dataRecord.remove("s_work_dep")
            dataRecord.remove("sapcode")
            dataRecord.remove("dep_type")
            dataRecord.remove("contact_name")
            dataRecord.remove("join_method")
            dataRecord.remove("trainee_comment")
            dataRecord.remove("id")
            dataRecord.remove("phone")

            excelDataList.add(dataRecord)
        }

        List<IDataRecord> traineeFeedbackList = queryTraineeFeedback( eventId)
        IObjectDescribe traineeFeedbackObjectDescribe = objectDescribeService.findByApiName("trainee_feedback")

        if(null != traineeFeedbackList && traineeFeedbackList.size()>0){

            Map<String,Object> traineeFeedbackMap = new HashMap<>()
            traineeFeedbackList.stream().forEach(){e-> traineeFeedbackMap.put(e.get("phone"), e.get("id")) }
            Map<String,Object> traineeFeedbackMapVersionMap = new HashMap<>()
            traineeFeedbackList.stream().forEach(){e-> traineeFeedbackMapVersionMap.put(e.get("phone"), e.get("version")) }

            List<IDataRecord> updateExcelDataRecord = new ArrayList<>()
            //更新 update的数据
            for(int i=0 ; i<excelDataList.size() ; i++){
                IDataRecord dataRecord = excelDataList.get(i)
                //trainee_feedback表中的包含 当前行的phone,就是 当前行数据 存在数据库中

                if( traineeFeedbackMap.get(String.valueOf(dataRecord.get("ated_phone"))) != null ){
                    dataRecord.put("id",traineeFeedbackMap.get(String.valueOf(dataRecord.get("ated_phone"))))
                    dataRecord.put("version",traineeFeedbackMapVersionMap.get(String.valueOf(dataRecord.get("ated_phone"))))

                    updateExcelDataRecord.add(dataRecord)
                    excelDataList.remove(i)
                    i--
                }
            }
            if(null != updateExcelDataRecord && updateExcelDataRecord.size()>0){
                dataRecordService.batchUpdate(ContextHolder.getHolder().get(), traineeFeedbackObjectDescribe, updateExcelDataRecord)
            }
        }

        //需要insert的
        if(null != excelDataList && excelDataList.size()>0){
            dataRecordService.batchInsert(ContextHolder.getHolder().get(), traineeFeedbackObjectDescribe, excelDataList)
        }

    }

    /**
     * 查询参与培训前 学员跟踪反馈
     */
    def queryTraineeFeedback(String eventId){
        String tenantId = ContextHolder.tenantId
        String sql = "select id,version,ext->>'ated_phone' as phone from \""+tenantId+"\".tenant_data_record " +
                " where object_describe_name =:object_describe_name and ext->>'feedback_stage' =:feedback_stage and  ext->>'event'=:event and is_deleted=:is_deleted "
        Map<String, Object> params = Maps.newHashMap()
        params.put("object_describe_name","trainee_feedback")
        params.put("feedback_stage","参与培训前")
        params.put("event",eventId)
        params.put("is_deleted",false)
        List<Map<String,Object>> list = pgDataRecordRepository.findBySql(sql,params)

        return list
    }

    /**
     *  Excel文件流 -->  List <Map<String,Object>>  对象
     想直接转成java bean的朋友可以使用fastjson将  List<Map<String,Object>>转成bean对象
     */
    class ImportExcelUtil {

        private static Logger log = LoggerFactory.getLogger(ImportExcelUtil.class)

        private final static String excel2003L = ".xls" // 2003- 版本的excel
        private final static String excel2007U = ".xlsx" // 2007+ 版本的excel

        /**
         * 将流中的Excel数据转成List<Map>
         * @param in 输入流
         * @param fileName 文件名（判断Excel版本）
         * @return
         * @throws Exception
         */
        static List<Map<String, Object>> parseExcel(InputStream inputStream, String fileName) throws Exception {
            // 根据文件名来创建Excel工作薄
            Workbook work = getWorkbook(inputStream, fileName)
            if (null == work) {
                IllegalCustomizationException innerException=new IllegalCustomizationException("创建Excel工作薄为空！")
                throw new IllegalCustomizationException("",innerException,"500")
            }
            Sheet sheet = null
            Row row = null
            Cell cell = null
            // 返回数据
            List<Map<String, Object>> ls = new ArrayList<Map<String, Object>>()

            // 遍历Excel中第一个的sheet
            sheet = work.getSheetAt(0)
            if (sheet == null) {
                IllegalCustomizationException innerException=new IllegalCustomizationException("请导入正确的模板!")
                throw new IllegalCustomizationException("",innerException,"500")
            }

            // 取第一行标题
            row = sheet.getRow(0)
            String[] title = null
            if (row != null) {
                title = new String[row.getLastCellNum()]

                for (int y = row.getFirstCellNum(); y < row.getLastCellNum(); y++) {
                    cell = row.getCell(y)
                    title[y] = (String) getCellValue(cell)
                }
            } else{
                IllegalCustomizationException innerException=new IllegalCustomizationException("请导入正确的模板!")
                throw new IllegalCustomizationException("",innerException,"500")
            }

            if(title.size() != 16){
                IllegalCustomizationException innerException=new IllegalCustomizationException("请导入正确的模板!")
                throw new IllegalCustomizationException("",innerException,"500")
            }

            // 遍历当前sheet中的所有行
            for (int j = 1; j < sheet.getLastRowNum() + 1; j++) {
                row = sheet.getRow(j)
                Map<String, Object> m = new HashMap<String, Object>()

                if(row == null){
                    continue
                }
                boolean flag = true
                for(int i=0 ; i< title.size(); i++) {
                    if (null != getCellValue(row.getCell(i)) && !StringUtils.isEmpty(getCellValue(row.getCell(i)).toString().trim())) {
                        flag = false
                        break
                    }
                }
                if (flag) {
                    continue
                }

                // 遍历所有的列
                for (int y = 0; y < title.size(); y++) {
                    cell = row.getCell(y)
                    String key = title[y]
                    if(CustomerDataDict.map.get(key) == null){
                        IllegalCustomizationException innerException=new IllegalCustomizationException("请导入正确的模板!")
                        throw new IllegalCustomizationException("",innerException,"500")
                    }
                    String cellValue = CustomerDataDict.map.get(key).getField()

                    if(cellValue != "sapcode" && cellValue != "comment" && (null == getCellValue(cell) || getCellValue(cell)=="")){
                        IllegalCustomizationException innerException=new IllegalCustomizationException("'"+CustomerDataDict.mapByFiled.get(cellValue).getName()+"' 数据不能为空!")
                        throw new IllegalCustomizationException("",innerException,"500")
                    }
                    if(cellValue == "order_number"){
                        if(!cell.toString().matches("[0-9]+[.0]*")) {
                            IllegalCustomizationException innerException=new IllegalCustomizationException("序号:'"+cell.toString()+"' 应为整数!")
                            throw new IllegalCustomizationException("",innerException,"500")
                        }
                    }
                    m.put(cellValue, getCellValue(cell))
                }
                ls.add(m)
            }
            work.close()
            return ls
        }

        /**
         * 描述：根据文件后缀，自适应上传文件的版本
         * @param inStr,fileName
         * @return
         * @throws Exception
         */
        static Workbook getWorkbook(InputStream inStr, String fileName) throws Exception {
            Workbook wb = null
            String fileType = fileName.substring(fileName.lastIndexOf("."))
            if (excel2003L.equals(fileType)) {
                wb = new HSSFWorkbook(inStr) // 2003-
            } else if (excel2007U.equals(fileType)) {
                wb = new XSSFWorkbook(inStr) // 2007+
            } else {
                IllegalCustomizationException innerException=new IllegalCustomizationException("文件格式有误,请导入正确的模板.")
                throw new IllegalCustomizationException("",innerException,"500")
            }
            return wb
        }

        /**
         * 描述：对表格中数值进行格式化
         * @param cell
         * @return
         */
        static Object getCellValue(Cell cell) {
            Object value = null
            DecimalFormat df = new DecimalFormat("0") // 格式化number String字符
            SimpleDateFormat sdf = new SimpleDateFormat("yyy-MM-dd") // 日期格式化
            DecimalFormat df2 = new DecimalFormat("0") // 格式化数字

            if(null == cell){
                return value
            }
            switch (cell.getCellType()) {
                case Cell.CELL_TYPE_STRING:
                    value = cell.getRichStringCellValue().getString()
                    break
                case Cell.CELL_TYPE_NUMERIC:
                    if ("General".equals(cell.getCellStyle().getDataFormatString())) {
                        value = df.format(cell.getNumericCellValue())
                    } else if ("m/d/yy".equals(cell.getCellStyle().getDataFormatString())) {
                        value = sdf.format(cell.getDateCellValue())
                    } else {
                        value = df2.format(cell.getNumericCellValue())
                    }
                    break
                case Cell.CELL_TYPE_BOOLEAN:
                    value = cell.getBooleanCellValue()
                    break
                case Cell.CELL_TYPE_BLANK:
                    value = ""
                    break
                default:
                    break
            }
            return value
        }
    }

    enum CustomerDataDict {
//        BELONGED_REGION("区域", "belonged_region", false, "belonged_region"),
//        CITY("城市", "city", false, "city"),
//        EVENT_DATE("时间", "event_date", false, "event_date"),
//        EVENT_TYPE("类型", "event_type", false, "event_type"),
        ORDER_NUMBER("序号", "order_number", false, "order_number"),
        NAME("姓名", "name", false, "ated_name"),
        GENDER("性别", "gender", false, "ated_gender"),
        PHONE("电话", "phone", false, "ated_phone"),
        SAPCODE("SAP客户编码", "sapcode", false, "ated_dep_sapcode"),
        S_WORK_DEP("单位", "s_work_dep", false, "ated_dep"),
        CONTACT_NAME("联系人姓名\n（Sales）", "contact_name", false, "contact_name"),
        DEP_TYPE("单位性质\n（公立/民营）", "dep_type", false, "ated_dep_type"),
        JOIN_METHOD("参与方式\n（销售包/自费/赠送）", "join_method", false, "ated_join_method"),
        TRAINEE_COMMENT("销售包内容/自费金额/赠送原因", "trainee_comment", false, "ated_content"),
        CUSTOMER_ID("客户ID", "id", false, "customer"),

        FEEDBACK_STAGE("跟踪阶段", "feedback_stage", false, "feedback_stage"),
        IS_USE("是否开展种植\n（是/否）", "is_use", false, "is_use"),
        USE_NUM("种植数量", "use_num", false, "use_num"),
        STRAUMANN_RATE("士卓曼占比", "straumann_rate", false, "straumann_rate"),
        OTHER_BRANDS("其它1-2种主要种植品牌\nNobel, BEGO,Ankylos,Zimmer,Astra,Dentium,Osstem…", "other_brands", false, "other_brands"),
        COMMENT("备注", "comment", false, "comment")

        private String name
        private String field
        private Boolean required
        private String eventFiled

        CustomerDataDict(String name, String field, Boolean required, String eventFiled) {
            this.name = name
            this.field = field
            this.required = required
            this.eventFiled = eventFiled
        }

        String getName() {
            return name
        }
        void setName(String name) {
            this.name = name
        }
        String getField() {
            return field
        }
        void setField(String field) {
            this.field = field
        }
        Boolean getRequired() {
            return required
        }
        void setRequired(Boolean required) {
            this.required = required
        }
        String getEventFiled() {
            return eventFiled
        }
        void setEventFiled(String eventFiled) {
            this.eventFiled = eventFiled
        }

        public static final Map<String, CustomerDataDict> map = new HashMap<>()
        static {
            for (CustomerDataDict dict : values()) {
                map.put(dict.getName(), dict)
            }
        }

        public static final Map<String, CustomerDataDict> mapByFiled = new HashMap<>()
        static {
            for (CustomerDataDict dict : values()) {
                mapByFiled.put(dict.getField(), dict)
            }
        }

    }
}