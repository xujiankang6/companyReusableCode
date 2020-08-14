package com.forceclouds.crm.task.impl;

import com.forceclouds.context.ContextHolder;
import com.forceclouds.crm.db.TenantTransactionManagerHolder;
import com.forceclouds.crm.exceptions.ExportException;
import com.forceclouds.crm.exceptions.ExportFileUploadRequestException;
import com.forceclouds.crm.inter.ICallback;
import com.forceclouds.crm.metadata.model.IExportHistory;
import com.forceclouds.crm.metadata.model.IFieldDescribe;
import com.forceclouds.crm.metadata.service.IBeCarefulUseSelectService;
import com.forceclouds.crm.metadata.service.impl.FieldDescribeService;
import com.forceclouds.crm.properties.ExportTaskProperties;
import com.forceclouds.crm.task.IExportService;
import com.forceclouds.crm.task.IExportUploadService;
import com.forceclouds.crm.utils.TextUtils;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.opencsv.CSVWriter;
import groovy.json.JsonSlurper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.map.ListOrderedMap;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.*;
import java.util.stream.Collectors;

/*
 *@create by jiankang
 *@date 2020/8/11 time 18:08
 */

@Service
@Slf4j
public class ExportService implements IExportService {
    @Autowired
    private IBeCarefulUseSelectService beCarefulUseSelectService;

    @Autowired
    private IExportUploadService exportUploadService;

    @Autowired
    private ExportTaskProperties exportTaskProperties;

    @Autowired
    private FieldDescribeService fieldDescribeService;


    @Autowired
    private TenantTransactionManagerHolder tenantTransactionManagerHolder;


    @Override
    public void export(IExportHistory exportHistory, ICallback successCallback, ICallback failCallback, ICallback processingCallback) throws ExportException {
        Connection connection = null;
        ResultSet rs = null;
        try {
            processingCallback.execute("数据导出操作开始");
            String script = exportHistory.getScripts();
            processingCallback.execute("执行sql查询数据库：" + script);
            connection = getConnection();
            rs = getRowCursor(connection, script);
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            //记录总条数
            int sumSize = 0;
            //表头数组
            String[] keys = new String[0];

            //10000条数据写一次，视情况而定
            Integer writeSize = exportTaskProperties.getPartSize();
            //保存数据库查出的数据
            List<ListOrderedMap> list = new ArrayList<>(writeSize);
            /**
             * csv文件流初始化
             */
            String tempFileName = exportHistory.getName() + "-" + new SimpleDateFormat("YYYY-MM-dd-HH-mm").format(new Date());
            processingCallback.execute("创建临时csv文件,文件名称:" + tempFileName + ".csv");
            File tmpFile = File.createTempFile(tempFileName, ".csv");
            FileOutputStream fileOutputStream = new FileOutputStream(tmpFile, true);
            byte[] uft8bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
            fileOutputStream.write(uft8bom);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fileOutputStream, "UTF-8"));
            CSVWriter csvWriter = new CSVWriter(writer);


            /**
             * 转换label映射
             */
            processingCallback.execute("开始解析label映射.\r\n" + exportHistory.getLabel());
            Map<String, Map<String, Object>> labelConvertMap = getLabelConvertMap(exportHistory.getLabel());
            processingCallback.execute("label映射解析完毕.");


            while (rs.next()) {
                ListOrderedMap rowData = new ListOrderedMap();
                for (int i = 1; i <= columnCount; ++i) {
                    Object value = rs.getObject(i);
                    String columnLabel = metaData.getColumnName(i);
                    if (value instanceof PGobject && ((PGobject) value).getType().equals("json")) {
                        rowData.put(columnLabel, (new JsonSlurper()).parseText(((PGobject) value).getValue()));
                    } else {
                        rowData.put(columnLabel, value);
                    }
                }
                sumSize++;
                list.add(rowData);

                /**
                 * 第一行写入表头
                 */
                if (sumSize == 1) {
                    Set<String> keySet = ((Map<String, Object>) list.get(0)).keySet();
                    keys = keySet.toArray(new String[keySet.size()]);
                    csvWriter.writeNext(keys);
                    processingCallback.execute("生成csv文件表头.\r\n" + String.join("\r\n", Lists.newArrayList(keys)));

                }

                if (sumSize % writeSize == 0) {
                    // 写入csv文件
                    writeCsvFile(processingCallback, csvWriter, keys, labelConvertMap, list, sumSize);
                }

            }


