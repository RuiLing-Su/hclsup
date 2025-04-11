import MissileEngineDecoder from './index';
import RenderEngine420P from './render-yuv420p';
let ws;
let rawParserObj;
let decoderMod;
let canvas;
let yuv;
window.onload = function () {
    const channelSelect = document.getElementById('channelSelect');
    for (let i = 1; i <= 27; i++) {
        const option = document.createElement('option');
        option.value = i;
        option.text = `Channel ${i}`;
        channelSelect.appendChild(option);
    }
    initPlayer();
};
function initPlayer() {
    canvas = document.getElementById('videoCanvas');
    yuv = RenderEngine420P.setupCanvas(canvas, { preserveDrawingBuffer: false });
    rawParserObj = new MissileEngineDecoder.CRawParser();
    const token = 'base64:QXV0aG9yOmNoYW5neWFubG9uZ3xudW1iZXJ3b2xmLEdpdGh1YjpodHRwczovL2dpdGh1Yi5jb20vbnVtYmVyd29sZixFbWFpbDpwb3JzY2hlZ3QyM0Bmb3htYWlsLmNvbSxRUTo1MzEzNjU4NzIsSG9tZVBhZ2U6aHR0cDovL3h2aWRlby52aWRlbyxEaXNjb3JkOm51bWJlcndvbGYjODY5NCx3ZWNoYXI6bnVtYmVyd29sZjExLEJlaWppbmcsV29ya0luOkJhaWR1';
    const version = '100.1.0';
    decoderMod = new MissileEngineDecoder.CMissileDecoder(token, version);
    decoderMod.initFinish = () => {
        console.log('Decoder initialized');
    };
    decoderMod.bindCallback((y, u, v, stride_y, stride_u, stride_v, width, height) => {
        RenderEngine420P.renderFrame(yuv, y, u, v, stride_y, height);
    });
    decoderMod.initDecoder();
}
function playSelectedChannel() {
    const channelSelect = document.getElementById('channelSelect');
    const selectedChannel = channelSelect.value;
    const id = GetQueryString("id") || '0';
    if (ws) {
        ws.close();
    }
    const wsUrl = `websocket/${id}/${selectedChannel}`;
    ws = new WebSocket(wsUrl);
    ws.onopen = () => {
        const openCommand = JSON.stringify({
            t: "open",
            c: "ch" + selectedChannel
        });
        ws.send(openCommand);
    };
    ws.onmessage = (event) => {
        if (event.data instanceof Blob) {
            const reader = new FileReader();
            reader.onload = (e) => {
                const chunk = new Uint8Array(e.target.result);
                rawParserObj.appendStreamRet(chunk);
                let nalBuf;
                while ((nalBuf = rawParserObj.nextNalu()) !== false) {
                    decoderMod.decodeNalu(nalBuf);
                }
            };
            reader.readAsArrayBuffer(event.data);
        }
    };
    ws.onclose = () => {
        console.log('WebSocket closed');
    };
}
function stopPlayback() {
    if (ws) {
        ws.close();
    }
}
function GetQueryString(name) {
    const reg = new RegExp("(^|&)" + name + "=([^&]*)(&|$)");
    const r = window.location.search.substr(1).match(reg);
    return r ? unescape(r[2]) : null;
}