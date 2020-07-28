package com.forceclouds.crm.local

import com.forceclouds.context.HeadCode
import com.forceclouds.context.impl.MapRestResponse
import com.forceclouds.crm.action.AbstractAction
import com.forceclouds.crm.anno.DebugCondition
import com.forceclouds.crm.anno.ObjectDescribeAction
import com.forceclouds.crm.data.repository.IDataRecordRepository
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFRow
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.stream.Collectors

/**
 *  Created by JQ.bi
 */
@Component
@ObjectDescribeAction(target = 'segmentation_history', api_name = 'export_segmentation')
@Conditional(DebugCondition.class)
class SegmentationExportAction extends AbstractAction {

    Logger logger = LoggerFactory.getLogger(SegmentationExportAction.class)

    final private def TENANTID = "T7641332615842816"
    //N V 定级筛选标准 包括职位职称
    final private def POSITION_IS_N = ['1', '2', '6', '8', '20', '24', '25', '28', '39'] as String[]
    final private def POSITION_IS_V = ['3', '12', '13', '16', '17', '18', '22', '23', '27', '32', '33', '35'] as String[]
    final private def POSTNAME_IS_N = ['zc12', 'zc13', 'zc14', 'zc15', 'zc16'] as String[]
    final private def POSTNAME_IS_V = ['zc26', 'zc27', 'zc28', 'zc29', 'zc30'] as String[]
    private def jdbcTemplate

    @Override
    MapRestResponse exec(List<Long> ids, Map<String, Object> requestParams, Map<String, ?> customActionDefinition) {
        ServletRequestAttributes servletRequestAttributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes()
        //HttpServletRequest request = servletRequestAttributes.getRequest()
        HttpServletResponse httpServletResponse = servletRequestAttributes.getResponse()
        jdbcTemplate = this.applicationContext.getBean(IDataRecordRepository.class).jdbcTemplate
        //取出需要定级的hcp
        def hcpCustomer = getHcpCustomer()
        if (!hcpCustomer) {
            HeadCode headCode = HeadCode.ERROR
            headCode.msg = '没有查到符合要求的医生数据'
            return MapRestResponse.build(headCode)
        }
        //算出十等分逻辑各打分标准（*区分产品）
        logger.info("SegmentationExportAction is starting")
        //处方量打分标准
        def xgmCflScoreStandard = gradeStandard("喜格迈", "cp", "avg_prescription_qty", "cfl") as List
        def dfCflScoreStandard = gradeStandard("丹参多酚酸盐", "cp", "avg_prescription_qty", "cfl") as List
        //床位数打分标准
        def xgmCwsScoreStandard = gradeStandard("喜格迈", "c", "bed_qty_hcp", "cws") as List
        def dfCwsScoreStandard = gradeStandard("丹参多酚酸盐", "c", "bed_qty_hcp", "cws") as List
        //周人数打分标准
        def xgmZrsScoreStandard = zrsGradeStandard("喜格迈") as List
        def dfZrsScoreStandard = zrsGradeStandard("丹参多酚酸盐") as List

        //根据产品区分算出各自的小分
        def separateCustomerResult = separateCustomerHandler(hcpCustomer)
        def scoreCustomerResult = calculateCustomerHandel(separateCustomerResult, xgmCflScoreStandard, dfCflScoreStandard,
                xgmCwsScoreStandard, dfCwsScoreStandard, xgmZrsScoreStandard, dfZrsScoreStandard)
        //区分出各个产品的N、V级
        def nvCustomerResult = NVCustomerHandler(scoreCustomerResult)
        //1、2、3 定级(区分产品)
        def saleCustomerResult = segmentationBySale(nvCustomerResult)
        //A、B、C定级(区分产品最终逻辑见CRM-6350)
        def countrySegmentationResult = segmentationByScore(saleCustomerResult)
        //a、b、c 定级(区分辖区，区分产品最终逻辑见CRM-6350)
        def scoreAreaSegmentationResult = segmentationOfScoreArea(countrySegmentationResult)

        //下载excel文件
        download(scoreAreaSegmentationResult, httpServletResponse)

        logger.info("finish export segmentation Action")
        return null
    }
    /**
     * a、b、b 辖区定级
     * @param resuleMap
     */
    def segmentationOfScoreArea(Map saleCustomerResult) {
        List DFCustomer = saleCustomerResult.get("DFCustomer") as List
        List XGMCustomer = saleCustomerResult.get("XGMCustomer") as List
        def territoryList = queryTerritory()
        if (!territoryList) {
            HeadCode headCode = HeadCode.ERROR
            headCode.msg = '没有查到任何辖区数据'
            return MapRestResponse.build(headCode)
        }
        def DFTempList = new ArrayList()
        def XGMTempList = new ArrayList()
        for (int i = 0; i < territoryList.size(); i++) {
            def tempListDF = new ArrayList()
            def tempListXGM = new ArrayList()
            for (int j = 0; j < DFCustomer.size(); j++) {
                if (territoryList[i].getAt("territory_code") == DFCustomer[j]["territory_code"]) {
                    tempListDF.add(DFCustomer[j])
                }
            }
            for (int j = 0; j < XGMCustomer.size(); j++) {
                if (territoryList[i].getAt("territory_code") == XGMCustomer[j]["territory_code"]) {
                    tempListXGM.add(XGMCustomer[j])
                }
            }
            //对辖区进行a、b、c 定级
            segmentationOnArea(tempListDF)
            segmentationOnArea(tempListXGM)
            DFTempList.addAll(tempListDF)
            XGMTempList.addAll(tempListXGM)
        }
        saleCustomerResult."DFCustomer" = DFTempList
        saleCustomerResult."XGMCustomer" = XGMTempList
        saleCustomerResult
    }

