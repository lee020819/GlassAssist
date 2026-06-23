# -*- coding: utf-8 -*-
"""
GlassAssist 관제서버 (재구성본)
 - 역무원 앱 ↔ 관제원 브라우저 간 접객보호 알림·음성통신 중계 (WebSocket)
 - 실행 중이던 원본 exe에서 복구한 대시보드를 그대로 서빙 (나이대 태깅 select만 제거)
 - 나이대 태깅은 영상이 있는 DB 대시보드(:8000/dashboard)에서 수행

[실행]  python dispatch_server.py   (관제 PC, 포트 8080)
[앱 연결]  콘솔/‘/qr’ 페이지의 QR을 앱에서 스캔  → ws://<관제PC IP>:8080/worker-ws
[exe 빌드]  pyinstaller --onefile dispatch_server.py
"""
import json, socket, io, base64
import qrcode
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Request
from fastapi.responses import HTMLResponse, JSONResponse

app = FastAPI()
workers: set[WebSocket] = set()    # 역무원 앱
browsers: set[WebSocket] = set()   # 관제원 브라우저

PORT = 8080

def get_lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80)); ip = s.getsockname()[0]
    except Exception:
        ip = "127.0.0.1"
    finally:
        s.close()
    return ip

async def _broadcast(targets: set, obj: dict):
    dead = []
    msg = json.dumps(obj, ensure_ascii=False)
    for ws in list(targets):
        try:
            await ws.send_text(msg)
        except Exception:
            dead.append(ws)
    for ws in dead:
        targets.discard(ws)

# ---------- HTTP ----------
@app.get("/", response_class=HTMLResponse)
async def index():
    return DASHBOARD_HTML

@app.get("/count")
async def count():
    return JSONResponse({"count": len(workers)})

@app.post("/send")
async def send(req: Request):
    body = await req.json()
    msg = (body.get("message") or "").strip()
    if msg:
        await _broadcast(workers, {"type": "dispatch", "message": msg})
    return JSONResponse({"ok": True, "workers": len(workers)})

@app.get("/qr", response_class=HTMLResponse)
async def qr_page():
    url = f"ws://{get_lan_ip()}:{PORT}/worker-ws"
    img = qrcode.make(url)
    buf = io.BytesIO(); img.save(buf, format="PNG")
    b64 = base64.b64encode(buf.getvalue()).decode()
    return f"""<html><body style="text-align:center;font-family:sans-serif;padding:40px">
    <h2>역무원 앱 연결 QR</h2>
    <img src="data:image/png;base64,{b64}" width="320"><p>{url}</p></body></html>"""

# ---------- WebSocket: 역무원 앱 ----------
@app.websocket("/worker-ws")
async def worker_ws(ws: WebSocket):
    await ws.accept(); workers.add(ws)
    try:
        while True:
            obj = json.loads(await ws.receive_text())
            # 앱 → 관제원 브라우저로 중계 (접객보호 알림 / 역무원 음성)
            if obj.get("type") in ("protection_alert", "protection_end", "protection_snapshot", "audio_from_worker"):
                await _broadcast(browsers, obj)
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        workers.discard(ws)

# ---------- WebSocket: 관제원 브라우저 ----------
@app.websocket("/browser-ws")
async def browser_ws(ws: WebSocket):
    await ws.accept(); browsers.add(ws)
    try:
        while True:
            obj = json.loads(await ws.receive_text())
            # 관제원 음성 → 모든 역무원에게 중계
            if obj.get("type") == "audio_to_worker":
                await _broadcast(workers, obj)
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        browsers.discard(ws)

