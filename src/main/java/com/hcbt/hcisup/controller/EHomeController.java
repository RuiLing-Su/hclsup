package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.SdkService.CmsService.CMS;
import com.hcbt.hcisup.SdkService.StreamService.SMS;
import com.hcbt.hcisup.common.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/")
@Tag(name = "EHome接口", description = "摄像头推流、控制等接口")
public class EHomeController {


    @Resource
    private SMS sms;
    @Resource
    private CMS cms;
    /**
     * 根据摄像头编号开始推流
     */
    @PostMapping("startPushStream")
    @Operation(summary = "根据摄像头编号开始推流")
    public AjaxResult startPushStream(@RequestParam("luserId") Integer luserId,
                                      @RequestParam("channel") Integer channel){

        CompletableFuture<String> completableFuture = new CompletableFuture<>();

        try {
            sms.RealPlay(luserId,channel,completableFuture);
        }catch (Exception e){
            return AjaxResult.error();
        }

        try {
            String result = completableFuture.get();
            if(Objects.equals(result, "true")){
                return AjaxResult.success();
            }
            return AjaxResult.error();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 根据摄像头编号停止推流
     */
    @PostMapping(value ="stopPushStream")
    @Operation(summary = "根据摄像头编号停止推流")
    public AjaxResult stopPreviewDevice(@RequestParam("luserId") Integer luserId,@RequestParam("channel") Integer channel)
    {
        Integer i = SMS.LuserIDandSessionMap.get(channel);
        if (i==null){
            return AjaxResult.error();
        }
        try {
            sms.StopRealPlay(luserId,channel,i,SMS.SessionIDAndPreviewHandleMap.get(i));
        }catch (Exception e){
            return AjaxResult.error();
        }
        return AjaxResult.success();
    }

    //根据DeviceID获取lUserID
    @GetMapping(value ="getLUserId/{DeviceID}")
    @Operation(summary = "根据DeviceID获取lUserId")
    public AjaxResult getLUserId(@PathVariable("DeviceID") String DeviceID)
    {
        Map<String,Integer> data = cms.getLUserId(DeviceID);
        if(data != null){
            return AjaxResult.success(data);
        }
        return AjaxResult.error();
    }

    //云台控制
    @PostMapping(value ="setPTZControlOther/{luserId}")
    @Operation(summary = "云台控制")
    public AjaxResult setPTZControlOther(@PathVariable("luserId") Integer luserId,
                                         @RequestParam("dwPTZCommand") Integer dwPTZCommand,
                                         @RequestParam(value = "speed", defaultValue = "5") Integer speed)
    {
        boolean state = cms.setPTZControlOther(luserId,dwPTZCommand,speed);
        if(state){
            return AjaxResult.success();
        }
        return AjaxResult.error();
    }

    //云台控制(停止)
    @PostMapping(value ="stopPTZControlOther/{luserId}")
    @Operation(summary = "云台控制（停止）")
    public AjaxResult stopPTZControlOther(@PathVariable("luserId") Integer luserId)
    {
        boolean state = cms.stopPTZControlOther(luserId);
        if(state){
            return AjaxResult.success();
        }
        return AjaxResult.error();
    }

    //抓拍
    @PostMapping("/getPic/{luserId}")
    @Operation(summary = "抓拍")
    public AjaxResult getPic(@PathVariable("luserId") Integer luserId){
        boolean state = cms.takePic(luserId);
        if(state){
            return AjaxResult.success();
        }
        return AjaxResult.error();
    }


}
