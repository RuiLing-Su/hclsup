package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.SdkService.CmsService.CMS;
import com.hcbt.hcisup.SdkService.StreamService.SMS;
import com.hcbt.hcisup.common.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Created with IntelliJ IDEA
 *
 * @Author: Sout
 * @Date: 2024/11/23 上午11:09
 * @Description:
 */
@Slf4j
@RestController
@RequestMapping("/PlayBackVideo/")
@Tag(name = "录像控制接口", description = "处理录像的开始、停止、回放等操作")
public class PlayBackVideoController {

    @Resource
    private CMS cms;

    @Resource
    private SMS sms;

    /**
     * 开始录制
     * @param luserId
     * @return
     */
    @PostMapping("start/{luserId}")
    @Operation(summary = "开始录制")
    public AjaxResult startPlayBackVideo(@PathVariable("luserId") Integer luserId){
        boolean state = cms.startPlayBackVideo(luserId);
        if(state){
            return AjaxResult.success();
        }
        return AjaxResult.error();
    }

    /**
     * 停止录制
     * @param luserId
     * @return
     */
    @PostMapping("stop/{luserId}")
    @Operation(summary = "停止录制")
    public AjaxResult stopPlayBackVideo(@PathVariable("luserId") Integer luserId){
        boolean state = cms.stopPlayBackVideo(luserId);
        if(state){
            return AjaxResult.success();
        }
        return AjaxResult.error();
    }

    //查询设备是否在录像
    @GetMapping("videotapedStatus/{luserId}")
    @Operation(summary = "查询设备是否正在录像")
    public AjaxResult videotapedStatus(@PathVariable("luserId") Integer luserId){
        if(cms.videotapedStatus(luserId)){
            return AjaxResult.success();
        }
        return AjaxResult.error();
    }

    /**
     * 查找录像文件
     * @param luserId
     * @return
     */
    @PostMapping("findVideoFile/{luserId}")
    @Operation(summary = "查找录像文件")
    public AjaxResult findVideoFile(@PathVariable("luserId") Integer luserId,
                                    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime){
        List<Map<String,String>> fileInfoList = cms.findVideoFile(luserId,startTime,endTime);
        if(!fileInfoList.isEmpty()){
            return AjaxResult.success(fileInfoList);
        }
        return AjaxResult.error();
    }

    /**
     * 回放预览
     */
    @PostMapping("backLook/start/{luserId}")
    @Operation(summary = "开始录像回放")
    public AjaxResult startLookBackVideo(@PathVariable("luserId") Integer luserId,
                                         @RequestParam("filename") String filename,
                                         @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
                                         @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime){

        CompletableFuture<String> completableFuture = new CompletableFuture<>();

        sms.startPlayBackByFileName(luserId,filename,startTime,endTime,completableFuture);

        try {
            String result = completableFuture.get();
            if(Objects.equals(result, "true")){
                return AjaxResult.success();
            }
            return AjaxResult.error();
        } catch (InterruptedException | ExecutionException e) {
            log.error(e.getMessage());
            return AjaxResult.error();
        }
    }

    /**
     * 回放预览
     */
    @PostMapping("backLook/stop/{luserId}")
    public AjaxResult stopLookBackVideo(@PathVariable("luserId") Integer luserId){
        //获取SessionID
        Integer i = SMS.BackLuserIDandSessionMap.get(luserId);
        if (i==null){
            return AjaxResult.error();
        }
        boolean state = sms.stopPlayBackByFileName(luserId,i,SMS.BackSessionIDAndPreviewHandleMap.get(i));
        if(state){
            return AjaxResult.success();
        }
        return AjaxResult.error();
    }

}
