package com.yy.hosp.service.impl;
import java.util.Date;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yy.hosp.repository.HospitalRepository;
import com.yy.hosp.repository.ScheduleRepository;
import com.yy.hosp.service.DepartmentService;
import com.yy.hosp.service.HospitalService;
import com.yy.hosp.service.ScheduleService;
import com.yy.util.exception.RException;
import com.yy.yygh.model.hosp.BookingRule;
import com.yy.yygh.model.hosp.Department;
import com.yy.yygh.model.hosp.Hospital;
import com.yy.yygh.model.hosp.Schedule;
import com.yy.yygh.vo.hosp.BookingScheduleRuleVo;
import com.yy.yygh.vo.hosp.ScheduleOrderVo;
import com.yy.yygh.vo.order.OrderMqVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author ice
 * @date 2022/8/4 12:45
 */
@Service
public class ScheduleServiceImpl implements ScheduleService {
    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Resource
    private DepartmentService departmentService;

    @Resource
    private HospitalService hospitalService;


    @Override
    public boolean update(OrderMqVo orderMqVo) {
        try {
            Schedule schedule = null;
            if (orderMqVo.getAvailableNumber() != null) {
                schedule= scheduleRepository.findById(orderMqVo.getScheduleId()).get();
                schedule.setReservedNumber(orderMqVo.getReservedNumber());
                schedule.setAvailableNumber(orderMqVo.getAvailableNumber());
                scheduleRepository.save(schedule);
            }else {
                schedule = scheduleRepository.findByHosScheduleId(orderMqVo.getScheduleId());
                int availableNumber = schedule.getAvailableNumber() + 1;
                schedule.setAvailableNumber(availableNumber);
                scheduleRepository.save(schedule);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public ScheduleOrderVo getFeignScheduleById(String id) {


        Schedule schedule = getScheduleById(id);
        if (schedule == null) {
            throw new RException("????????????");
        }

        Hospital hospital = hospitalService.findByHosCode(schedule.getHoscode());
        if (hospital == null) {
            throw new RException("????????????");
        }
        BookingRule bookingRule = hospital.getBookingRule();
        ScheduleOrderVo scheduleOrderVo = new ScheduleOrderVo();
        scheduleOrderVo.setHoscode(schedule.getHoscode());
        scheduleOrderVo.setHosname((String) schedule.getParam().get("hosname"));
        scheduleOrderVo.setDepcode(schedule.getDepcode());
        scheduleOrderVo.setDepname((String) schedule.getParam().get("depname"));
        scheduleOrderVo.setHosScheduleId(schedule.getHosScheduleId());
        scheduleOrderVo.setTitle(schedule.getTitle());
        scheduleOrderVo.setReserveDate(schedule.getWorkDate());
        scheduleOrderVo.setAvailableNumber(schedule.getAvailableNumber());
        scheduleOrderVo.setReserveTime(schedule.getWorkTime());
        scheduleOrderVo.setAmount(schedule.getAmount());
        //?????????????????????????????????????????????-1????????????0???
        int quitDay = bookingRule.getQuitDay();
        DateTime dateTime = this.getDateTime(new org.joda.time.DateTime(schedule.getWorkDate()).plusDays(quitDay).toDate(), bookingRule.getQuitTime());
        scheduleOrderVo.setQuitTime(dateTime.toJdkDate());

        //??????????????????
        DateTime startTime = this.getDateTime(new Date(), bookingRule.getReleaseTime());
        scheduleOrderVo.setStartTime(startTime.toJdkDate());

        //??????????????????
        DateTime endTime = this.getDateTime(new org.joda.time.DateTime().plusDays(bookingRule.getCycle()).toDate(), bookingRule.getStopTime());
        scheduleOrderVo.setEndTime(endTime.toJdkDate());

        //????????????????????????
        DateTime stopTime = this.getDateTime(new Date(), bookingRule.getStopTime());
        scheduleOrderVo.setStopTime(stopTime.toJdkDate());

        return scheduleOrderVo;
    }

    // ????????????Id??????????????????
    @Override
    public Schedule getScheduleById(String id) {
        Schedule schedule = scheduleRepository.findById(id).get();
        return packageSchedule(schedule);
    }

    private Schedule packageSchedule(Schedule schedule) {
        String hoscode = schedule.getHoscode();
        Hospital byHoscode = hospitalRepository.findByHoscode(hoscode);
        String hosname = byHoscode.getHosname();
        String depcode = schedule.getDepcode();
        Department department = departmentService.getDepartment(hoscode, depcode);
        String depname = department.getDepname();
        schedule.getParam().put("hosname", hosname);
        schedule.getParam().put("dayOfWeek", dayOfWeek(schedule.getWorkDate()));
        schedule.getParam().put("depname", depname);
        return schedule;
    }

    // ???????????????????????????
    @Override
    public Map<String, Object> getBookingScheduleRule(Integer page, Integer limit, String hoscode, String depcode) {


        // ???????????????????????????
        Hospital hospital = hospitalService.findByHosCode(hoscode);
        if (hospital == null) {
            throw new RException("?????????");
        }
        BookingRule bookingRule = hospital.getBookingRule();
        //?????????????????????????????????
        IPage<Date> iPage = this.getListDate(page, limit, bookingRule);
        // ????????????????????????
        List<Date> dateList = iPage.getRecords();

        //??????????????????????????????????????????
        Criteria criteria = Criteria.where("hoscode").is(hoscode).and("depcode").is(depcode).and("workDate").in(dateList);
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(criteria), // ??????????????????
                Aggregation.group("workDate") // ??????workDate????????????
                        .first("workDate").as("workDate") // ??????????????????
                        .count().as("docCount")  // ??????????????????
                        .sum("reservedNumber").as("reservedNumber") // ??????????????????
                        .sum("availableNumber").as("availableNumber") // ?????????????????????
        );
        AggregationResults<BookingScheduleRuleVo> aggregate = mongoTemplate.
                aggregate(agg, Schedule.class, BookingScheduleRuleVo.class);
        // ?????????????????????
        List<BookingScheduleRuleVo> results = aggregate.getMappedResults();

        Map<Date, BookingScheduleRuleVo> ruleVoMap = results.stream().
                collect(Collectors.toMap(BookingScheduleRuleVo::getWorkDate, BookingScheduleRuleVo -> BookingScheduleRuleVo));

        List<BookingScheduleRuleVo> bookingScheduleRuleVos = new ArrayList<>();
        for (int i = 0; i < dateList.size(); i++) {
            Date date = dateList.get(i);
            BookingScheduleRuleVo bookingScheduleRuleVo = ruleVoMap.get(date);
            if (bookingScheduleRuleVo == null) {
                bookingScheduleRuleVo = new BookingScheduleRuleVo();
                bookingScheduleRuleVo.setDocCount(0);
                //?????????????????????  -1????????????
                bookingScheduleRuleVo.setAvailableNumber(-1);
                bookingScheduleRuleVos.add(bookingScheduleRuleVo);
            }
            bookingScheduleRuleVo.setWorkDate(date);
            bookingScheduleRuleVo.setWorkDateMd(date);
            bookingScheduleRuleVo.setDayOfWeek(dayOfWeek(date));
            //?????????????????????????????????????????????   ?????? 0????????? 1??????????????? -1????????????????????????
            if (i == dateList.size() - 1 && page == iPage.getPages()) {
                bookingScheduleRuleVo.setStatus(1);
            } else {
                bookingScheduleRuleVo.setStatus(0);
            }
            //??????????????????????????????????????? ????????????
            if (i == 0 && page == 1) {
                DateTime stopTime = this.getDateTime(new Date(), bookingRule.getStopTime());
                if (stopTime.isAfter(new Date())) {
                    //????????????
                    bookingScheduleRuleVo.setStatus(-1);
                }
            }
            bookingScheduleRuleVos.add(bookingScheduleRuleVo);
        }

        Map<String, Object> result = new HashMap<>();
        //???????????????????????????
        result.put("bookingScheduleList", bookingScheduleRuleVos);
        result.put("total", iPage.getTotal());
        //??????????????????
        Map<String, String> baseMap = new HashMap<>();
        //????????????
        baseMap.put("hosname", hospitalService.getByHoscode(hoscode).getHosname());
        //??????
        Department department = departmentService.getDepartment(hoscode, depcode);
        //???????????????
        baseMap.put("bigname", department.getBigname());
        //????????????
        baseMap.put("depname", department.getDepname());
        //???
        baseMap.put("workDateString", new DateTime().toString("yyyy???MM???"));
        //????????????
        baseMap.put("releaseTime", bookingRule.getReleaseTime());
        //????????????
        baseMap.put("stopTime", bookingRule.getStopTime());
        result.put("baseMap", baseMap);
        return result;
    }

    private IPage<Date> getListDate(Integer page, Integer limit, BookingRule bookingRule) {


        // ?????????????????????
        DateTime releaseTime = this.getDateTime(new Date(), bookingRule.getReleaseTime());
        // ????????????
        Integer cycle = bookingRule.getCycle();
        // ???????????????????????????????????????
        if (releaseTime.isAfter(new Date())) cycle = cycle + 1;

        // ???????????????????????????
        List<Date> dateList = new ArrayList<>();
        for (int i = 0; i < cycle; i++) {
            DateTime curDateTime = DateUtil.offsetDay(new DateTime(), i);
            String dateString = curDateTime.toString("yyyy-MM-dd");
            dateList.add(new DateTime(dateString).toJdkDate());
        }


        int begin = (page - 1) * limit;
        int end = begin + limit;


        if (end > dateList.size()) end = dateList.size();
        List<Date> pageDateList = new ArrayList<>();
        for (int i = begin; i < end; i++) {
            pageDateList.add(dateList.get(i));
        }

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Date> dataPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, limit, dateList.size());
        dataPage.setRecords(pageDateList);
        return dataPage;
    }

    /**
     * ???Date?????????yyyy-MM-dd HH:mm????????????DateTime
     */
    private DateTime getDateTime(Date date, String timeString) {
        String dateTimeString = new DateTime(date).toString("yyyy-MM-dd") + " " + timeString;
        DateTime dt = DateUtil.parse(dateTimeString);
        return dt;
    }

    @Override
    public void save(Map<String, Object> switchMap) {
        Schedule schedule = JSONObject.parseObject(JSONObject.toJSONString(switchMap), Schedule.class);
        //?????????????????? ??? ??????????????????
        Schedule scheduleExist = scheduleRepository.findByHoscodeAndHosScheduleId(schedule.getHoscode(), schedule.getHosScheduleId());
        //??????
        if (scheduleExist != null) {
            scheduleExist.setId(schedule.getId());
            scheduleExist.setUpdateTime(new Date());
            scheduleExist.setIsDeleted(0);
            scheduleExist.setStatus(1);
            scheduleRepository.save(scheduleExist);

        } else {
            schedule.setCreateTime(new Date());
            schedule.setUpdateTime(new Date());
            schedule.setIsDeleted(0);
            schedule.setStatus(1);
            scheduleRepository.save(schedule);
        }
    }

    @Override
    public Page<Schedule> getList(Map<String, Object> switchMap) {
        String hoscode = (String) switchMap.get("hoscode");
        String page = (String) switchMap.get("page");
        String limit = (String) switchMap.get("limit");

        Schedule schedule = new Schedule();
        schedule.setHoscode(hoscode);

        Example<Schedule> example = Example.of(schedule);
        Sort sort = Sort.by(Sort.Direction.ASC, "createTime");
        PageRequest pageRequest = PageRequest.of(Integer.parseInt(page), Integer.parseInt(limit), sort);
        return scheduleRepository.findAll(example, pageRequest);
    }

    @Override
    public boolean removeSchedule(Map<String, Object> switchMap) {
        String hoscode = (String) switchMap.get("hoscode");
        String hosScheduleId = (String) switchMap.get("hosScheduleId");
        Schedule schedule = scheduleRepository.findByHoscodeAndHosScheduleId(hoscode, hosScheduleId);
        if (schedule == null) {
            return false;
        }
        scheduleRepository.deleteById(schedule.getId());
        return true;
    }

    @Override
    public Map<String, Object> getRuleSchedule(Long page, Long limit, String hoscode, String depcode) {
        HashMap<String, Object> resultMap = new HashMap<>();
        Criteria criteria = Criteria.where("hoscode").is(hoscode).and("depcode").is(depcode);
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(criteria), // ??????????????????
                Aggregation.group("workDate") // ??????workDate????????????
                        .first("workDate").as("workDate") // ??????????????????
                        .count().as("docCount")  // ??????????????????
                        .sum("reservedNumber").as("reservedNumber") // ??????????????????
                        .sum("availableNumber").as("availableNumber"), // ?????????????????????
                Aggregation.sort(Sort.Direction.ASC, "workDate"), // ??????
                Aggregation.skip((page - 1) * limit), // ??????
                Aggregation.limit(limit)// ??????
        );
        AggregationResults<BookingScheduleRuleVo> aggregate = mongoTemplate.aggregate(agg, Schedule.class, BookingScheduleRuleVo.class);
        List<BookingScheduleRuleVo> resultList = aggregate.getMappedResults();

        resultList.forEach(result -> result.setDayOfWeek(dayOfWeek(result.getWorkDate())));


        resultMap.put("bookingScheduleRuleList", resultList);


        Criteria criteria1 = Criteria.where("hoscode").is(hoscode).and("depcode").is(depcode);
        Aggregation agg1 = Aggregation.newAggregation(
                Aggregation.match(criteria1), // ??????????????????
                Aggregation.group("workDate")); // ??????workDate????????????
        AggregationResults<BookingScheduleRuleVo> aggregate1 = mongoTemplate.aggregate(agg1, Schedule.class, BookingScheduleRuleVo.class);
        List<BookingScheduleRuleVo> totals = aggregate1.getMappedResults();
        // ????????????
        resultMap.put("total", totals.size());

        //??????????????????
        Hospital hospital = hospitalRepository.getByHoscode(hoscode);
        //??????????????????
        Map<String, String> baseMap = new HashMap<>();
        baseMap.put("hosname", hospital.getHosname());
        resultMap.put("baseMap", baseMap);
        return resultMap;
    }

    /**
     * ?????????????????? ?????????????????????????????????????????????????????????
     *
     * @param hoscode  ????????????
     * @param depcode  ????????????
     * @param workDate ????????????
     * @return ??????????????????
     */
    @Override
    public List<Schedule> getDetailSchedule(String hoscode, String depcode, String workDate) {
        Date dateTime = DateUtil.parse(workDate);
        List<Schedule> list = scheduleRepository.findAllByHoscodeAndDepcodeAndWorkDate(hoscode, depcode, dateTime);
        if (list.size() <= 0) {
            throw new RException("?????????????????????");
        }
        return list;
    }

    /**
     * ???????????????
     *
     * @param date ??????
     * @return ??????
     */
    public static String dayOfWeek(Date date) {
        int day = DateUtil.dayOfWeek(date);
        switch (day) {
            case 1:
                return "?????????";
            case 2:
                return "?????????";
            case 3:
                return "?????????";
            case 4:
                return "?????????";
            case 5:
                return "?????????";
            case 6:
                return "?????????";
            case 7:
                return "?????????";
            default:
                return "";
        }
    }


}
