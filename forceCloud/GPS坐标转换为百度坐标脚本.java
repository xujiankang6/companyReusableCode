package com.forceclouds.crm.local;

/*
 *@create by jiankang
 *@date 2020/6/3 time 14:59
 */


import cn.hutool.core.text.csv.CsvWriter;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TransferAddress {

    private static MessageFormat m = new MessageFormat("http://api.map.baidu.com/geoconv/v1/?coords={0},{1}&from=1&to=5&ak=SkSfzWhpQqHzO93TMA5pgoaYbq60LLHS");

    private static RestTemplate restTemplate = new RestTemplate();

    public static void main(String[] args) {
        List<ResultBean> datas = new ArrayList<>();
        ResultBean title = new ResultBean("external_id", "v_longitude", "v_latitude", "longitude", "latitude");
        datas.add(title);
        String sourcePath = "C:\\Users\\ForceClouds\\Desktop\\test_longite.csv";
        String goalPath = "C:\\Users\\ForceClouds\\Desktop\\jiangkang-alabama.csv";
        readCSV(datas, sourcePath);
        writeCSV(datas, goalPath);


    }

    public static void writeCSV(List<ResultBean> datas, String sourcePath) {
        CsvWriter csvWriter = new CsvWriter(new File(sourcePath));
        for (ResultBean data : datas) {
            csvWriter.write(new String[]{data.getExternal_id(), data.getV_longitude(), data.getV_latitude(), data.getLongitude(), data.getLatitude()});
        }
        csvWriter.close();
    }


    public static void readCSV(List<ResultBean> datas, String goalPath) {
        try (CSVReader csvReader = new CSVReaderBuilder(new BufferedReader(new InputStreamReader(new FileInputStream(new File(goalPath)), "utf-8"))).build()) {
            Iterator<String[]> iterator = csvReader.iterator();
            iterator.next();
            while (iterator.hasNext()) {
                String[] next = iterator.next();
                if (next[1] == null || "".equals(next[1]) || next[2] == null || "".equals(next[2])) {
                    ResultBean bean = new ResultBean(next[0], next[1], next[2], null, null);
                    datas.add(bean);
                } else {
                    String address = m.format(new Object[]{next[1], next[2]});
                    ResponseEntity<BaiDuReturnBean> result = restTemplate.getForEntity(address, BaiDuReturnBean.class);
                    if ("0".equals(result.getBody().getStatus())) {
                        Object x = result.getBody().getResult().get(0).get("x");
                        Object y = result.getBody().getResult().get(0).get("y");
                        ResultBean bean = new ResultBean(next[0], next[1], next[2], String.valueOf(x), String.valueOf(y));
                        datas.add(bean);
                    } else {
                        ResultBean bean = new ResultBean(next[0], next[1], next[2], null, null);
                        datas.add(bean);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    static class ResultBean {
        private String external_id;
        //康源经纬度
        private String v_longitude;
        private String v_latitude;
        //百度经纬度
        private String longitude;
        private String latitude;

        public ResultBean(String external_id, String v_longitude, String v_latitude, String longitude, String latitude) {
            this.external_id = external_id;
            this.v_longitude = v_longitude;
            this.v_latitude = v_latitude;
            this.longitude = longitude;
            this.latitude = latitude;
        }

        public String getExternal_id() {
            return external_id;
        }

        public String getV_longitude() {
            return v_longitude;
        }

        public String getV_latitude() {
            return v_latitude;
        }

        public String getLongitude() {
            return longitude;
        }

        public String getLatitude() {
            return latitude;
        }
    }

    static class BaiDuReturnBean {

        String status;
        List<Map<String, Object>> result;

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public List<Map<String, Object>> getResult() {
            return result;
        }

        public void setResult(List<Map<String, Object>> result) {
            this.result = result;
        }
    }
}