    static def segmentationOnArea(List tempList) {
        if (tempList.size() == 1) {
            tempList[0]["xiaqu"] = "a" + tempList[0]["xlpm"] ?: 3
        } else if (tempList.size() == 2) {
            tempList[0]["xiaqu"] = "a" + tempList[0]["xlpm"] ?: 3
            tempList[1]["xiaqu"] = "a" + tempList[0]["xlpm"] ?: 3
        } else {
            tempList = segmentationOnScore(tempList)
        }
        tempList
    }

    static def segmentationOnScore(List tempList) {

        def sortTempList=sortByScore(tempList)
        def first = Math.round(sortTempList.size() * 0.3)-1
        def second = Math.round(sortTempList.size() * 0.75)-1
        for (int i = 0; i < sortTempList.size(); i++) {
            if (i<=first){
                tempList[i]["xiaqu"] = "c" + tempList[i]["xlpm"] ?: 3
            } else if (i<=second) {
                tempList[i]["xiaqu"] = "b" + tempList[i]["xlpm"] ?: 3
            } else {
                tempList[i]["xiaqu"] = "a" + tempList[i]["xlpm"] ?: 3
            }
        }
        tempList
    }
    def queryTerritory() {
        String sql = """SELECT
                DISTINCT t.territory_code
                FROM
                "${TENANTID}" .Customer C
                LEFT JOIN "${TENANTID}" .customer_territory ct ON C . ID = ct.Customer
                LEFT JOIN "${TENANTID}" .territory T ON T . ID = ct.territory
                LEFT JOIN "${TENANTID}" .customer_product cp on cp.Customer=c.ID
                LEFT JOIN "${TENANTID}" .product p on p.id=cp.product
                WHERE
                c.record_type='hcp'
                and p.name !='971'
                and C .is_active = TRUE
                AND C .is_deleted = FALSE
                AND ct.is_deleted = FALSE
                AND T .is_deleted = FALSE"""
        def territory = jdbcTemplate.queryForList(sql)
        if (territory)
            return territory
        return null
    }
    /**
     * A、B、C定级（按人数比例）
     * @param saleCustomerResult
     * @return
     */
    def segmentationByScore(Map saleCustomerResult){
        def DFCustomer = saleCustomerResult.get("DFCustomer") as List
        def XGMCustomer = saleCustomerResult.get("XGMCustomer") as List
        logger.info("ExportSegmentationAction is executing----segmentationByScore")
        def sortDFCustomer=sortByScore(DFCustomer)
        def sortXGMCustomer=sortByScore(XGMCustomer)
        def DFfirst = sortDFCustomer.size() * 0.3
        def DFsecond = sortDFCustomer.size() * 0.75
        for(int i=0;i<sortDFCustomer.size();i++){
            if(i<=DFfirst){
                sortDFCustomer[i]["quanguo"] = "C" + (sortDFCustomer[i]["xlpm"] ?: 3)
            }else if (i<=DFsecond) {
                sortDFCustomer[i]["quanguo"] = "B" + (sortDFCustomer[i]["xlpm"] ?: 3)
            }else{
                sortDFCustomer[i]["quanguo"] = "A" + (sortDFCustomer[i]["xlpm"] ?: 3)
            }
        }
        def XGMfirst = sortXGMCustomer.size() * 0.3
        def XGMsecond = sortXGMCustomer.size() * 0.75
        for(int i=0;i<sortXGMCustomer.size();i++){
            if(i<=XGMfirst){
                sortXGMCustomer[i]["quanguo"] = "C" + (sortXGMCustomer[i]["xlpm"] ?: 3)
            }else if (i<=XGMsecond) {
                sortXGMCustomer[i]["quanguo"] = "B" + (sortXGMCustomer[i]["xlpm"] ?: 3)
            }else{
                sortXGMCustomer[i]["quanguo"] = "A" + (sortXGMCustomer[i]["xlpm"] ?: 3)
            }
        }
        saleCustomerResult."DFCustomer" = DFCustomer
        saleCustomerResult."XGMCustomer" = XGMCustomer
        return saleCustomerResult
    }
    //排序
    static def sortByScore(List customer){
        if(customer.size()<=1) return customer
        for (int i = 0; i < customer.size(); ++i) {
            boolean flag = false
            for (int j = 0; j < customer.size() - i - 1; ++j) {
                if (customer[j]["score"] > customer[j + 1]["score"]) {
                    def temp = customer[j]
                    customer[j] = customer[j + 1]
                    customer[j + 1] = temp
                    flag = true
                }
            }
            if (!flag) break
        }
        return  customer
    }
    /**
     * 1、2、3 定级
     */
    def segmentationBySale(Map nvCustomerResult) {
        def DFCustomer = nvCustomerResult.get("DFCustomer") as List
        def XGMCustomer = nvCustomerResult.get("XGMCustomer") as List
        logger.info("ExportSegmentationAction is executing----segmentationOfSale")
        //丹酚123
        def DFSaleGroupBy = saleGroupByHandle(DFCustomer,'DF002')
        //喜格迈123
        def XGMSaleGroupBy = saleGroupByHandle(XGMCustomer,'SIG001')

        nvCustomerResult."DFCustomer" = DFSaleGroupBy
        nvCustomerResult."XGMCustomer" = XGMSaleGroupBy
        return nvCustomerResult
    }

    def saleGroupByHandle(List customer,convert_product_code) {
        //按销量从大到小查出医院的外部ID(区分产品)
        def selectSaleSql = """
        SELECT external_id ,round(SUM(sum_sales)) AS sale
        FROM
        "${TENANTID}".back_sale
        where convert_product_code = '${convert_product_code}'
        GROUP BY
        external_id
        ORDER BY
        SUM(sum_sales) DESC """

        def saleResultList = jdbcTemplate.queryForList(selectSaleSql)
        List<Integer> standard = getStandardSale(saleResultList[0]["sale"], saleResultList[saleResultList.size - 1]["sale"])
        //三等分
        Map<String, Integer> mapSale = transMap(saleResultList as List<Map>)
        /*List<List<String>> lists = averageAssign(externalIdList, 3)
        def aList = toListValue(lists[0] as List<Map>, "external_id")
        def bList = toListValue(lists[1] as List<Map>, "external_id")
        def cList = toListValue(lists[2] as List<Map>, "external_id")*/
        customer.forEach({
            def sale=mapSale.get(it["external_id"]) as Integer
            if (sale>=standard[2]) {
                it["xlpm"] = "1"
                it["sale"] = sale
            } else if (sale>=standard[1]) {
                it["xlpm"] = "2"
                it["sale"] = sale
            } else {
                it["xlpm"] = "3"
                it["sale"] = sale
            }
        })
        return customer
    }

