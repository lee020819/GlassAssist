# GlassAssist

철도 현장 근무자를 위한 스마트 안경 AI 보조 앱 (Android)

KORAIL 역무원이 스마트 안경을 착용한 채로 음성 명령 하나로 민원 응대, 접객보호, 계량기 확인, 관제 연결까지 수행할 수 있도록 설계된 시스템입니다.

---

## 지원 단말

| 단말 | 상태 | 비고 |
|------|------|------|
| Meta Ray-Ban (Wayfarer / Headliner) | 개발·테스트 완료 | SDK 음성 제약 있음 (아래 한계점 참고) |
| Rokid | 수신 후 연동 진행 예정 | |

---

## 시스템 구성

```
Meta Ray-Ban 안경
  마이크 → 블루투스 SCO → 스마트폰 AudioRecord 버퍼
  스피커 ← 블루투스 SCO ← 스마트폰 TTS

스마트폰 (Android 앱)
  ├─ 음성 캡처 루프 (whisperCaptureLoop)
  ├─ 웨이크워드 감지 (정규식 "코비야/코비아")
  ├─ 분류 결과별 처리
  │    ├─ danger  → TTS 경고 + 관제 WebSocket 알림
  │    ├─ qa      → LLM 스트리밍 답변 TTS 출력
  │    ├─ dispatch→ 관제실 연결
  │    └─ ignore  → 무시
  └─ 관제 서버 WebSocket (dispatchWebSocket)

LLM 서버 (Jupyter / FastAPI)
  ├─ /stt    — Whisper 음성→텍스트
  └─ /chat   — 의도 분류 (classify_intent) + QA 생성 (stream_qa_sentences)

관제 서버 (dispatch_server.py)
  └─ 브라우저 대시보드 ← WebSocket 실시간 알림
```

---

## 주요 기능

### 1. 웨이크워드 + 음성 Q&A
- 안경 마이크 음성을 블루투스 SCO로 상시 수신
- "코비야 ..." 발화 감지 시 서버 `/stt` (Whisper) → 텍스트 변환
- 서버 `/chat` (LLM) 의도 분류 후 처리
  - **FAQ 직답**: KR-SBERT 코사인 유사도 ≥ 0.78이면 LLM 없이 즉시 반환
  - **RAG Q&A**: 관련 문서 3건 검색 → LLM 스트리밍 답변

### 2. 접객보호 (위험 발언 감지)
- `danger` 분류 시 TTS로 "긴급 상황이 감지되었습니다" 즉시 재생
- 관제 서버로 `protection_alert` WebSocket 전송 → 관제실 대시보드 알람
- 접객보호 기록 DB 저장 (키워드·시각)

### 3. 관제실 연결 (dispatch)
- "관제 연결" 발화 시 관제 서버로 WebSocket 연결
- 관제실↔역무원 실시간 음성 통신

### 4. 계량기 판독
- 음성 또는 버튼으로 촬영 트리거
- 서버 `/analyze-frame` (Vision AI) → 계량기 수치 TTS 출력

### 5. 근무 일지 / 인수인계
- 당일 접객보호·Q&A·계량기 기록 DB 저장
- 근무 종료 시 텍스트 파일 자동 생성 (Downloads 폴더)

---

## 서버 구성 (`LLMServer/`)

| 컴포넌트 | 역할 |
|---------|------|
| `glassassist_server.ipynb` Cell 12 | FastAPI 메인 서버 `/stt`, `/chat` |
| `dispatch_server.py` | 관제 WebSocket 중계 서버 |

### LLM 파이프라인 (Cell 12 `/chat`)
```
입력 텍스트
  → classify_intent()   LLM 의도 분류 (JSON forcing 기법)
       ↓ danger/dispatch/inspection/ignore
           → 하드코딩 메시지 즉시 반환 (LLM 답변 없음)
       ↓ qa
           → _faq_direct()   KR-SBERT + FAISS 유사도 검색 (임계값 0.78)
               ↓ hit
                   → 저장된 FAQ 답변 즉시 반환
               ↓ miss
                   → search_rag()   관련 문서 3건 검색
                   → stream_qa_sentences()   LLM 스트리밍 답변
```

---

## 기술 스택

