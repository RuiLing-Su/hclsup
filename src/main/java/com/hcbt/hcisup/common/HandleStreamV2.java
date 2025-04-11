package com.hcbt.hcisup.common;

import com.hcbt.hcisup.utils.WebSocket;
import lombok.extern.slf4j.Slf4j;

/**
 * Improved implementation for handling H.265 video streams
 */
@Slf4j
public class HandleStreamV2 {

    private byte[] allEsBytes = null;
    private final WebSocket webSocket = new WebSocket();
    private final Integer luserId;
    private static final int MAX_BUFFER_SIZE = 1024 * 1024; // 1MB max buffer size to prevent memory issues

    public HandleStreamV2(Integer luserId) {
        this.luserId = luserId;
    }

    /**
     * Process incoming video data and send complete frames to WebSocket
     */
    public void startProcessing(final byte[] outputData) {
        if (outputData == null || outputData.length <= 0) {
            return;
        }

        try {
            // Check for RTP packet header (0x00 0x00 0x01 0xBA)
            if (isRtpPacketStart(outputData)) {
                // A complete frame is finished - send to client
                if (allEsBytes != null && allEsBytes.length > 0) {
                    webSocket.sendMessageForOne(allEsBytes, luserId);
                }
                allEsBytes = null;
            }

            // Video PES packet header (0x00 0x00 0x01 0xE0)
            if (isPesVideoPacket(outputData)) {
                // Extract the H.265 ES (Elementary Stream) data
                byte[] esBytes = extractEsData(outputData);

                if (esBytes != null && esBytes.length > 0) {
                    if (allEsBytes == null) {
                        allEsBytes = esBytes;
                    } else {
                        // Check buffer size to prevent memory issues
                        if (allEsBytes.length + esBytes.length > MAX_BUFFER_SIZE) {
                            log.warn("Buffer overflow prevented - frame too large ({}). Sending partial data.",
                                    allEsBytes.length + esBytes.length);
                            webSocket.sendMessageForOne(allEsBytes, luserId);
                            allEsBytes = esBytes; // Start a new frame
                        } else {
                            // Append the new ES data to the existing buffer
                            byte[] newEsBytes = new byte[allEsBytes.length + esBytes.length];
                            System.arraycopy(allEsBytes, 0, newEsBytes, 0, allEsBytes.length);
                            System.arraycopy(esBytes, 0, newEsBytes, allEsBytes.length, esBytes.length);
                            allEsBytes = newEsBytes;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error processing video data: {}", e.getMessage(), e);
        }
    }

    /**
     * Checks if the data starts with an RTP packet header
     */
    private boolean isRtpPacketStart(byte[] data) {
        return data.length >= 4 &&
                (data[0] & 0xff) == 0x00 &&
                (data[1] & 0xff) == 0x00 &&
                (data[2] & 0xff) == 0x01 &&
                (data[3] & 0xff) == 0xBA;
    }

    /**
     * Checks if the data starts with a video PES packet header
     */
    private boolean isPesVideoPacket(byte[] data) {
        return data.length >= 4 &&
                (data[0] & 0xff) == 0x00 &&
                (data[1] & 0xff) == 0x00 &&
                (data[2] & 0xff) == 0x01 &&
                (data[3] & 0xff) == 0xE0;
    }

    /**
     * Extracts the ES data from a PES packet
     */
    private byte[] extractEsData(byte[] pesPacket) {
        try {
            if (pesPacket.length < 9) {
                return null;
            }

            // The PES header length is at index 8
            int headerLength = pesPacket[8] & 0xff;
            int dataOffset = 9 + headerLength;

            // Check that we have enough data
            if (pesPacket.length <= dataOffset) {
                return null;
            }

            int dataLength = pesPacket.length - dataOffset;
            byte[] esData = new byte[dataLength];
            System.arraycopy(pesPacket, dataOffset, esData, 0, dataLength);
            return esData;
        } catch (Exception e) {
            log.error("Error extracting ES data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Stops processing and cleans up resources
     */
    public void stopProcessing() {
        // Clear any buffered data
        allEsBytes = null;
        log.info("Stream processing stopped for user ID: {}", luserId);
    }
}