    static List<Integer> getStandardSale(maximum, minimum){
        def list=[]
        def differ=Math.round((maximum-minimum)/3 as Double)
        for(int i=0;i<3;i++){
            list.add(differ*i+minimum)
        }
        list
    }
    static Map<String,Integer> transMap(List<Map> data){
        def result=new HashMap()
        for(Map d :data){
            result.put(d.get("external_id"),d.get("sale"))
        }
        result
    }

    /**
     * 筛选出定级为N、V的customer
     * @param hcpCustomer
     * @return
     */
    def NVCustomerHandler(Map scoreCustomerResult) {

        def DFCustomer = scoreCustomerResult.get("DFCustomer") as List
        def XGMCustomer = scoreCustomerResult.get("XGMCustomer") as List
        def VCustomer = [] as List
        def NCustomer = [] as List

        logger.info("ExportSegmentationAction is executing----VCustomerHandler")
        //根据职务职称筛选出所有的V级
        DFCustomer.forEach({
            if (POSITION_IS_V.contains(it['zhiwu'] as String) || POSTNAME_IS_V.contains(it['zhicheng'] as String) || it["leixing"] == "ht05") {
                VCustomer.add(it)
            } else if (POSITION_IS_N.contains(it['zhiwu'] as String) || POSTNAME_IS_N.contains(it['zhicheng'] as String) || it["leixing"] == "ht02") {
                NCustomer.add(it)
            }
        })
        DFCustomer.removeAll(VCustomer)
        DFCustomer.removeAll(NCustomer)
        //根据职务职称筛选出所有的N级
        logger.info("ExportSegmentationAction is executing----NCustomerHandler")
        XGMCustomer.forEach({
            if (POSITION_IS_V.contains(it['zhiwu'] as String) || POSTNAME_IS_V.contains(it['zhicheng'] as String) || it["leixing"] == "ht05") {
                VCustomer.add(it)
            } else if (POSITION_IS_N.contains(it['zhiwu'] as String) || POSTNAME_IS_N.contains(it['zhicheng'] as String) || it["leixing"] == "ht02") {
                NCustomer.add(it)
            }
        })
        XGMCustomer.removeAll(VCustomer)
        XGMCustomer.removeAll(NCustomer)
        NCustomer.forEach({
            (it["quanguo"] = "N")
            (it["xiaqu"] = "N")
        })
        VCustomer.forEach({
            (it["quanguo"] = "V")
            (it["xiaqu"] = "V")
        })
        scoreCustomerResult.put("VCustomer", VCustomer)
        scoreCustomerResult.put("NCustomer", NCustomer)
        scoreCustomerResult.put("DFCustomer", DFCustomer)
        scoreCustomerResult.put("XGMCustomer", XGMCustomer)
        logger.info("ExportSegmentationAction finished----NVCustomerHandler")
        return scoreCustomerResult
    }
    /**
     * 计算出各个产品下的客户小分
     * @param resultMap
     * @param xgmCflScoreStandard
     * @param dfCflScoreStandard
     * @param xgmCwsScoreStandard
     * @param dfCwsScoreStandard
     * @param xgmZrsScoreStandard
     * @param dfZrsScoreStandard
     * @return
     */
    def calculateCustomerHandel(Map resultMap, List xgmCflScoreStandard, List dfCflScoreStandard, List xgmCwsScoreStandard, List dfCwsScoreStandard, List xgmZrsScoreStandard, List dfZrsScoreStandard) {
        List DFCustomer = resultMap.get("DFCustomer") as List
        List XGMCustomer = resultMap.get("XGMCustomer") as List
        logger.info("ExportSegmentationAction is executing----calculateCustomerHandel")
        DFCustomer.forEach({
            def departmentScore = dFDepartmentGrade(it["department"] as String) as Integer
            def postNameScore = postNameGrade(it["zhicheng"] as String) as Integer
//            def zrsScore = (it["zrs"].toString().toDouble().toInteger()) * (it["zmzts"].toString().toDouble().toInteger())
            def zrsScore = (it["zrs"].toString().toDouble() * it["zmzts"].toString().toDouble()).toInteger()
            if (zrsScore > 0) {
                zrsScore = scoreFunction(zrsScore, dfZrsScoreStandard)
            }
            def cwsScore = it["cws"] as Double
            if (cwsScore > 0) {
                cwsScore = scoreFunction(cwsScore, dfCwsScoreStandard)
            }
            def cflScore = it["cfl"] as Double
            if (cflScore > 0) {
                cflScore = scoreFunction(cflScore, dfCflScoreStandard)
            }
            def score = (departmentScore * 0.25) + (postNameScore * 0.15) + (zrsScore * 0.20) + (cwsScore * 0.20) + (cflScore * 0.20) as Double
            it["score"] = score
            it["departmentScore"] = departmentScore * 0.25
            it["postNameScore"] = postNameScore * 0.15
            it["zrsScore"] = zrsScore * 0.20
            it["cwsScore"] = cwsScore * 0.20
            it["cflScore"] = cflScore * 0.20
        })
        XGMCustomer.forEach({
            def departmentScore = xGMDepartmentGrade(it["department"] as String) as Integer
            def postNameScore = postNameGrade(it["zhicheng"] as String) as Integer
//            def zrsScore = (it["zrs"].toString().toDouble().toInteger()) * (it["zmzts"].toString().toDouble().toInteger())
            def zrsScore = (it["zrs"].toString().toDouble() * it["zmzts"].toString().toDouble()).toInteger()
            if (zrsScore > 0) {
                zrsScore = scoreFunction(zrsScore, xgmZrsScoreStandard)
            }
            def cwsScore = it["cws"] as Double
            if (cwsScore > 0) {
                cwsScore = scoreFunction(cwsScore, xgmCwsScoreStandard)
            }
            def cflScore = it["cfl"] as Double
            if (cflScore > 0) {
                cflScore = scoreFunction(cflScore, xgmCflScoreStandard)
            }
            def score = (departmentScore * 0.25) + (postNameScore * 0.15) + (zrsScore * 0.20) + (cwsScore * 0.20) + (cflScore * 0.20) as Double
            (it["score"] = score)
            (it["departmentScore"] = departmentScore * 0.25)
            (it["postNameScore"] = postNameScore * 0.15)
            (it["zrsScore"] = zrsScore * 0.20)
            (it["cwsScore"] = cwsScore * 0.20)
            (it["cflScore"] = cflScore * 0.20)
        })
        resultMap.put("DFCustomer", DFCustomer)
        resultMap.put("XGMCustomer", XGMCustomer)
        logger.info("ExportSegmentationAction finished----calculateCustomerHandel")
        return resultMap
    }
    /**
     * 跟据产品区分customer
     * @param hcpCustomer
     * @return
     */
    def separateCustomerHandler(List hcpCustomer) {

        def DFCustomer = [] as List
        def XGMCustomer = [] as List

        def resultMap = new HashMap<String, List>()
        logger.info("ExportSegmentationAction is executing----separateCustomerHandler")

        //根据产品分成丹酚hcpCustomer,喜格迈hcpCustomer
        hcpCustomer.forEach({
            if (it['chanpin'] == "喜格迈") {
                XGMCustomer.add(it)

            }
            if (it['chanpin'] == "丹参多酚酸盐") {
                DFCustomer.add(it)
            }
        })
        resultMap.put("hcpCustomer", hcpCustomer)
        resultMap.put("DFCustomer", DFCustomer)
        resultMap.put("XGMCustomer", XGMCustomer)
        logger.info("ExportSegmentationAction finished----separateCustomerHandler")
        return resultMap
    }
    /**
     * 打分标准
     * @param table
     * @param field
     * @param label
     * @return
     */
    def gradeStandard(product, table, field, label) {
        def scoreStandard = [] as List
        def sql = """ SELECT
          COALESCE(
                ${table}.ext ->> '${field}',
                '0'
                ) AS ${label}
        FROM
        "${TENANTID}".customer C
        LEFT JOIN
        "${TENANTID}".customer_product cp ON C.ID = cp.customer
        LEFT JOIN
        "${TENANTID}".customer cc ON cc.ID = C.parent_id
        LEFT JOIN
        "${TENANTID}".product P ON P.ID = cp.product
        LEFT JOIN
        "${TENANTID}".customer_territory ct ON C.ID = ct.customer
        LEFT JOIN
        "${TENANTID}".territory T ON ct.territory = T.ID
        WHERE
        C.record_type = 'hcp'
        and p.name = '${product}'
        AND T. ext ->> 'bu_name' = '丹酚事业部-自营'
        AND T.NAME NOT LIKE '%Test%'
        AND C.is_active = TRUE
        AND C.is_deleted = FALSE
        AND cp . is_deleted = FALSE
        AND ct . is_deleted = FALSE
        AND T. is_deleted = FALSE """

        def resultList = jdbcTemplate.queryForList(sql)
        def tempSet = [] as Set<Double>
        if (resultList) {
            resultList.forEach({
                tempSet.add((it[label].toString()).toDouble())
            })
        }
        tempSet = tempSet.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList()) as List
        for (int i = 10; i > 0; i--) {
            def standardScore = tempSet[0] / 10 as Double
            scoreStandard.add(standardScore * i)
        }
        return scoreStandard
    }

    //周人数打分-20%=周门诊天数*每天病人数
    def zrsGradeStandard(product) {
        def zrsScoreStandard = [] as List
        def sql = """ SELECT
          COALESCE (
                C .ext ->> 'daily_patient_number' :: VARCHAR,
                '0'
                ) :: int4 AS zrs,
                COALESCE (
                C .ext ->> 'weekly_outpatient_days' :: VARCHAR,
                '0'
                ) AS zmzts
        FROM
        "${TENANTID}".customer C
        LEFT JOIN
        "${TENANTID}".customer_product cp ON C.ID = cp.customer
        LEFT JOIN
        "${TENANTID}".customer cc ON cc.ID = C.parent_id
        LEFT JOIN
        "${TENANTID}".product P ON P.ID = cp.product
        LEFT JOIN
        "${TENANTID}".customer_territory ct ON C.ID = ct.customer
        LEFT JOIN
        "${TENANTID}".territory T ON ct.territory = T.ID
        WHERE
        C.record_type = 'hcp'
        --AND cp.ext ->> ' avg_prescription_qty ' != '0'
        and p.name = '${product}'
        --AND P. NAME != '971'
        AND T. ext ->> 'bu_name' = '丹酚事业部-自营'
        AND T.NAME NOT LIKE '%Test%'
        AND C.is_active = TRUE
        AND C.is_deleted = FALSE
        AND cp . is_deleted = FALSE
        AND ct . is_deleted = FALSE
        AND T. is_deleted = FALSE """
        def resultList = jdbcTemplate.queryForList(sql)
        def tempSet = [] as Set<Integer>
        if (resultList) {
            resultList.forEach({
                tempSet.add(((it["zrs"].toString().toDouble().toInteger()) * ((it["zmzts"].toString().toDouble().toInteger()))))
            })
        }
        tempSet = tempSet.stream().sorted(Comparator.reverseOrder()).collect(Collectors.toList()) as List
        for (int i = 10; i > 0; i--) {
            def standardScore = tempSet[0] / 10 as Integer
            zrsScoreStandard.add(standardScore * i)
        }
        return zrsScoreStandard
    }
    /**
     * 十等分逻辑单项打分
     * @param cfl
     * @param cflScoreStandard
     * @return
     */
    static def scoreFunction(Double score, List<Double> scoreStandard) {
        def scoreResult = 0
        if (score >= scoreStandard[1]) {
            scoreResult = 10
        } else if (score >= scoreStandard[2]) {
            scoreResult = 9
        } else if (score >= scoreStandard[3]) {
            scoreResult = 8
        } else if (score >= scoreStandard[4]) {
            scoreResult = 7
        } else if (score >= scoreStandard[5]) {
            scoreResult = 6
        } else if (score >= scoreStandard[6]) {
            scoreResult = 5
        } else if (score >= scoreStandard[7]) {
            scoreResult = 4
        } else if (score >= scoreStandard[8]) {
            scoreResult = 3
        } else if (score >= scoreStandard[9]) {
            scoreResult = 2
        } else if (score > 0) {
            scoreResult = 1
        }
        return scoreResult
    }
    /**
     *  查询record_type为hcp，产品为丹酚、喜格迈的customer数据
     */
    def getHcpCustomer() {
        def queryHcpSql = """SELECT
                T . ID AS tid,
                T .ext ->> 'territory_name' AS tname,
                C .external_id AS cid,
                C . NAME,
                C .department,
                C .admin_title AS zhiwu,
                C .major_title AS zhicheng,
                C .ext ->> 'hcp_type' AS leixing,
                cc.external_id,
                T .territory_code,
                cc. NAME AS yiyuan,
                P . NAME AS chanpin,
                COALESCE (
                C .ext ->> 'daily_patient_number' :: VARCHAR,
                '0'
                ) :: int4 AS zrs,
                COALESCE (
                C .ext ->> 'weekly_outpatient_days',
                '0'
                ) AS zmzts,
                COALESCE (C .ext ->> 'bed_qty_hcp', '0') AS cws,
                COALESCE (
                cp .ext ->> 'avg_prescription_qty',
                '0'
                ) AS cfl,
                cp.ext ->> 'personal_segmentation' AS xitong
                FROM
                "${TENANTID}".customer C
                LEFT JOIN "${TENANTID}".customer_product cp ON C . ID = cp.customer
                LEFT JOIN "${TENANTID}".customer cc ON cc. ID = C .parent_id
                LEFT JOIN "${TENANTID}".product P ON P . ID = cp.product
                LEFT JOIN "${TENANTID}".customer_territory ct ON C . ID = ct.customer
                LEFT JOIN "${TENANTID}".territory T ON ct.territory = T . ID
                WHERE
                C .record_type = 'hcp'
                AND P . NAME != '971'
                AND T .ext ->> 'bu_name' = '丹酚事业部-自营'
                AND T . NAME NOT LIKE '%Test%'
                AND C .is_active = TRUE
                AND C .is_deleted = FALSE
                AND cp.is_deleted = FALSE
                AND ct.is_deleted = FALSE
                AND T .is_deleted = FALSE"""
        def hcpCustomer = jdbcTemplate.queryForList(queryHcpSql) as List
        logger.info("ExportSegmentationAction query hcpCustomer is----" + hcpCustomer.size())
        if (hcpCustomer) {
            def userInfo = queryUserByTerritory() as List
            hcpCustomer.forEach({
                for (int i = 0; i < userInfo.size(); i++) {
                    if (userInfo[i]."territory_code" == it["territory_code"]) {
                        it["ucode"] = userInfo[i]."ucode"
                        it["uname"] = userInfo[i]."uname"
                        break
                    }
                }
                /*userInfo.forEach { u ->
                    if (u["territory"] == it["territory"]) {
                        it["ucode"] = u["ucode"]
                        it["uname"] = u["uname"]
                    }
                }*/
            })
        }
        return hcpCustomer
    }

    def queryUserByTerritory() {
        def sql = """SELECT
                t.territory_code,
                T.id AS territory,
                u.external_id AS ucode,
                u. NAME AS uname
                FROM
                "${TENANTID}".territory T
                LEFT JOIN "${TENANTID}".user_territory ut ON T . ID = ut.territory
                LEFT JOIN "${TENANTID}".user_info u ON ut.user_info = u. ID
                WHERE
                T .is_deleted = FALSE
                AND ut.is_deleted = FALSE
                AND u.is_deleted = FALSE"""

        return jdbcTemplate.queryForList(sql)
    }
    /**
     * 科室职称打分标准
     * @return
     */
    static def postNameGrade(postName) {
        switch (postName as String) {
            case 'zc17': return 10
                break
            case 'zc18': return 9
                break
            case 'zc19': return 8
                break
            case 'zc20': return 5
                break
            case 'zc21': return 2
                break
            default: return 0
                break
        }
    }

    static def dFDepartmentGrade(String department) {
        def department2Int = department.toInteger() as Integer
        switch (department2Int) {
            case [2, 6, 28, 31, 44, 67, 100, 101, 110, 121, 122, 127, 141, 143, 151, 185, 199, 206]: return 4
                break
            case 7: return 2
                break
            case [34, 70, 108, 111, 119, 134, 135, 165, 184]: return 3
                break
            case [46, 63, 98, 103, 104, 106, 187, 194, 196]: return 8
                break
            case [65, 102, 113, 116, 129, 193, 197, 200, 205, 209, 210, 211, 212, 213, 214]: return 5
                break
            case [77, 105, 139, 180, 181, 186, 192, 195]: return 7
                break
            case [107, 109, 198]: return 10
                break
            case [123, 124, 125, 136, 201, 208]: return 6
                break
            case [140, 204]: return 1
                break
            case [188, 190, 191]: return 9
                break
            default: return 0
                break
        }
    }

    static def xGMDepartmentGrade(department) {
        def department2Int = department.toInteger() as Integer
        switch (department2Int) {
            case [46, 119, 136, 139, 172, 187]: return 4
                break
            case [7, 108, 110, 122, 123, 171, 199, 208, 209, 214]: return 2
                break
            case [65, 152]: return 3
                break
            case [63, 115, 194]: return 8
                break
            case [71, 77, 98, 113, 116, 180, 188]: return 5
                break
            case [42, 104]: return 7
                break
            case [103, 107, 109, 190, 198, 199]: return 10
                break
            case [105, 106, 181, 191, 195]: return 6
                break
            case [49, 52, 59, 61]: return 1
                break
            case [188, 190, 191]: return 9
                break
            default: return 0
                break
        }
    }
    /**
     * 导出定级数据方法
     * @param resultMap
     * @param response
     * @throws IOException
     */
    void download(resultMap, HttpServletResponse response) throws IOException {

        OutputStream os = null

        XSSFWorkbook workbook = new XSSFWorkbook()
        //创建一个Excel表单,参数为sheet的名字

        XSSFSheet sheet = workbook.createSheet("客户定级")
        //支持筛选
        CellRangeAddress c = CellRangeAddress.valueOf("A1:Z1")
        sheet.setAutoFilter(c)
        sheet.lockAutoFilter(false)
        String[] title = ["员工编号", "用户名称", "岗位编号", "岗位名称", "医生id", "医生姓名", "科室", "科室打分", "职务", "职称", "职称打分", "医生类型", "所属医院", "医院编码", "周门诊天数", "每天病人数", "周人数打分", "总床位数", "床位数打分", "每周处方均量", "处方打分", "医生总分", "产品", "产品销量", "全国定级", "辖区内定级", "代表定级"]
        //创建表头
        setTitle(sheet, workbook, title)

//新增数据行，并且设置单元格数据
        int rowNum = 1
        def result = resultMap."DFCustomer" as List
        result.addAll(resultMap."XGMCustomer" as List)
        result.addAll(resultMap."NCustomer" as List)
        result.addAll(resultMap."VCustomer" as List)

        for (int i = 0; i < result.size(); i++) {
            XSSFRow row = sheet.createRow(rowNum)
            sheet.setColumnWidth(0, 100 * 35)
            sheet.setColumnWidth(1, 100 * 25)
            sheet.setColumnWidth(2, 100 * 35)
            sheet.setColumnWidth(3, 100 * 35)
            sheet.setColumnWidth(4, 100 * 50)
            sheet.setColumnWidth(5, 100 * 25)
            sheet.setColumnWidth(6, 100 * 50)
            sheet.setColumnWidth(7, 100 * 30)
            sheet.setColumnWidth(8, 100 * 30)
            sheet.setColumnWidth(9, 100 * 30)
            sheet.setColumnWidth(10, 100 * 30)
            sheet.setColumnWidth(11, 100 * 30)
            sheet.setColumnWidth(12, 100 * 80)
            sheet.setColumnWidth(13, 100 * 30)
            sheet.setColumnWidth(14, 100 * 30)
            sheet.setColumnWidth(15, 100 * 30)
            sheet.setColumnWidth(16, 100 * 30)
            sheet.setColumnWidth(17, 100 * 30)
            sheet.setColumnWidth(18, 100 * 30)
            sheet.setColumnWidth(19, 100 * 30)
            sheet.setColumnWidth(20, 100 * 30)
            sheet.setColumnWidth(21, 100 * 30)
            sheet.setColumnWidth(22, 100 * 30)
            sheet.setColumnWidth(23, 100 * 30)
            sheet.setColumnWidth(24, 100 * 25)
            sheet.setColumnWidth(25, 100 * 25)
            sheet.setColumnWidth(26, 100 * 25)

            row.createCell(0).setCellValue(result[i].getAt("ucode")?result[i].getAt("ucode").toString():"")
            row.createCell(1).setCellValue(result[i].getAt("uname")?result[i].getAt("uname").toString():"")
            row.createCell(2).setCellValue(result[i].getAt("territory_code").toString())
            row.createCell(3).setCellValue(result[i].getAt("tname").toString())
            row.createCell(4).setCellValue(result[i].getAt("cid").toString())
            row.createCell(5).setCellValue(result[i].getAt("name").toString())
            row.createCell(6).setCellValue((DEPARMENT.get(result[i].getAt("department"))) ?: "")
            row.createCell(7).setCellValue(result[i].getAt("departmentScore") ? result[i].getAt("departmentScore").toString() : "0")
            row.createCell(8).setCellValue((POSITION.get(result[i].getAt("zhiwu"))) ?: "其他")
            row.createCell(9).setCellValue((POST_NAME.get(result[i].getAt("zhicheng"))) ?: "无职称")
            row.createCell(10).setCellValue(result[i].getAt("postNameScore") ? result[i].getAt("postNameScore").toString() : "0")
            row.createCell(11).setCellValue((HCP_TYPE.get(result[i].getAt("leixing"))) ?: "其他技术人员")
            row.createCell(12).setCellValue(result[i].getAt("yiyuan").toString())
            row.createCell(13).setCellValue(result[i].getAt("external_id").toString())
            row.createCell(14).setCellValue(result[i].getAt("zmzts") ? result[i].getAt("zmzts").toString() : "0")
            row.createCell(15).setCellValue(result[i].getAt("zrs") ? result[i].getAt("zrs").toString() : "0")
            row.createCell(16).setCellValue(result[i].getAt("zrsScore") ? result[i].getAt("zrsScore").toString() : "0")
            row.createCell(17).setCellValue(result[i].getAt("cws") ? result[i].getAt("cws").toString() : "0")
            row.createCell(18).setCellValue(result[i].getAt("cwsScore") ? result[i].getAt("cwsScore").toString() : "0")
            row.createCell(19).setCellValue(result[i].getAt("cfl") ? result[i].getAt("cfl").toString() : "0")
            row.createCell(20).setCellValue(result[i].getAt("cflScore") ? result[i].getAt("cflScore").toString() : "0")
            row.createCell(21).setCellValue(result[i].getAt("score") ?result[i].getAt("score") as Double:0)
            row.createCell(22).setCellValue(result[i].getAt("chanpin").toString())
            row.createCell(23).setCellValue(result[i].getAt("sale") ? result[i].getAt("sale").toString() : "0")
            row.createCell(24).setCellValue(result[i].getAt("quanguo").toString())
            row.createCell(25).setCellValue(result[i].getAt("xiaqu").toString())
            row.createCell(26).setCellValue(result[i].getAt("xitong").toString())

            rowNum++
        }
        try {
            //清空response
            response.reset()
            //设置response的Header
            response.addHeader("Content-Disposition", "attachment;filename=" + "segmentation.xlsx")
            os = new BufferedOutputStream(response.getOutputStream())
            response.setContentType("application/vnd.ms-excel;charset=gb2312")
            //将excel写入到输出流中
            workbook.write(os)
            os.flush()
            os.close()
        } catch (Exception e) {
            logger.info("文件导出失败")
            e.printStackTrace()
        } finally {
            if (os != null)
                os.close()
        }
    }
    static void setTitle(Sheet sheet, Workbook workbook, String[] title) {

        Row row = sheet.createRow(0)// 第一行为表头
        // 设置为居中加粗
        CellStyle style = workbook.createCellStyle()
        style.setAlignment(HorizontalAlignment.CENTER)//左右居中
        Font font = workbook.createFont()
        font.setBold(true)
        style.setFont(font)
        Cell cell

        for (int i = 0; i < title.length; i++) {
            cell = row.createCell(i)
            cell.setCellValue(title[i])
            cell.setCellStyle(style)
        }
    }
    final private def POSITION = [
            "1" : "病区副护士长", "2": "病区护士长", "3": "采购", "4": "处长", "5": "床位医生",
            "6" : "大科副护士长", "7": "大科副主任", "8": "大科护士长", "9": "大科主任", "10": "带组医生",
            "11": "副处长", "12": "副经理", "13": "副局长", "14": "副科长", "15": "副书记",
            "16": "副所长", "17": "副院长", "18": "副站长", "19": "副主任", "20": "进修护士",
            "21": "进修医生", "22": "经理", "23": "局长", "24": "科副护士长", "25": "科护士长", "26": "科长",
            "27": "库管", "28": "秘书", "29": "普通职员", "30": "其他", "31": "书记", "32": "所长", "33": "院长",
            "34": "院长助理", "35": "站长", "36": "主任", "37": "主任助理", "38": "住院部医生", "39": "总护士长",
    ] as Map<String, String>
    final private def POST_NAME = [
            "zc01": "主任技师", "zc02": "副主任技师", "zc03": "主管技师", "zc04": "技师",
            "zc05": "按摩师", "zc06": "康复治疗师", "zc07": "美容师", "zc08": "麻醉师",
            "zc09": "主管麻醉师", "zc10": "副主任麻醉师", "zc11": "主任麻醉师", "zc12": "主任护师",
            "zc13": "副主任护师", "zc14": "主管护师", "zc15": "护师", "zc16": "护士",
            "zc17": "主任医师", "zc18": "副主任医师", "zc19": "主治医师", "zc20": "住院医师",
            "zc21": "实习医生", "zc22": "主任检验师", "zc23": "副主任检验师", "zc24": "主管检验师",
            "zc25": "检验师", "zc26": "主任药师", "zc27": "副主任药师", "zc28": "主管药师",
            "zc29": "药师", "zc30": "药士", "zc31": "无职称", "zc32": "实验员"
    ] as Map<String, String>
    final private def HCP_TYPE = [
            "ht01": "技师", "ht02": "护师", "ht03": "医师",
            "ht04": "检验师", "ht05": "药师", "ht06": "其他技术人员"
    ] as Map<String, String>

    final private def DEPARMENT = [
            "110": "内科_血液内科", "111": "皮肤科", "112": "皮肤科_性传播疾病科", "113": "全科医疗科", "114": "手术室", "115": "特需病房",
            "116": "特需科", "117": "特种医学与军事医学科", "118": "疼痛科", "119": "体检中心", "10": "儿科_小儿传染病科", "11": "儿科_小儿呼吸科",
            "12" : "儿科_小儿免疫科", "13": "儿科_小儿内分泌科", "14": "儿科_小儿内科", "15": "儿科_小儿神经病学科", "16": "儿科_小儿肾病科",
            "17" : "儿科_小儿消化科", "18": "儿科_小儿心脏病科", "19": "儿科_小儿血液病科", "120": "头颈科", "121": "外科_腹部外科", "1": "病理科",
            "122": "外科_肝胆外科", "2": "传染科", "123": "外科_骨科", "3": "传染科_肠道传染病科", "124": "外科_骨科关节组", "4": "传染科_虫媒传染病科",
            "125": "外科_骨伤科", "5": "传染科_动物源性传染病科", "126": "外科_关节科", "6": "传染科_肝炎科", "127": "外科_泌尿外科", "7": "传染科_呼吸道传染病科",
            "128": "外科_泌尿外科_肾脏移植科", "8": "传染科_蠕虫病科", "129": "外科_普通外科", "9": "儿科", "20": "儿科_小儿遗传病科", "21": "儿科_新生儿科",
            "22" : "儿童保健科", "23": "儿童保健科_儿童康复科", "24": "儿童保健科_儿童生长发育科", "25": "儿童保健科_儿童五官保健科", "26": "儿童保健科_儿童心理卫生科",
            "27" : "儿童保健科_儿童营养科", "28": "耳鼻咽喉科", "29": "耳鼻咽喉科_鼻科", "130": "外科_普通外科_肝脏移植科", "131": "外科_普通外科_小肠移植科", "132": "外科_普通外科_胰腺移植科",
            "133": "外科_普通外科_移植科", "134": "外科_乳腺外科", "135": "外科_烧伤科", "136": "外科_神经外科", "137": "外科_微创外科", "138": "外科_胃肠外科", "139": "外科_心脏大血管外科",
            "30" : "耳鼻咽喉科_耳科", "31": "耳鼻咽喉科_咽喉科", "32": "妇产科", "33": "妇产科_产科", "34": "妇产科_妇科", "35": "妇产科_计划生育科", "36": "妇产科_生殖健康与不孕症科",
            "37" : "妇产科_优生学科", "38": "妇女保健科", "39": "妇女保健科_妇女心理卫生科", "140": "外科_心脏大血管外科_心脏移植科", "141": "外科_胸外科",
            "142": "外科_胸外科专业_肺脏移植科", "143": "外科_整形外科", "144": "未知", "145": "小儿外科", "146": "小儿外科_小儿骨科",
            "147": "小儿外科_小儿泌尿外科", "148": "小儿外科_小儿普通外科", "149": "小儿外科_小儿神经外科", "40": "妇女保健科_妇女营养科",
            "41" : "妇女保健科_更年期保健科", "42": "妇女保健科_青春期保健科", "43": "妇女保健科_围产期保健科", "44": "肝病科", "45": "感染科",
            "46" : "干部科", "47": "高压氧科", "48": "公共卫生科", "49": "管理科室", "150": "小儿外科_小儿胸心外科", "151": "眼科", "152": "药剂科",
            "153": "医疗美容科", "154": "医学检验科", "155": "医学检验科_临床免疫、血清学科", "156": "医学检验科_临床生化检验科", "157": "医学检验科_临床体液、血液科", "158": "医学检验科_临床微生物学科", "159": "医学检验科_临床细胞分子遗传学科", "50": "管理科室_病案室", "51": "管理科室_财务科", "52": "管理科室_采购科", "53": "管理科室_党委办公室", "54": "管理科室_人事科",
            "55" : "管理科室_书记办公室", "56": "管理科室_信息宣传科", "57": "管理科室_医保办公室", "58": "管理科室_医教科", "59": "管理科室_医务科",
            "160": "医学影像科", "161": "医学影像科_CT诊断科", "162": "医学影像科_X线诊断科", "163": "医学影像科_超声诊断科", "164": "医学影像科_磁共振成像诊断科", "165": "医学影像科_放射治疗科", "166": "医学影像科_核医学科", "167": "医学影像科_介入放射学科", "168": "医学影像科_脑电及脑血流图诊断科", "169": "医学影像科_神经肌肉电图科", "60": "管理科室_医院办公室", "61": "管理科室_院长办公室", "62": "管理科室_质控科",
            "63" : "冠心病监护病房", "64": "护理科", "65": "急诊医学科", "66": "疾病控制科", "67": "结核病科", "68": "结石病科", "69": "精神科",
            "170": "医学影像科_心电诊断科", "171": "营养科", "172": "预防保健科", "173": "运动医学科", "174": "职业病科", "175": "职业病科_尘肺科",
            "176": "职业病科_放射病科", "177": "职业病科_物理因素损伤科", "178": "职业病科_职业健康监护科", "179": "职业病科_职业中毒科",
            "70" : "精神科_精神病科", "71": "精神科_精神康复科", "72": "精神科_精神卫生科", "73": "精神科_临床心理科", "74": "精神科_社区防治科",
            "75" : "精神科_司法精神科", "76": "精神科_药物依赖科", "77": "康复医学科", "78": "口腔科", "79": "口腔科_口腔颌面外科", "180": "中西医结合科",
            "181": "中医科", "182": "中医科_儿科", "183": "中医科_耳鼻咽喉科", "184": "中医科_妇产科", "185": "中医科_肛肠科", "186": "中医科_骨伤科",
            "187": "中医科_急诊科", "188": "中医科_康复医学科", "189": "中医科_口腔科", "80": "口腔科_口腔修复科", "81": "口腔科_口腔牙周病科",
            "82" : "口腔科_口腔预防保健科", "83": "口腔科_口腔粘膜病科", "84": "口腔科_口腔整形美容科", "85": "口腔科_口腔种植科",
            "86" : "口腔科_牙体牙髓病科", "87": "口腔科_正畸科", "88": "疗养中心", "89": "临终关怀科", "190": "中医科_老年病科", "191": "中医科_内科",
            "192": "中医科_内科_风湿免疫科", "193": "中医科_内科_呼吸科", "194": "中医科_内科_内分泌科", "195": "中医科_内科_神经内科",
            "196": "中医科_内科_肾内科", "197": "中医科_内科_消化科", "198": "中医科_内科_心内科", "199": "中医科_皮肤科", "90": "麻醉科",
            "91" : "门诊部_发热门诊", "92": "民族医学科", "93": "民族医学科_藏医学科", "94": "民族医学科_傣医学科", "95": "民族医学科_蒙医学科",
            "96" : "民族医学科_维吾尔医学科", "97": "民族医学科_彝医学科", "98": "内科", "99": "内科_变态反应科", "200": "中医科_推拿科",
            "201": "中医科_外科", "202": "中医科_外科_泌尿外科", "203": "中医科_外科_神经外科", "204": "中医科_外科_心胸外科",
            "205": "中医科_外科_血管外科", "206": "中医科_眼科", "207": "中医科_预防保健科", "208": "中医科_针灸科", "209": "中医科_肿瘤科",
            "210": "肿瘤科", "211": "肿瘤科_肿瘤介入科", "212": "肿瘤科_肿瘤内科", "213": "肿瘤科_肿瘤外科", "214": "重症医学科",
            "100": "内科_风湿免疫科", "101": "内科_肝胆内科", "102": "内科_呼吸内科", "103": "内科_老年病科", "104": "内科_内分泌科",
            "105": "内科_神经内科", "106": "内科_肾病科", "107": "内科_糖尿病科", "108": "内科_消化内科", "109": "内科_心血管内科"
    ] as Map<String, String>
}
