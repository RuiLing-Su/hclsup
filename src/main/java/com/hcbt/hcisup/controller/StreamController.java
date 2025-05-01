package com.hcbt.hcisup.controller;

import com.hcbt.hcisup.SdkService.CmsService.CMS;
import com.hcbt.hcisup.SdkService.StreamService.SMS;
import com.hcbt.hcisup.common.AjaxResult;
import com.hcbt.hcisup.service.StreamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/detection")
@Tag(name = "推流接口")
public class StreamController {
    private final StreamService streamService;
    private final CMS cms;

    public StreamController(StreamService streamService, SMS sms, CMS cms) {
        this.streamService = streamService;
        this.cms = cms;
    }

    //根据DeviceID获取lUserID
    @RequestMapping(value ="getLUserId/{DeviceID}")
    @Operation(summary = "根据DeviceID获取lUserId")
    public AjaxResult getLUserId(@PathVariable("DeviceID") String DeviceID)
    {
        Map<String,Integer> data = cms.getLUserId(DeviceID);
        if(data != null){
            return AjaxResult.success(data);
        }
        return AjaxResult.error();
    }

    @PostMapping("/startHlsStream")
    @Operation(summary = "开始 HLS 推流")
    public ResponseEntity<?> startHlsStream(
            @RequestParam("channel") int channel,
            @RequestParam("hlsPath") String hlsPath) {
        try {
            streamService.startHlsStream(channel, hlsPath);
            return ResponseEntity.ok("HLS 流启动成功");
        } catch (Exception e) {
            log.error("启动 HLS 流失败: {}", e.getMessage());
            return ResponseEntity.status(500).body("启动 HLS 流失败: " + e.getMessage());
        }
    }

    @PostMapping("/stopStream")
    @Operation(summary = "停止推流")
    public ResponseEntity<?> stopStream(@RequestParam("channel") int channel) {
        try {
            streamService.stopStream(channel);
            return ResponseEntity.ok("流已停止");
        } catch (Exception e) {
            log.error("停止流失败: {}", e.getMessage());
            return ResponseEntity.status(500).body("停止流失败: " + e.getMessage());
        }
    }
}