- **앱**: Kotlin, Android, OkHttp (WebSocket + REST), SQLite
- **서버**: Python, FastAPI, Whisper (STT), vLLM (LLM 추론)
- **ML**: KR-SBERT (`snunlp/KR-SBERT-V40K-klueNLI-augSTS`), FAISS
- **LLM**: 사내 파인튜닝 8B 모델 (v11 기준 core 43.8%)

---

## 한계점 및 설계 배경

### 1. 안경 마이크 음성 8kHz 제약 (Meta SDK)

현재 음성 품질이 8kHz·모노인 것은 SDK 버전 문제가 아니라 **Meta Wearables Device Access Toolkit의 구조적 제약**입니다.

> *"HFP streams audio at 8kHz in mono. This is an expected limitation."*
> — Meta 공식 마이크/스피커 문서

Meta Ray-Ban은 5마이크 빔포밍 어레이를 탑재하고 있지만, 서드파티 앱에는 블루투스 HFP(Hands-Free Profile) 경로로만 음성이 전달됩니다. HFP는 전화 통화 품질(8kHz)로 고정됩니다.

**우회 방법**: 스마트폰 `AudioRecord`로 블루투스 SCO 버퍼를 직접 수신 → WAV 파일로 저장 → 서버 Whisper STT로 전달. 이 구조 덕분에 상시 수신이 가능합니다.

**16kHz 광대역화 가능 시점**: Meta가 Wi-Fi / LE Audio(아이소크로너스 채널) 통로를 SDK에 개방해야 가능. 현재 GitHub 커뮤니티 논의 단계이며 공식 약속·일정 없음.

### 2. 온디바이스 STT 미적용

Android `SpeechRecognizer` API는 내장 마이크를 직접 제어하는 방식입니다. 안경→블루투스→`AudioRecord` 버퍼를 직접 주입할 수 없어, 온디바이스 STT를 안경 입력에 바로 연결할 수 없습니다. 이 이유로 현재는 서버 Whisper를 사용합니다.

### 3. 웨이크워드 하드코딩

현재 웨이크워드 "코비"는 정규식으로 감지합니다 (`MainActivity.kt`).

```kotlin
private val wakeRegex = Regex("코\\s*비(야|아)?")
```

STT가 "코비"를 "고비"나 "코삐"로 잘못 인식하면 감지 실패. 향후 KoBERT 기반 학습 분류기로 전환 예정.

### 4. LLM이 분류·답변 동시 수행

현재 LLM이 `classify_intent`(모든 입력)와 `stream_qa_sentences`(qa 입력)를 모두 담당합니다. qa 입력은 LLM을 2회 호출합니다. 향후 분류는 온디바이스 KoBERT로 이전하고 LLM은 QA 전용으로 분리할 계획입니다.

---

## 빌드 및 실행

### 앱 빌드

```bash
# JAVA_HOME 설정 필요 (JDK 17)
set JAVA_HOME=C:\Users\leeee\.jdks\corretto-17.0.14
./gradlew assembleDebug
```

### 서버 실행

```bash
# LLM 서버
jupyter notebook LLMServer/06_서버/glassassist_server.ipynb

# 관제 서버
python dispatch_server.py
```

### 앱 초기 설정

앱 설치 후 설정 화면에서 아래 항목 입력:
- **사용자 ID**: 역무원 식별자
- **서버 URL**: `http://<LLM서버IP>:8000`
- **관제 WebSocket URL**: `ws://<관제서버IP>:8080/ws`

---

## 개발 현황

| 기능 | 상태 |
|------|------|
| 웨이크워드 감지 + STT | 완료 (Whisper) |
| 의도 분류 (classify_intent) | 완료 |
| FAQ 직답 (KR-SBERT + FAISS) | 완료 |
| RAG Q&A (LLM 스트리밍) | 완료 |
| 접객보호 감지·기록 | 완료 |
| 관제 WebSocket 알림 | 완료 |
| 관제↔역무원 음성 통신 | 완료 |
| 계량기 판독 (Vision AI) | 완료 |
| 근무 일지 자동 생성 | 완료 |
| KoBERT 온디바이스 분류 전환 | 예정 |
| Rokid 안경 연동 | 진행 예정 |

---

## 관련 자료

- Meta Wearables Device Access Toolkit: https://developers.meta.com/horizon/documentation/wearables/wearables-overview
- Whisper (OpenAI): https://github.com/openai/whisper
- KR-SBERT: https://huggingface.co/snunlp/KR-SBERT-V40K-klueNLI-augSTS