            if (sumSize == 0) {
                QueryNoData(exportHistory, successCallback, processingCallback, csvWriter, tmpFile);
                return;
            }

            if (list.size() > 0) {
                // 写入csv文件
                writeCsvFile(processingCallback, csvWriter, keys, labelConvertMap, list, sumSize);
            }
            processingCallback.execute("共导出数据条数：" + sumSize + "条");
            //上传csv文件，并删除临时文件
            uploadCSVFile(exportHistory, successCallback, processingCallback, failCallback, csvWriter, tmpFile);
        } catch (Exception e) {
            throw new ExportException(e.getMessage(), e);
        } finally {
            closeConnection(connection);
        }
    }


    /**
     * 写入csv文件
     */
    private void writeCsvFile(ICallback processingCallback, CSVWriter csvWriter, String[] keys,
                              Map<String, Map<String, Object>> labelConvertMap, List<ListOrderedMap> list, int sumSize) {
        try {
            processingCallback.execute("开始数据转换并写入文件");
            List result = dataTransferToArray(keys, list, labelConvertMap);
            csvWriter.writeAll(result);
            list.clear();
            csvWriter.flush();
        } catch (Exception e) {
            processingCallback.execute("数据转换并写入文件失败： " + e);
        } finally {
            processingCallback.execute(String.format("数据转换并写入文件成功，已写入行数:%d 。 ", sumSize));
        }
    }


    /**
     * 上传csv文件供下载，删除临时文件
     *
     * @param exportHistory
     * @param successCallback
     * @param processingCallback
     * @param failCallback
     * @param csvWriter
     * @param tmpFile
     */
    private void uploadCSVFile(IExportHistory exportHistory, ICallback successCallback, ICallback processingCallback, ICallback failCallback, CSVWriter csvWriter, File tmpFile) {
        try {
            processingCallback.execute("生成的文件大小:" + (tmpFile.length() / 1024) + "Kb");
            /**
             * 上传文件并获取结果
             */
            processingCallback.execute("开始上传csv文件.");
            ResponseEntity<Map> uploadResult = exportUploadService.upload(exportHistory, tmpFile);
            processingCallback.execute("csv文件上传完毕,获取到的上传结果\r\n" + TextUtils.printResponseEntiry(uploadResult));

            if (uploadResult.getStatusCodeValue() == 200) {
                processingCallback.execute("csv上传文件成功.");
                Map body = uploadResult.getBody();
                exportHistory.setFileKey(body.get("key").toString());
                exportHistory.setFileType(body.get("file-extension").toString());
                /**
                 * 将结果返回给调用者
                 */
                successCallback.execute(exportHistory);
            } else {
                failCallback.execute(new ExportFileUploadRequestException(TextUtils.printResponseEntiry(uploadResult)));
            }
            csvWriter.close();
        } catch (Exception e) {
            failCallback.execute(e);
        } finally {
            tmpFile.delete();
            processingCallback.execute("关闭文件流，删除临时文件,数据导出操作结束！");
        }
    }


    /**
     * *对数据进行转换
     *
     * @param keys
     * @param list
     * @param labelConvertMap
     * @return
     */

    private List dataTransferToArray(String[] keys, List<ListOrderedMap> list, Map<String, Map<String, Object>> labelConvertMap) {
        String[] finalKeys = keys;
        List result = list.stream().map(it -> {
            Map<String, Object> item = (Map<String, Object>) it;
            String[] strings = new String[finalKeys.length];
            int i = -1;
            for (String key : finalKeys) {
                i++;
                Object currValue = item.get(key);
                if (currValue == null) {
                    continue;
                }

                Map<String, Object> map = labelConvertMap.get(key);
                if (map == null) {
                    strings[i] = currValue.toString();
                    continue;
                }

                List<String> cValue = new ArrayList<>();
                if (currValue instanceof List) {
                    cValue = (List) currValue;
                } else {
                    cValue.add(currValue.toString());
                }
                List<Object> collect = cValue.stream().map(value -> {
                    if (map.get(value) != null) {
                        return map.get(value);
                    }
                    return value;
                }).collect(Collectors.toList());
                if (collect.size() > 1) {
                    String data = collect.get(0).toString();
                    for (int d = 1; d < collect.size(); d++) {
                        data += "," + collect.get(d);
                    }
                    strings[i] = data;
                } else {
                    strings[i] = collect.size() > 0 ? collect.get(0).toString() : "";
                }

            }
            return strings;
        }).collect(Collectors.toList());

        return result;
    }

    /**
     * 查询不到数据日志
     *
     * @param exportHistory
     * @param successCallback
     * @param processingCallback
     * @param csvWriter
     * @param tmpFile
     */
    private void QueryNoData(IExportHistory exportHistory, ICallback successCallback, ICallback processingCallback, CSVWriter csvWriter, File tmpFile) {
        processingCallback.execute("数据查询完毕.");
        processingCallback.execute("没有查询到任何结果.");
        /**
         * 任务执行成功，但是没有生成任何文件
         */
        successCallback.execute(exportHistory);
        try {
            csvWriter.close();
            tmpFile.delete();
        } catch (Exception e) {
            processingCallback.execute("关闭文件流，删除临时文件报错");
        } finally {
            processingCallback.execute("关闭文件流，删除临时文件,数据导出操作结束,无数据文件生成！");
        }
    }


    /**
     * 获取数据库游标
     *
     * @param sql
     * @return
     * @throws SQLException
     */
    private ResultSet getRowCursor(Connection connection, String sql) {
        ResultSet rs = null;
        try {
            Statement statement = connection.createStatement();
            rs = statement.executeQuery(sql);
            return rs;
        } catch (Exception e) {
            log.info("error:{}", e);
        } finally {
            return rs;
        }
    }

    /**
     * 获取数据库连接
     *
     * @return
     * @throws SQLException
     */
    private Connection getConnection() {
        Connection connection = null;
        try {
            connection = this.tenantTransactionManagerHolder.getDataSource().getConnection();
            connection.setSchema(ContextHolder.get().currentUser().getTenantId());
            return connection;
        } catch (Exception e) {
            log.info("error:{}", e);
        } finally {
            return connection;
        }
    }


    /**
     * 关闭数据库连接
     *
     * @return
     */
    private void closeConnection(Connection connection) {
        if (!Objects.equals(connection, (Object) null)) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.info("database connection error." + e);
            }
            if (log.isInfoEnabled()) {
                log.info("database connection released.");
            }
        }
    }

    /**
     * label进行转换
     *
     * @param runLabel
     * @return
     * @throws SQLException
     */
    private Map<String, Map<String, Object>> getLabelConvertMap(String runLabel) throws SQLException {
        if (runLabel.contains("FC_FIELD") || runLabel.contains("FC_SQL")) {
            Map<String, Map<String, Object>> map = new Gson().fromJson(runLabel, Map.class);
            for (String s : map.keySet()) {
                Map<String, Object> innerMap = map.get(s);
                Set<String> outerSet = innerMap.keySet();
                if (outerSet.contains("FC_FIELD")) {
                    String[] fc_field = ((String) innerMap.get("FC_FIELD")).split("\\.");
                    IFieldDescribe fieldDescribe = fieldDescribeService.findByObjectApiNameAndFieldApiName(fc_field[0], fc_field[1]).get();
                    List<HashMap> options = (List<HashMap>) fieldDescribe.get("options");
                    for (Map option : options) {
                        innerMap.put((String) option.getOrDefault("value", ""), (String) option.getOrDefault("label", ""));
                    }
                    innerMap.remove("FC_FIELD");
                } else if (outerSet.contains("FC_SQL")) {
                    String fc_sql = (String) innerMap.get("FC_SQL");

                    List<Map<String, Object>> list = beCarefulUseSelectService.query(fc_sql);
                    if (list != null && list.size() > 0) {
                        for (Map subMap : list) {
                            innerMap.put(String.valueOf(subMap.getOrDefault("id", "")), subMap.getOrDefault("name", ""));
                        }
                    }
                    innerMap.remove("FC_SQL");
                }
            }
            return map;
        } else {
            return new Gson().fromJson(runLabel, Map.class);
        }
    }

}
