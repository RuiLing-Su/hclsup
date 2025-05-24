package com.hcbt.hcisup.SdkService.SsService;

import com.hcbt.hcisup.SdkService.CmsService.HCISUPCMS;
import com.hcbt.hcisup.common.osSelect;
import com.hcbt.hcisup.utils.HttpUtil;
import com.hcbt.hcisup.utils.lUserIdAndDeviceMap;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Created with IntelliJ IDEA
 *
 * @Author: Sout
 * @Date: 2024/11/16 上午11:09
 * @Description:
 */
@Slf4j
@Component
public class SS {

    @Value("${ehome.in-ip}")
    private String ehomeInIp;

    @Value("${ehome.pu-ip}")
    private String ehomePuIp;

    @Value("${ehome.ss-port}")
    private short ss_port;

    @Value("${ehome.ss-filepath}")
    private String filepath;

    public static HCISUPSS hCEhomeSS = null;

    static PSS_Message_Callback pSS_Message_Callback;// 信息回调函数(上报)
    static PSS_Storage_Callback pSS_Storage_Callback;// 文件保存回调函数(下载)
    static HCISUPSS.NET_EHOME_SS_LISTEN_PARAM pSSListenParam = new HCISUPSS.NET_EHOME_SS_LISTEN_PARAM();
    public static int SsHandle = -1; //存储服务监听句柄
    int client = -1;
    /**
     * 根据不同操作系统选择不同的库文件和库路径
     *
     * @return
     */
    private static boolean CreateSDKInstance() {
        if (hCEhomeSS == null) {
            synchronized (HCISUPSS.class) {
                String strDllPath = "";
                try {
                    //System.setProperty("jna.debug_load", "true");
                    if (osSelect.isWindows())
                        //win系统加载库路径(路径不要带中文)
                        strDllPath = System.getProperty("user.dir") + "\\lib\\HCISUPSS.dll";
                    else if (osSelect.isLinux())
                        //Linux系统加载库路径(路径不要带中文)
                        strDllPath = System.getProperty("user.dir") + "/lib/libHCISUPSS.so";
                    hCEhomeSS = (HCISUPSS) Native.loadLibrary(strDllPath, HCISUPSS.class);
                } catch (Exception ex) {
                    System.out.println("loadLibrary: " + strDllPath + " Error: " + ex.getMessage());
                    return false;
                }
            }
        }
        return true;
    }


    /**
     * 初始化ss中心
     * @throws IOException
     */
    @PostConstruct
    public void eSS_Init() throws UnsupportedEncodingException {
        if (hCEhomeSS == null) {
            if (!CreateSDKInstance()) {
                log.error("Load SS SDK fail");
                return;
            }
        }
        if (osSelect.isWindows()) {
            String strPathCrypto = System.getProperty("user.dir") + "\\lib\\libeay32.dll"; //Linux版本是libcrypto.so库文件的路径
            int iPathCryptoLen = strPathCrypto.getBytes().length;
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCrypto = new HCISUPCMS.BYTE_ARRAY(iPathCryptoLen + 1);
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, iPathCryptoLen);
            ptrByteArrayCrypto.write();
            hCEhomeSS.NET_ESS_SetSDKInitCfg(4, ptrByteArrayCrypto.getPointer());

            //设置libssl.so所在路径
            String strPathSsl = System.getProperty("user.dir") + "\\lib\\ssleay32.dll";    //Linux版本是libssl.so库文件的路径
            int iPathSslLen = strPathSsl.getBytes().length;
            HCISUPCMS.BYTE_ARRAY ptrByteArraySsl = new HCISUPCMS.BYTE_ARRAY(iPathSslLen + 1);
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, iPathSslLen);
            ptrByteArraySsl.write();
            hCEhomeSS.NET_ESS_SetSDKInitCfg(5, ptrByteArraySsl.getPointer());