# ---------- 대시보드 (복구본, 나이대 태깅 select 제거) ----------
DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>GlassAssist 관제실</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body { font-family: 'Segoe UI', sans-serif; background: #F0F4F8; color: #222; }
        .topbar { background: #1A237E; color: white; padding: 14px 24px; display: flex; align-items: center; gap: 12px; }
        .topbar h1 { font-size: 18px; font-weight: 700; }
        .topbar .badge { background: #42A5F5; border-radius: 12px; padding: 3px 10px; font-size: 13px; font-weight: 600; }
        .dot { width: 10px; height: 10px; border-radius: 50%; background: #4CAF50; display: inline-block; margin-right: 4px; }
        .container { max-width: 760px; margin: 0 auto; padding: 20px 16px; }
        .card { background: white; border-radius: 12px; padding: 18px; margin-bottom: 16px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }
        .card h3 { font-size: 15px; color: #1A237E; margin-bottom: 14px; font-weight: 700; }
        #alert_card { display: none; border: 2px solid #D32F2F; background: #fff8f8; }
        #alert_card h3 { color: #D32F2F; }
        .alert_info { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; font-size: 14px; }
        .alert_info span { background: #FFEBEE; padding: 4px 10px; border-radius: 8px; }
        .alert_info .kw { background: #D32F2F; color: white; font-weight: bold; }
        #live_view { width: 100%; max-height: 300px; object-fit: contain; border-radius: 8px; background: #111; display: none; }
        .rec_indicator { display: inline-flex; align-items: center; gap: 6px; background: #D32F2F; color: white; border-radius: 8px; padding: 4px 12px; font-size: 13px; font-weight: bold; margin-top: 10px; }
        .rec_dot { width: 8px; height: 8px; border-radius: 50%; background: white; animation: blink 1s infinite; }
        @keyframes blink { 0%,100%{opacity:1} 50%{opacity:0} }
        .age_note { margin-top:12px; font-size:13px; color:#1565C0; background:#E3F2FD; padding:8px 12px; border-radius:8px; }
        textarea { width: 100%; height: 64px; font-size: 15px; padding: 10px; border: 1.5px solid #BBDEFB; border-radius: 8px; resize: none; outline: none; font-family: inherit; }
        textarea:focus { border-color: #1565C0; }
        button { padding: 10px 20px; background: #1565C0; color: white; font-size: 14px; font-weight: bold; border: none; border-radius: 8px; cursor: pointer; margin-top: 8px; width: 100%; }
        button:hover { background: #0d47a1; }
        button.green { background: #388E3C; } button.green:hover { background: #2E7D32; }
        button.red { background: #D32F2F; } button.red:hover { background: #B71C1C; }
        audio { width: 100%; margin-top: 10px; }
    </style>
</head>
<body>
    <div class="topbar">
        <h1>📡 GlassAssist 관제실</h1>
        <span class="badge"><span class="dot"></span>역무원 <b id="count">0</b>명 연결</span>
        <button onclick="toggleDb()" style="margin-left:auto;width:auto;margin-top:0;background:#42A5F5;padding:6px 14px;font-size:13px">📊 DB 대시보드</button>
    </div>

    <div id="db_overlay" style="display:none;position:fixed;inset:0;background:rgba(0,0,0,.6);z-index:1000;">
        <div style="position:absolute;inset:20px;background:#fff;border-radius:12px;overflow:hidden;display:flex;flex-direction:column;">
            <div style="display:flex;align-items:center;justify-content:space-between;padding:10px 16px;background:#1A237E;color:#fff;">
                <b>📊 GlassAssist DB 대시보드</b>
                <span onclick="toggleDb()" style="cursor:pointer;font-size:20px;font-weight:bold;line-height:1;">✕</span>
            </div>
            <iframe id="db_frame" src="" style="border:none;flex:1;width:100%;"></iframe>
        </div>
    </div>

    <div class="container">
        <div class="card" id="alert_card">
            <h3>🚨 접객보호 알림</h3>
            <div class="alert_info">
                <span>역무원: <b id="alert_worker">-</b></span>
                <span class="kw">키워드: <b id="alert_keyword">-</b></span>
                <span>감지 시각: <b id="alert_time">-</b></span>
            </div>
            <img id="live_view" src="" alt="라이브 뷰">
            <div class="rec_indicator"><span class="rec_dot"></span> 서버 녹화 중</div>
            <div class="age_note">📊 나이대 태깅은 [DB 대시보드]에서 영상을 확인 후 진행하세요.</div>
        </div>

        <div class="card">
            <h3>💬 텍스트 전송</h3>
            <textarea id="msg" placeholder="역무원에게 전달할 메시지 입력 (Enter 전송)"></textarea>
            <button onclick="sendText()">전송</button>
        </div>

        <div class="card">
            <h3>🎤 음성 통신</h3>
            <button id="btn_record" class="green" onclick="toggleRecord()">🎤 녹음 시작</button>
            <audio id="rx_audio" controls></audio>
        </div>
    </div>

    <script>
        const DB_DASHBOARD_URL = "http://175.196.239.135:8000/dashboard";
        function toggleDb() {
            const ov = document.getElementById('db_overlay');
            const fr = document.getElementById('db_frame');
            if (ov.style.display === 'none' || !ov.style.display) { fr.src = DB_DASHBOARD_URL; ov.style.display = 'block'; }
            else { ov.style.display = 'none'; fr.src = ''; }
        }

        async function updateCount() {
            try { const res = await fetch('/count'); const data = await res.json(); document.getElementById('count').textContent = data.count; } catch(e) {}
        }
        setInterval(updateCount, 3000); updateCount();

        async function sendText() {
            const msg = document.getElementById('msg').value.trim();
            if (!msg) return;
            await fetch('/send', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ message: msg }) });
            document.getElementById('msg').value = '';
        }
        document.getElementById('msg').addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendText(); } });

        const bws = new WebSocket(`ws://${location.host}/browser-ws`);
        bws.onmessage = function(event) {
            const data = JSON.parse(event.data);
            if (data.type === 'audio_from_worker') {
                const audio = document.getElementById('rx_audio');
                audio.src = 'data:audio/wav;base64,' + data.data; audio.play();
            } else if (data.type === 'protection_alert') {
                const card = document.getElementById('alert_card');
                card.style.display = 'block';
                document.getElementById('alert_worker').textContent = data.from;
                document.getElementById('alert_keyword').textContent = data.keyword;
                document.getElementById('alert_time').textContent = new Date().toLocaleTimeString('ko-KR');
                document.getElementById('live_view').style.display = 'none';
            } else if (data.type === 'protection_snapshot') {
                const img = document.getElementById('live_view');
                img.src = 'data:image/jpeg;base64,' + data.data; img.style.display = 'block';
            } else if (data.type === 'protection_end') {
                document.getElementById('alert_card').style.display = 'none';
                document.getElementById('live_view').style.display = 'none';
            }
        };

        let mediaRecorder = null, audioChunks = [], isRecording = false;
        function toggleRecord() {
            if (!isRecording) {
                navigator.mediaDevices.getUserMedia({ audio: true }).then(stream => {
                    const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/ogg;codecs=opus';
                    mediaRecorder = new MediaRecorder(stream, { mimeType: mime });
                    audioChunks = [];
                    mediaRecorder.ondataavailable = e => audioChunks.push(e.data);
                    mediaRecorder.onstop = () => {
                        stream.getTracks().forEach(t => t.stop());
                        const blob = new Blob(audioChunks, { type: mime });
                        const reader = new FileReader();
                        reader.onloadend = () => {
                            const b64 = reader.result.split(',')[1];
                            bws.send(JSON.stringify({ type: 'audio_to_worker', data: b64, from: '관제실' }));
                        };
                        reader.readAsDataURL(blob);
                    };
                    mediaRecorder.start(); isRecording = true;
                    const btn = document.getElementById('btn_record'); btn.textContent = '⏹ 전송'; btn.className = 'red';
                }).catch(e => alert('마이크 권한 필요: ' + e.message));
            } else {
                if (mediaRecorder) mediaRecorder.stop(); isRecording = false;
                const btn = document.getElementById('btn_record'); btn.textContent = '🎤 녹음 시작'; btn.className = 'green';
            }
        }
    </script>
</body>
</html>"""

if __name__ == "__main__":
    ip = get_lan_ip()
    url = f"ws://{ip}:{PORT}/worker-ws"
    print("=" * 50)
    print(f"  관제 대시보드 : http://{ip}:{PORT}")
    print(f"  앱 연결 QR    : http://{ip}:{PORT}/qr  (또는 아래 QR 스캔)")
    print(f"  앱 연결 주소  : {url}")
    print("=" * 50)
    try:
        qr = qrcode.QRCode(border=1); qr.add_data(url); qr.make()
        qr.print_ascii(invert=True)
    except Exception:
        pass
    uvicorn.run(app, host="0.0.0.0", port=PORT)
