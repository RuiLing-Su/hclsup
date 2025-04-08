package com.hcbt.hcisup.config;

import com.hcbt.hcisup.SdkService.CmsService.CMS;
import com.hcbt.hcisup.utils.lUserIdAndDeviceMap;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

//@Configuration      //1.主要用于标记配置类，兼备Component的效果。
//@EnableScheduling   // 2.开启定时任务
//@Slf4j
//public class StaticScheduleTask {
//
//    @Resource
//    private CMS cms;
//
//    //每小时抓拍一次
//    @Scheduled(cron = "0 0 * * * ?")
//    private void timingGetPic() {
//        List<String> lUserIdList = lUserIdAndDeviceMap.getKeys();
//        if(!lUserIdList.isEmpty()){
//            for (String lUserId : lUserIdList) {
//                cms.takePic(Integer.parseInt(lUserId));
//                log.info("【定时抓拍任务】 设备{}定时抓拍",lUserId);
//            }
//        }
//    }
//
//
//}