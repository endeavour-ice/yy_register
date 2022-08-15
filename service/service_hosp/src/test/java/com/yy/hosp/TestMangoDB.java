package com.yy.hosp;

import com.google.common.collect.Maps;
import com.yy.hosp.repository.HospitalRepository;
import com.yy.yygh.model.hosp.BookingRule;
import com.yy.yygh.model.hosp.Hospital;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

/**
 * @author ice
 * @date 2022/8/8 21:18
 */

@SpringBootTest
public class TestMangoDB {

    private final ExecutorService executor = new ThreadPoolExecutor(60, 1000, 10000, TimeUnit.MINUTES, new ArrayBlockingQueue<>(10000));
    @Autowired
    private HospitalRepository hospitalRepository;

    @Test
    public void doConcurrencyInsertUser() {
        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        int j = 0;
        for (int i = 0; i < 10; i++) {
            ArrayList<Hospital> hospitals = new ArrayList<>();
            do {
                j++;
                Hospital hospital = new Hospital();
                hospital.setHoscode("20000"+i+j);
                hospital.setHosname("洞口协和医院分部" + i+j);
                hospital.setHostype("1");
                hospital.setProvinceCode("110000");
                hospital.setCityCode("110100");
                hospital.setDistrictCode("110102");
                hospital.setAddress("大望路" + i+j);
                hospital.setLogoData("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");
                hospital.setIntro("" + i+j);
                hospital.setRoute("" + i+j);
                hospital.setStatus(1);
                hospital.setCreateTime(new Date());
                hospital.setUpdateTime(new Date());
                hospital.setIsDeleted(0);
                hospital.setParam(Maps.newHashMap());
                BookingRule bookingRule = new BookingRule();
                ArrayList<String> list = new ArrayList<>();
                list.add("西院区预约号取号地点：西院区门诊楼一层大厅挂号窗口取号");
                list.add("东院区预约号取号地点：东院区老门诊楼一层大厅挂号窗口或新门诊楼各楼层挂号/收费窗口取号");
                bookingRule.setCycle(10);
                bookingRule.setQuitDay(-1);
                bookingRule.setReleaseTime("8:44");
                bookingRule.setQuitTime("15:50");
                bookingRule.setStopTime("12:50");
                bookingRule.setRule(list);
                hospital.setBookingRule(bookingRule);
                hospitals.add(hospital);
            } while (j % 1500 != 0);
            CompletableFuture<Void> runAsync = CompletableFuture.runAsync(() -> {
                hospitalRepository.saveAll(hospitals);
                System.out.println("threadName :" + Thread.currentThread().getName());
            }, executor);
            futureList.add(runAsync);
        }
        CompletableFuture.allOf(futureList.toArray(new CompletableFuture[]{})).join();

    }
}