            //设置sqlite3库的路径
            String strPathSqlite = System.getProperty("user.dir") + "\\lib\\sqlite3.dll";
            int iPathSqliteLen = strPathSqlite.getBytes().length;
            HCISUPCMS.BYTE_ARRAY ptrByteArraySqlite = new HCISUPCMS.BYTE_ARRAY(iPathSqliteLen + 1);
            System.arraycopy(strPathSqlite.getBytes(), 0, ptrByteArraySqlite.byValue, 0, iPathSqliteLen);
            ptrByteArraySqlite.write();
            hCEhomeSS.NET_ESS_SetSDKInitCfg(6, ptrByteArraySqlite.getPointer());
            //SDK初始化
            boolean sinit = hCEhomeSS.NET_ESS_Init();
            if (!sinit) {
                log.error("NET_ESS_Init失败，错误码：" + hCEhomeSS.NET_ESS_GetLastError());
            }
            //设置图片存储服务器公网地址 （当存在内外网映射时使用
            HCISUPCMS.NET_EHOME_IPADDRESS ipaddress = new HCISUPCMS.NET_EHOME_IPADDRESS();
            System.arraycopy(ehomePuIp.getBytes(), 0, ipaddress.szIP, 0, ehomePuIp.length());
            ipaddress.wPort = ss_port;
            ipaddress.write();
            boolean b = hCEhomeSS.NET_ESS_SetSDKInitCfg(3, ipaddress.getPointer());
            if (!b) {
                log.error("NET_ESS_SetSDKInitCfg失败，错误码：" + hCEhomeSS.NET_ESS_GetLastError());
            }
            startSsListen();

        } else if (osSelect.isLinux()) {
            HCISUPCMS.BYTE_ARRAY ptrByteArrayCrypto = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathCrypto = System.getProperty("user.dir") + "/lib/libcrypto.so"; //Linux版本是libcrypto.so库文件的路径
            System.arraycopy(strPathCrypto.getBytes(), 0, ptrByteArrayCrypto.byValue, 0, strPathCrypto.length());
            ptrByteArrayCrypto.write();
            hCEhomeSS.NET_ESS_SetSDKInitCfg(4, ptrByteArrayCrypto.getPointer());

            //设置libssl.so所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArraySsl = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathSsl = System.getProperty("user.dir") + "/lib/libssl.so";    //Linux版本是libssl.so库文件的路径
            System.arraycopy(strPathSsl.getBytes(), 0, ptrByteArraySsl.byValue, 0, strPathSsl.length());
            ptrByteArraySsl.write();
            hCEhomeSS.NET_ESS_SetSDKInitCfg(5, ptrByteArraySsl.getPointer());

            //设置splite3.so所在路径
            HCISUPCMS.BYTE_ARRAY ptrByteArraysplite = new HCISUPCMS.BYTE_ARRAY(256);
            String strPathsplite = System.getProperty("user.dir") + "/lib/libsqlite3.so";    //Linux版本是libsqlite3.so库文件的路径
            System.arraycopy(strPathsplite.getBytes(), 0, ptrByteArraysplite.byValue, 0, strPathsplite.length());
            ptrByteArraysplite.write();
            hCEhomeSS.NET_ESS_SetSDKInitCfg(6, ptrByteArraysplite.getPointer());
            //SDK初始化
            boolean sinit = hCEhomeSS.NET_ESS_Init();
            if (!sinit) {
                log.error("NET_ESS_Init失败，错误码：" + hCEhomeSS.NET_ESS_GetLastError());
            }
            //设置图片存储服务器公网地址 （当存在内外网映射时使用
            HCISUPCMS.NET_EHOME_IPADDRESS ipaddress = new HCISUPCMS.NET_EHOME_IPADDRESS();
            String ServerIP = ehomePuIp;
            System.arraycopy(ServerIP.getBytes(), 0, ipaddress.szIP, 0, ServerIP.length());
            ipaddress.wPort = ss_port;
            ipaddress.write();
            boolean b = hCEhomeSS.NET_ESS_SetSDKInitCfg(3, ipaddress.getPointer());
            if (!b) {
                log.error("NET_ESS_SetSDKInitCfg失败，错误码：" + hCEhomeSS.NET_ESS_GetLastError());
            }
            startSsListen();

        }
    }

    /**
     * 开启存储服务监听
     */
    public void startSsListen() {
        System.arraycopy(ehomeInIp.getBytes(), 0, pSSListenParam.struAddress.szIP, 0, ehomeInIp.length());
        pSSListenParam.struAddress.wPort = ss_port;
        String strKMS_UserName = "test";
        System.arraycopy(strKMS_UserName.getBytes(), 0, pSSListenParam.szKMS_UserName, 0, strKMS_UserName.length());
        String strKMS_Password = "12345";
        System.arraycopy(strKMS_Password.getBytes(), 0, pSSListenParam.szKMS_Password, 0, strKMS_Password.length());
        String strAccessKey = "test";
        System.arraycopy(strAccessKey.getBytes(), 0, pSSListenParam.szAccessKey, 0, strAccessKey.length());
        String strSecretKey = "12345";
        System.arraycopy(strSecretKey.getBytes(), 0, pSSListenParam.szSecretKey, 0, strSecretKey.length());
        pSSListenParam.byHttps = 0;
        /******************************************************************
         * 存储信息回调
         */
        if (pSS_Message_Callback == null) {
            pSS_Message_Callback = new PSS_Message_Callback();
        }
        pSSListenParam.fnSSMsgCb = pSS_Message_Callback;
        //存储回调
        if (pSS_Storage_Callback == null) {
            pSS_Storage_Callback = new PSS_Storage_Callback();
        }
        pSSListenParam.fnSStorageCb = pSS_Storage_Callback;

        pSSListenParam.bySecurityMode = 1;
        pSSListenParam.write();
        SsHandle = hCEhomeSS.NET_ESS_StartListen(pSSListenParam);
        if (SsHandle == -1) {
            int err = hCEhomeSS.NET_ESS_GetLastError();
            log.info("NET_ESS_StartListen failed,error:" + err);
            hCEhomeSS.NET_ESS_Fini();
            return;
        } else {
            String SsListenInfo = new String(pSSListenParam.struAddress.szIP).trim() + "_" + pSSListenParam.struAddress.wPort;
            log.info("存储服务器：" + SsListenInfo + ",NET_ESS_StartListen succeed!\n");
        }
    }



    public class PSS_Message_Callback implements HCISUPSS.EHomeSSMsgCallBack {

        public boolean invoke(int iHandle, int enumType, Pointer pOutBuffer, int dwOutLen, Pointer pInBuffer,
                              int dwInLen, Pointer pUser) {
            log.info("进入信息回调函数");
            if (1 == enumType) {
                HCISUPSS.NET_EHOME_SS_TOMCAT_MSG pTomcatMsg = new HCISUPSS.NET_EHOME_SS_TOMCAT_MSG();
                String szDevUri = new String(pTomcatMsg.szDevUri).trim();
                int dwPicNum = pTomcatMsg.dwPicNum;
                String pPicURLs = pTomcatMsg.pPicURLs;
                System.out.println("szDevUri = " + szDevUri + "   dwPicNum= " + dwPicNum + "   pPicURLs=" + pPicURLs);
            } else if (2 == enumType) {


            } else if (3 == enumType) {

            }
            return true;
        }
    }


    public class PSS_Storage_Callback implements HCISUPSS.EHomeSSStorageCallBack {

        public boolean invoke(int iHandle, String pFileName, Pointer pFileBuf, int dwFileLen, Pointer pFilePath, Pointer pUser) {

            String strPath = filepath+"temporary";
            String strFilePath = strPath+"/"+pFileName+".jpg";

            //若此目录不存在，则创建之
            File myPath = new File(strPath);
            if (!myPath.exists()) {
                myPath.mkdirs();
                log.info("创建文件夹路径为：" + strPath);
            }

            if (dwFileLen > 0 && pFileBuf != null) {
                FileOutputStream fout;
                try {
                    fout = new FileOutputStream(strFilePath);
                    //将字节写入文件
                    long offset = 0;
                    ByteBuffer buffers = pFileBuf.getByteBuffer(offset, dwFileLen);
                    byte[] bytes = new byte[dwFileLen];
                    buffers.rewind();
                    buffers.get(bytes);
                    fout.write(bytes);
                    fout.close();
                } catch (IOException e) {
                }
            }

            pFilePath.write(0, strFilePath.getBytes(), 0, strFilePath.getBytes().length);

            return true;
        }
    }

    public String getRawData(Pointer pUser, int maxLength) {
        StringBuilder sb = new StringBuilder();

        // 获取最大长度范围内的字节数据
        for (int i = 0; i < maxLength; i++) {
            byte b = pUser.getByte(i);
            sb.append(String.format("%02X ", b));  // 以十六进制格式输出字节
        }

        return sb.toString();
    }

    /**
     * 销毁存储客户端
     */
    public void ssDestroyClient() {
        if (hCEhomeSS.NET_ESS_DestroyClient(client))//释放资源
        {
            client = -1;
        }
        return;
    }

}
