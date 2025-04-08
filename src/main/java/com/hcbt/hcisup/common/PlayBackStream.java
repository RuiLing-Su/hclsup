package com.hcbt.hcisup.common;

import com.hcbt.hcisup.SdkService.StreamService.HCISUPSMS;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * 回放
 */
@Getter
@Slf4j
public class PlayBackStream{

    private final String deviceId;
    private final String fileName;
    private final CompletableFuture<String> completableFuture;
    private final LocalDateTime startTime;

    public PlayBackStream(String deviceId, String fileName, LocalDateTime startTime, CompletableFuture<String> completableFuture) {
        this.deviceId = deviceId;
        this.fileName = fileName;
        this.completableFuture = completableFuture;
        this.startTime = startTime;
    }
}
