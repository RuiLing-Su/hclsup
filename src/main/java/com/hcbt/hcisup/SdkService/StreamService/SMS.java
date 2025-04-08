package com.hcbt.hcisup.SdkService.StreamService;


import com.hcbt.hcisup.SdkService.CmsService.CMS;
import com.hcbt.hcisup.SdkService.CmsService.HCISUPCMS;
import com.hcbt.hcisup.common.HandleStreamV2;
import com.hcbt.hcisup.common.PlayBackStream;
import com.hcbt.hcisup.common.osSelect;
import com.hcbt.hcisup.utils.lUserIdAndDeviceMap;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ws.schild.jave.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class SMS {

    public static HCISUPSMS hcISUPSMS = null;

    //存储sessionID和HandleStreamV2
    public static Map<Integer, HandleStreamV2> concurrentMap = new HashMap<>();
    public static Map<Integer, PlayBackStream> PlayBackconcurrentMap = new HashMap<>();
    //预览
    public static Map<Integer,Integer> PreviewHandSAndSessionIDandMap=new HashMap<>(); //存储lLinkHandle和SessionID
    public static Map<Integer,Integer> SessionIDAndPreviewHandleMap=new HashMap<>();    //存储SessionID和lLinkHandle

    public static Map<Integer,Integer> LuserIDandSessionMap=new HashMap<>(); //存储lUserID和SessionID
    static  FPREVIEW_NEWLINK_CB fPREVIEW_NEWLINK_CB;//预览监听回调函数实现
    static  FPREVIEW_DATA_CB_WIN fPREVIEW_DATA_CB_WIN;//预览回调函数实现
    HCISUPSMS.NET_EHOME_LISTEN_PREVIEW_CFG struPreviewListen = new HCISUPSMS.NET_EHOME_LISTEN_PREVIEW_CFG();
    //回放 同预览
    public static Map<Integer,Integer> BackLuserIDandSessionMap = new HashMap<>();
    public static Map<Integer,Integer> BackPreviewHandSAndSessionIDandMap=new HashMap<>();
    public static Map<Integer,Integer> BackSessionIDAndPreviewHandleMap=new HashMap<>();
    static PLAYBACK_NEWLINK_CB_FILE fPLAYBACK_NEWLINK_CB_FILE; //回放监听回调函数实现 - 文件存储
    static PLAYBACK_DATA_CB_FILE fPLAYBACK_DATA_CB_FILE;   //回放回调实现 - 文件存储
    HCISUPSMS.NET_EHOME_PLAYBACK_LISTEN_PARAM struPlayBackListen = new HCISUPSMS.NET_EHOME_PLAYBACK_LISTEN_PARAM();

    @Value("${ehome.in-ip}")
    private String ehomeInIp;

    @Value("${ehome.pu-ip}")
    private String ehomePuIp;

    @Value("${ehome.sms-preview-port}")
    private short ehomeSmsPreViewPort;

    @Value("${ehome.sms-back-port}")
    private short ehomeSmsBackPort;

    @Value("${ehome.playBack-videoPath}")
    private String fileVideoPath;


    /**
     * 实例化 hcISUPSMS 对象
     *
     * @return
     */
    private static boolean CreateSDKInstance() {
        if (hcISUPSMS == null) {
            synchronized (HCISUPSMS.class) {
                String strDllPath = "";
                try {
                    if (osSelect.isWindows())
                        //win系统加载库路径
                        strDllPath = System.getProperty("user.dir") + "\\lib\\HCISUPStream.dll";
                    else if (osSelect.isLinux())
                        //Linux系统加载库路径
                        strDllPath = System.getProperty("user.dir") + "/lib/libHCISUPStream.so";
                    hcISUPSMS = (HCISUPSMS) Native.loadLibrary(strDllPath, HCISUPSMS.class);
                } catch (Exception ex) {
                    log.error("loadLibrary: " + strDllPath + " Error: " + ex.getMessage());
                    return false;
                }
            }
        }
        return true;
    }

    @PostConstruct
    public void SMS_Init() {
        if (hcISUPSMS == null) {
            if (!CreateSDKInstance()) {
                log.error("加载SMS SDK 失败");
                return;
            }
        }
        //根据系统加载对应的库
        if (osSelect.isWindows()) {
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCrypto = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "\\lib\\libeay32.dll"; //Linux版本是libcrypto.so库文件的路径
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            if (!hcISUPSMS.NET_ESTREAM_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer())) {
                System.out.println("NET_ESTREAM_SetSDKInitCfg 0 failed, error:" + hcISUPSMS.NET_ESTREAM_GetLastError());
            }

            HCISUPCMS.BYTE_ARRAY ptrByteArraySsl = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "\\lib\\ssleay32.dll";    //Linux版本是libssl.so库文件的路径
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            if (!hcISUPSMS.NET_ESTREAM_SetSDKInitCfg(1, ptrByteArraySsl.getPointer())) {
                System.out.println("NET_ESTREAM_SetSDKInitCfg 1 failed, error:" + hcISUPSMS.NET_ESTREAM_GetLastError());
            }
            //流媒体初始化
            boolean b = hcISUPSMS.NET_ESTREAM_Init();
            if(b){
                log.info("SMS 流媒体初始化成功!");
                SMS_StartListen();
                startPlayBackListen();
            }else {
                log.error("SMS 流媒体初始化失败! 错误码:"+ hcISUPSMS.NET_ESTREAM_GetLastError());
            }
            //设置HCAapSDKCom组件库文件夹所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCom = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "\\lib\\HCAapSDKCom";      //只支持绝对路径，建议使用英文路径
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            if (!hcISUPSMS.NET_ESTREAM_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer())) {
                System.out.println("NET_ESTREAM_SetSDKLocalCfg 5 failed, error:" + hcISUPSMS.NET_ESTREAM_GetLastError());
            }
//            hcISUPSMS.NET_ESTREAM_SetLogToFile(3, "..\\EHomeSDKLog", false);
        } else if (osSelect.isLinux()) {
            //设置libcrypto.so所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCrypto = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "/lib/libcrypto.so"; //Linux版本是libcrypto.so库文件的路径
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            if (!hcISUPSMS.NET_ESTREAM_SetSDKInitCfg(0, ptrByteArrayCrypto.getPointer())) {
                System.out.println("NET_ESTREAM_SetSDKInitCfg 0 failed, error:" + hcISUPSMS.NET_ESTREAM_GetLastError());
            }
            //设置libssl.so所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArraySsl = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "/lib/libssl.so";    //Linux版本是libssl.so库文件的路径
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            if (!hcISUPSMS.NET_ESTREAM_SetSDKInitCfg(1, ptrByteArraySsl.getPointer())) {
                System.out.println("NET_ESTREAM_SetSDKInitCfg 1 failed, error:" + hcISUPSMS.NET_ESTREAM_GetLastError());
            }
            //流媒体初始化
            boolean b = hcISUPSMS.NET_ESTREAM_Init();
            if(b){
                log.info("SMS 流媒体初始化成功!");
                SMS_StartListen();
                startPlayBackListen();
            }else {
                log.error("SMS 流媒体初始化失败! 错误码:"+ hcISUPSMS.NET_ESTREAM_GetLastError());
            }
            //设置HCAapSDKCom组件库文件夹所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCom = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCom = System.getProperty("user.dir") + "/lib/HCAapSDKCom/";      //只支持绝对路径，建议使用英文路径
            System.arraycopy(strPathCom.getBytes(), 0, ptrByteArrayCom.byValue, 0, strPathCom.length());
            ptrByteArrayCom.write();
            if (!hcISUPSMS.NET_ESTREAM_SetSDKLocalCfg(5, ptrByteArrayCom.getPointer())) {
                System.out.println("NET_ESTREAM_SetSDKLocalCfg 5 failed, error:" + hcISUPSMS.NET_ESTREAM_GetLastError());
            }
//            hcISUPSMS.NET_ESTREAM_SetLogToFile(3, "./EHomeSDKLog", false);
        }

    }

    /**
     * 开启实时预览监听
     */
    public void SMS_StartListen() {

        //预览监听
        if (fPREVIEW_NEWLINK_CB == null) {
            fPREVIEW_NEWLINK_CB = new FPREVIEW_NEWLINK_CB();
        }
        struPreviewListen.struIPAdress.szIP=ehomeInIp.getBytes();
        struPreviewListen.struIPAdress.wPort = ehomeSmsPreViewPort; //流媒体服务器监听端口
        struPreviewListen.fnNewLinkCB = fPREVIEW_NEWLINK_CB; //预览连接请求回调函数
        struPreviewListen.pUser = null;
        struPreviewListen.byLinkMode = 0; //0- TCP方式，1- UDP方式
        struPreviewListen.write();

        int SmsHandle = hcISUPSMS.NET_ESTREAM_StartListenPreview(struPreviewListen);

        if (SmsHandle <0) {
            log.error("SMS流媒体服务监听失败, 错误码:"+hcISUPSMS.NET_ESTREAM_GetLastError());
            hcISUPSMS.NET_ESTREAM_Fini();
            return;
        }
        else {
            String StreamListenInfo = new String(struPreviewListen.struIPAdress.szIP).trim() + "_" + struPreviewListen.struIPAdress.wPort;
            log.info("SMS流媒体服务:" + StreamListenInfo + "监听成功!");
        }
    }

    /**
     * 设置回放监听
     */
    public void startPlayBackListen() {
        //回放监听
        if (fPLAYBACK_NEWLINK_CB_FILE == null) {
            fPLAYBACK_NEWLINK_CB_FILE = new PLAYBACK_NEWLINK_CB_FILE();
        }
        struPlayBackListen.struIPAdress.szIP = ehomeInIp.getBytes();//SMS服务器IP，配置成实际运行SMS服务器的内网的IP和端口
        struPlayBackListen.struIPAdress.wPort = ehomeSmsBackPort; //SMS服务器监听端口
        struPlayBackListen.pUser = null;
        struPlayBackListen.fnNewLinkCB = fPLAYBACK_NEWLINK_CB_FILE;
        struPlayBackListen.byLinkMode = 0; //0- TCP方式，1- UDP方式;

        int m_lPlayBackListenHandle = hcISUPSMS.NET_ESTREAM_StartListenPlayBack(struPlayBackListen); //SMS的回放监听
        if (m_lPlayBackListenHandle < 0) {
            log.error("NET_ESTREAM_StartListenPlayBack failed, error code:" + hcISUPSMS.NET_ESTREAM_GetLastError());
            hcISUPSMS.NET_ESTREAM_Fini();
            return;
        } else {
            String BackStreamListenInfo = new String(struPlayBackListen.struIPAdress.szIP).trim() + "_" + struPlayBackListen.struIPAdress.wPort;
            log.info("回放流媒体服务：" + BackStreamListenInfo + ",NET_ESTREAM_StartListenPlayBack succeed");
        }

    }

    /**
     * 回放监听回调
     */
    public class PLAYBACK_NEWLINK_CB_FILE implements HCISUPSMS.PLAYBACK_NEWLINK_CB{

        @Override
        public boolean invoke(int lPlayBackLinkHandle, HCISUPSMS.NET_EHOME_PLAYBACK_NEWLINK_CB_INFO pNewLinkCBMsg, Pointer pUserData) {

            pNewLinkCBMsg.read();
            HCISUPSMS.NET_EHOME_PLAYBACK_DATA_CB_PARAM struDataCB = new HCISUPSMS.NET_EHOME_PLAYBACK_DATA_CB_PARAM();
            //双向存储session和lLinkHandle
            BackPreviewHandSAndSessionIDandMap.put(lPlayBackLinkHandle,pNewLinkCBMsg.lSessionID);
            BackSessionIDAndPreviewHandleMap.put(pNewLinkCBMsg.lSessionID,lPlayBackLinkHandle);

            if(fPLAYBACK_DATA_CB_FILE == null){
                fPLAYBACK_DATA_CB_FILE = new PLAYBACK_DATA_CB_FILE();
            }

            struDataCB.fnPlayBackDataCB = fPLAYBACK_DATA_CB_FILE;
            struDataCB.byStreamFormat = 0;
            struDataCB.write();

            //注册回调接受码流
            if(!hcISUPSMS.NET_ESTREAM_SetPlayBackDataCB(lPlayBackLinkHandle,struDataCB)){
                log.error("NET_ESTREAM_SetPlayBackDataCB failed, error:" + hcISUPSMS.NET_ESTREAM_GetLastError());
                return false;
            }

            return true;
        }
    }

    /**
     * 回放数据回调
     */
    public class PLAYBACK_DATA_CB_FILE implements HCISUPSMS.PLAYBACK_DATA_CB {

        byte[] bytes1 = new byte[1024*1024];

        //回放流回调函数
        @Override
        public boolean invoke(int iPlayBackLinkHandle, HCISUPSMS.NET_EHOME_PLAYBACK_DATA_CB_INFO pDataCBInfo, Pointer pUserData) {

            //取出SessionID
            Integer sessionID = BackPreviewHandSAndSessionIDandMap.get(iPlayBackLinkHandle);
            //取出playBackStream对象
            PlayBackStream playBackStream = PlayBackconcurrentMap.get(sessionID);
            if(pDataCBInfo.pData != null && playBackStream != null) {

                String date = playBackStream.getStartTime().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                String filePath = fileVideoPath+playBackStream.getDeviceId()+"/"+date+"/"+playBackStream.getFileName()+".mp4";
                //仅需取码流数据，获取码流数据保存成文件
                try {
                    //先判断该设备文件夹是否存在
                    File file = new File(fileVideoPath+playBackStream.getDeviceId()+"/"+date);
                    if (!file.exists())
                    {
                        file.mkdirs();
                    }
                    FileOutputStream playbackFileOutput = new FileOutputStream(filePath, true);
                    long offset = 0;
                    ByteBuffer buffers = pDataCBInfo.pData.getByteBuffer(offset, pDataCBInfo.dwDataLen);
                    byte[] bytes = new byte[pDataCBInfo.dwDataLen];
                    buffers.rewind();
                    buffers.get(bytes);
                    playbackFileOutput.write(bytes);
                    playbackFileOutput.close();
                } catch (IOException e) {
                    // TODO 这里需要自行处理文件读取异常逻辑
                    e.printStackTrace();
                }
            }else{
                if (playBackStream != null) {
                    String date = playBackStream.getStartTime().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                    String filePath = fileVideoPath+playBackStream.getDeviceId()+"/"+date+"/"+playBackStream.getFileName()+".mp4";
                    String filePath2 = fileVideoPath+playBackStream.getDeviceId()+"/"+date+"/"+playBackStream.getFileName()+"_0.mp4";
                    try {
                        MP4Covert(filePath,filePath2);
                        playBackStream.getCompletableFuture().complete("true");
                        //推流结束移除playBackStream
                        PlayBackconcurrentMap.remove(sessionID);
                    } catch (EncoderException e) {
                        log.error(e.getMessage());
                    }
                }
            }
            return true;
        }

    }

    /**
     * 实时预览数据回调
     */
    public class FPREVIEW_NEWLINK_CB implements HCISUPSMS.PREVIEW_NEWLINK_CB {
        @Override
        public boolean invoke(int lLinkHandle, HCISUPSMS.NET_EHOME_NEWLINK_CB_MSG pNewLinkCBMsg, Pointer pUserData) {

            HCISUPSMS.NET_EHOME_PREVIEW_DATA_CB_PARAM struDataCB = new HCISUPSMS.NET_EHOME_PREVIEW_DATA_CB_PARAM();

//            log.info("参数解析,lLinkHandle:"+lLinkHandle+"设备会话ID"+pNewLinkCBMsg.iSessionID);

            //双向存储session和lLinkHandle
            PreviewHandSAndSessionIDandMap.put(lLinkHandle,pNewLinkCBMsg.iSessionID);
            SessionIDAndPreviewHandleMap.put(pNewLinkCBMsg.iSessionID,lLinkHandle);

            if (fPREVIEW_DATA_CB_WIN == null) {
                fPREVIEW_DATA_CB_WIN = new FPREVIEW_DATA_CB_WIN();
            }

            struDataCB.fnPreviewDataCB = fPREVIEW_DATA_CB_WIN;
            //注册回调函数以接收实时码流
            if (!hcISUPSMS.NET_ESTREAM_SetPreviewDataCB(lLinkHandle, struDataCB)) {
                log.error("NET_ESTREAM_SetPreviewDataCB failed err:：" + hcISUPSMS.NET_ESTREAM_GetLastError());
                return false;
            }
            return true;
        }
    }

    /**
     * 预览数据的回调函数
     */
    public class FPREVIEW_DATA_CB_WIN implements HCISUPSMS.PREVIEW_DATA_CB {
        //实时流回调函数/
        @Override
        public void invoke(int iPreviewHandle, HCISUPSMS.NET_EHOME_PREVIEW_CB_MSG pPreviewCBMsg, Pointer pUserData) throws IOException {

            switch (pPreviewCBMsg.byDataType) {
                case HCNetSDK.NET_DVR_SYSHEAD: //系统头
                {
                }
                case HCNetSDK.NET_DVR_STREAMDATA:   //码流数据
                {

                    byte[] dataStream = pPreviewCBMsg.pRecvdata.getByteArray(0, pPreviewCBMsg.dwDataLen);

                    if(dataStream!=null){
                        Integer sessionID = PreviewHandSAndSessionIDandMap.get(iPreviewHandle);
                        HandleStreamV2 handleStreamV2 = concurrentMap.get(sessionID);
                        if (handleStreamV2 != null) {
                            handleStreamV2.startProcessing(dataStream);
                        }

                    }
                }
            }
        }
    }

    /**
     * 开启预览，
     *
     * @param luserID 预览通道号
     */
    public void RealPlay(int luserID,int channel, CompletableFuture<String> completableFutureOne) {

        //判断是否正在推流
        List<Integer> luserIdList = new ArrayList<>(LuserIDandSessionMap.keySet());
        if (luserIdList.contains(luserID)) {
            //正在推流
            log.error("禁止重复推流!");
            completableFutureOne.complete("false");
            return;
        }

        HCISUPCMS.NET_EHOME_PREVIEWINFO_IN struPreviewIn = new HCISUPCMS.NET_EHOME_PREVIEWINFO_IN();
        struPreviewIn.iChannel = channel; //通道号
        struPreviewIn.dwLinkMode = 0; //0- TCP方式，1- UDP方式
        struPreviewIn.dwStreamType = 0; //码流类型：0- 主码流，1- 子码流, 2- 第三码流
        struPreviewIn.struStreamSever.szIP =ehomePuIp.getBytes(); ;//流媒体服务器IP地址,公网地址
        struPreviewIn.struStreamSever.wPort = ehomeSmsPreViewPort; //流媒体服务器端口，需要跟服务器启动监听端口一致
        struPreviewIn.write();
        //预览请求
        HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT struPreviewOut = new HCISUPCMS.NET_EHOME_PREVIEWINFO_OUT();
        //请求开始预览
        if (!CMS.hcISUPCMS.NET_ECMS_StartGetRealStream(luserID, struPreviewIn, struPreviewOut)) {
            log.error("请求开始预览失败,错误码:"+CMS.hcISUPCMS.NET_ECMS_GetLastError());
            completableFutureOne.complete("false");
            return;
        } else {
            struPreviewOut.read();
//            log.info("请求预览成功, sessionID:" + struPreviewOut.lSessionID);
//            sessionID = struPreviewOut.lSessionID;
        }
        HCISUPCMS.NET_EHOME_PUSHSTREAM_IN struPushInfoIn = new HCISUPCMS.NET_EHOME_PUSHSTREAM_IN();
        struPushInfoIn.read();
        struPushInfoIn.dwSize = struPushInfoIn.size();
        struPushInfoIn.lSessionID = struPreviewOut.lSessionID;
        struPushInfoIn.write();
        HCISUPCMS.NET_EHOME_PUSHSTREAM_OUT struPushInfoOut = new HCISUPCMS.NET_EHOME_PUSHSTREAM_OUT();
        struPushInfoOut.read();
        struPushInfoOut.dwSize = struPushInfoOut.size();
        struPushInfoOut.write();
        //中心管理服务器（CMS）向设备发送请求，设备开始传输预览实时码流
        if (!CMS.hcISUPCMS.NET_ECMS_StartPushRealStream(luserID, struPushInfoIn, struPushInfoOut)) {
            log.error("CMS向设备发送请求预览实时码流失败, error code:" + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            completableFutureOne.complete("false");
        } else {
            log.info("CMS向设备发送请求预览实时码流成功, sessionID:" + struPushInfoIn.lSessionID);

            completableFutureOne.complete("true");//运行到这说明推流成功了
            LuserIDandSessionMap.computeIfAbsent(luserID, k -> struPushInfoIn.lSessionID);

            concurrentMap.computeIfAbsent(struPushInfoIn.lSessionID, k -> new HandleStreamV2(luserID));
        }

    }

    /**
     * 停止预览,Stream服务停止实时流转发，CMS向设备发送停止预览请求
     */
    public void StopRealPlay(int luserID,int sessionID,int lPreviewHandle) {

        //判断是否正在推流
        List<Integer> luserIDLiat = new ArrayList<>(LuserIDandSessionMap.keySet());
        if (!luserIDLiat.contains(luserID)) {
            log.error("禁止重复停止推流!");
            return;
        }
        //停止某一通道转发预览实时码流
        if (!hcISUPSMS.NET_ESTREAM_StopPreview(lPreviewHandle)) {
            log.error("停止转发预览实时码流失败, 错误码: " + hcISUPSMS.NET_ESTREAM_GetLastError());
            return;
        }
        //请求停止预览
        if (!CMS.hcISUPCMS.NET_ECMS_StopGetRealStream(luserID, sessionID)) {
            log.error("请求停止预览失败,错误码: " + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            return;
        }
        //停止线程
//        HandleStreamV2 handleStreamV2 = concurrentMap.get(sessionID);
//        handleStreamV2.stopProcessing();

        concurrentMap.remove(sessionID);
        PreviewHandSAndSessionIDandMap.remove(lPreviewHandle);
        LuserIDandSessionMap.remove(luserID);
        SessionIDAndPreviewHandleMap.remove(sessionID);

        if(!concurrentMap.containsKey(sessionID)&&!PreviewHandSAndSessionIDandMap.containsKey(lPreviewHandle)&&!LuserIDandSessionMap.containsKey(luserID)&&!SessionIDAndPreviewHandleMap.containsKey(sessionID)){
            log.info("会话"+sessionID+"相关资源已被清空");
        }

    }

    /**
     * 开启回放（按文件名）
     */
    public void startPlayBackByFileName(int lUserId, String fileName, LocalDateTime startTime , LocalDateTime endTime , CompletableFuture<String> completableFuture) {

        //先判断文件是否存在
        String deviceId = lUserIdAndDeviceMap.get(String.valueOf(lUserId));

        String date = startTime.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String filePath = fileVideoPath+deviceId+"/"+date+"/"+fileName+"_0.mp4";

        File file = new File(filePath);
        if (file.exists())
        {
            completableFuture.complete("true");
            return;
        }

        HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN m_struPlayBackInfoIn = new HCISUPCMS.NET_EHOME_PLAYBACK_INFO_IN();
        m_struPlayBackInfoIn.read();
        m_struPlayBackInfoIn.dwSize = m_struPlayBackInfoIn.size();
        m_struPlayBackInfoIn.dwChannel = 1; //通道号
        m_struPlayBackInfoIn.byPlayBackMode = 1;//0- 按文件名回放，1- 按时间回放
        m_struPlayBackInfoIn.unionPlayBackMode.setType(HCISUPCMS.NET_EHOME_PLAYBACKBYTIME.class);
        // FIXME 这里的时间参数需要根据实际设备上存在的时间段进行设置, 否则可能可能提示：3505 - 该时间段内无录像。
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStartTime.wYear = (short) startTime.getYear();
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStartTime.byMonth = (byte) startTime.getMonthValue();
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStartTime.byDay = (byte) startTime.getDayOfMonth();
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStartTime.byHour = (byte) startTime.getHour();
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStartTime.byMinute = (byte) startTime.getMinute();
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStartTime.bySecond = (byte) startTime.getSecond();

        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStopTime.wYear = (short) endTime.getYear();
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStopTime.byMonth = (byte) endTime.getMonthValue();
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStopTime.byDay = (byte) endTime.getDayOfMonth();
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStopTime.byHour = (byte) endTime.getHour();
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStopTime.byMinute = (byte) endTime.getMinute();
        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyTime.struStopTime.bySecond = (byte) endTime.getSecond();
//        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyName.szFileName = fileName.getBytes();
//        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyName.dwFileSpan = 0;
//        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyName.dwFileOffset = 0;
//        m_struPlayBackInfoIn.unionPlayBackMode.struPlayBackbyName.dwSeekType = 1;


        m_struPlayBackInfoIn.struStreamSever.szIP = ehomePuIp.getBytes();//配置SMS服务器IP，配置成实际运行SMS服务器的公网的IP和端口
        m_struPlayBackInfoIn.struStreamSever.wPort = ehomeSmsBackPort; //SMS服务器监听端口

        m_struPlayBackInfoIn.write();
        HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT m_struPlayBackInfoOut = new HCISUPCMS.NET_EHOME_PLAYBACK_INFO_OUT();
        m_struPlayBackInfoOut.write();


        //CMS向设备请求回放，并给设备配置SMS服务器IP和端口
        if (!CMS.hcISUPCMS.NET_ECMS_StartPlayBack(lUserId, m_struPlayBackInfoIn, m_struPlayBackInfoOut)) {
            log.error("NET_ECMS_StartPlayBack failed, error code:" + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            completableFuture.complete("false");
            return;
        } else {
            m_struPlayBackInfoOut.read();
            log.info("NET_ECMS_StartPlayBack succeed, lSessionID:" + m_struPlayBackInfoOut.lSessionID);
            //加入map对象
            PlayBackconcurrentMap.computeIfAbsent(m_struPlayBackInfoOut.lSessionID, k -> new PlayBackStream(deviceId,fileName,startTime,completableFuture));
        }

        HCISUPCMS.NET_EHOME_PUSHPLAYBACK_IN m_struPushPlayBackIn = new HCISUPCMS.NET_EHOME_PUSHPLAYBACK_IN();
        m_struPushPlayBackIn.read();
        m_struPushPlayBackIn.dwSize = m_struPushPlayBackIn.size();
        m_struPushPlayBackIn.lSessionID = m_struPlayBackInfoOut.lSessionID; //获取回放sessionID
        m_struPushPlayBackIn.write();

        BackLuserIDandSessionMap.put(lUserId,m_struPushPlayBackIn.lSessionID);

        HCISUPCMS.NET_EHOME_PUSHPLAYBACK_OUT m_struPushPlayBackOut = new HCISUPCMS.NET_EHOME_PUSHPLAYBACK_OUT();
        m_struPushPlayBackOut.read();
        m_struPushPlayBackOut.dwSize = m_struPushPlayBackOut.size();
        m_struPushPlayBackOut.write();

        //请求设备回放数据
        if (!CMS.hcISUPCMS.NET_ECMS_StartPushPlayBack(lUserId, m_struPushPlayBackIn, m_struPushPlayBackOut)) {
            log.error("NET_ECMS_StartPushPlayBack failed, error code:" + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            completableFuture.complete("false");
            return;
        } else {
            log.info("NET_ECMS_StartPushPlayBack succeed, sessionID:" + m_struPushPlayBackIn.lSessionID + ",lUserID:" + lUserId);
        }
    }


    /**
     * 停止回放
     */
    public boolean stopPlayBackByFileName(int lUserID,int sessionID,int lPreviewHandle) {
        if (!CMS.hcISUPCMS.NET_ECMS_StopPlayBack(lUserID, sessionID) ) {
            log.error("NET_ECMS_StopPlayBack failed,err = " + CMS.hcISUPCMS.NET_ECMS_GetLastError());
            return false;
        }
        log.info("CMS发送回放停止请求");
        if (!hcISUPSMS.NET_ESTREAM_StopPlayBack(lPreviewHandle) ){
            log.error("NET_ESTREAM_StopPlayBack failed,err = " + hcISUPSMS.NET_ESTREAM_GetLastError());
            return false;
        }
        PlayBackconcurrentMap.remove(sessionID);
        BackPreviewHandSAndSessionIDandMap.remove(lPreviewHandle);
        BackLuserIDandSessionMap.remove(lUserID);
        BackSessionIDAndPreviewHandleMap.remove(sessionID);

        if(!PlayBackconcurrentMap.containsKey(sessionID)&&!BackPreviewHandSAndSessionIDandMap.containsKey(lPreviewHandle)&&!BackLuserIDandSessionMap.containsKey(lUserID)&&!BackSessionIDAndPreviewHandleMap.containsKey(sessionID)){
            log.info("回放会话"+sessionID+"相关资源已被清空");
        }
        return true;
    }


    public static void MP4Covert(String filepath,String filepath1) throws EncoderException {
        //源视频位置
        File file = new File(filepath);
        //目标视频位置
        File file1 = new File(filepath1);
        // 创建转码器
        AudioAttributes audio = new AudioAttributes();
        //指定编码
        audio.setCodec("aac");
        audio.setBitRate(128000);
        //通道
        audio.setChannels(2);
        audio.setSamplingRate(44100);
        VideoAttributes video = new VideoAttributes();
        //设置编解码器
        video.setCodec("h264");
        video.setX264Profile(VideoAttributes.X264_PROFILE.BASELINE);
        video.setBitRate(1024*1024 * 4);
        //设置帧率
        video.setFrameRate(25);
        //设置大小
        //video.setSize(new VideoSize(2560, 1440));
        EncodingAttributes attrs = new EncodingAttributes();
        //格式
        attrs.setFormat("mp4");
        attrs.setAudioAttributes(audio);
        attrs.setVideoAttributes(video);
        // 进行转码
        Encoder encoder = new Encoder();
        encoder.encode(new MultimediaObject(file), file1, attrs);
        //删除源文件
        file.delete();
    }
}
