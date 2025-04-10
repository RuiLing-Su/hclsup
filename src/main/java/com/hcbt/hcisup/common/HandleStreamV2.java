package com.hcbt.hcisup.common;

import com.hcbt.hcisup.utils.WebSocket;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 跳过了解析帧，降低cpu利用率
 */
@Slf4j
public class HandleStreamV2 {

    private byte[] allEsBytes = null;
    private final WebSocket webSocket = new WebSocket();
    private final Integer luserId;

    public HandleStreamV2(Integer luserId) {
        this.luserId = luserId;
    }

    public void startProcessing(final byte[] outputData){
            if (outputData.length <= 0) {
                return;
            }
            if ((outputData[0] & 0xff) == 0x00//
                    && (outputData[1] & 0xff) == 0x00//
                    && (outputData[2] & 0xff) == 0x01//
                    && (outputData[3] & 0xff) == 0xBA) {// RTP包开头
                // 一个完整的帧解析完成后将解析的数据放入BlockingQueue,websocket获取后发给前端
                if (allEsBytes != null && allEsBytes.length > 0) {
                    //MyBlockingQueue.bq.put(allEsBytes);
                    webSocket.sendMessageForOne(allEsBytes,luserId);
                }
                allEsBytes = null;
            }

            // 是00 00 01 eo开头的就是视频的pes包
            if ((outputData[0] & 0xff) == 0x00//
                    && (outputData[1] & 0xff) == 0x00//
                    && (outputData[2] & 0xff) == 0x01//
                    && (outputData[3] & 0xff) == 0xE0) {//
                // 去掉包头后的起始位置
                int from = 9 + outputData[8] & 0xff;
                int len = outputData.length - 9 - (outputData[8] & 0xff);
                // 获取es裸流
                byte[] esBytes = new byte[len];
                System.arraycopy(outputData, from, esBytes, 0, len);

                if (allEsBytes == null) {
                    allEsBytes = esBytes;
                } else {
                    byte[] newEsBytes = new byte[allEsBytes.length + esBytes.length];
                    System.arraycopy(allEsBytes, 0, newEsBytes, 0, allEsBytes.length);
                    System.arraycopy(esBytes, 0, newEsBytes, allEsBytes.length, esBytes.length);
                    allEsBytes = newEsBytes;
                }
            }
    }
}
