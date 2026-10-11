# Android → Windows → USB 인쇄 사전 설계

2026-10-11. 선택된 cloud 실행기는 초기화 실패 상태다. 읽을 수 있었던 소스는 selfness-byte/yuran-android-build의 d16f9f3b8f87a0b474ee175298e42516835461bc (유란노트 1.17.1/code23, 유란사진 1.4.0/code6)이다. 배포 APK는 노트 1.19.59/code90 / 사진 1.7.37로 서로 다르다. 사용자는 이후 구 소스 기반 수정에 동의했지만, 생산 자료 형식14와 기존 서명 호환은 별도 해결이 필요하다.

이 문서는 최초 요청한 인쇄 연동 탐색 결과다. 뒤에 승인된 사진/수량 수리는 별도 변경이며, 여기의 Windows 수신기·LAN 전송·방화벽 변경은 구현하지 않았다. 다른 프로젝트·사용자 실제 재고·개인 사진·인증정보는 조사하지 않았다. 공개 checkout에 AGENTS/.agents/skills 파일은 없었으며, 실패한 실행기의 공용 로컬 지침은 읽을 수 없었다.

## 현재 코드 경로와 연결 위치

- MainActivity.prepareExport/confirmFrozenExport/executeExport: 현재 작업을 저장하고 고정된 ExportSnapshot과 보호된 사진 소스를 만든다.
- ExportSnapshot.capture → JobStore.prepareExport/exportMedia: 행 순서·수량·사진 버전·pin을 고정한다.
- Exporter.print → Android PrintManager → PrintDocumentAdapter: Android 인쇄 선택창을 연다. Windows 수신기나 USB Canon 직접 제어는 없다.
- Exporter.writePdf/writeXlsx와 FileSharing: 같은 snapshot을 파일 또는 Android 공유로 내보낸다. XlsxWriter는 사진을 XLSX 내부 JPEG/관계/앵커로 포함한다.
- 최소 연결점은 고정 snapshot을 기존 Exporter.writePdf로 만든 뒤 새 PC 전송 작업으로 넘기는 경로다. 인쇄를 위해 재고나 사진 카탈로그를 수정하지 않는다. 내보내기 pin은 전송 자료가 안전하게 저장될 때까지 유지한다.

## 최소 시험 구조

Android 유란노트 → 같은 공유기망의 Windows 사용자 세션 상주 수신기 → 설치된 Windows Canon G1910 프린터 큐 → 기존 USB 프린터. 별도 모바일 연결 앱은 필요 없다. PC 전원·로그인·수신기 실행이 필요하다.

1. 탐색: 첫 시험은 PC 화면의 주소/일회용 연결 코드로 수동 연결. 이어서 DNS-SD/Android NsdManager 자동 찾기와 수동 대체를 추가한다. 공유기/방화벽은 탐색 중 변경하지 않는다. 필요한 허용 규칙은 실제 설치 단계에서 범위를 확인한다.
2. 페어링: PC가 짧은 유효시간의 연결 화면을 열 때만 허용한다. 코드 시도 제한, PC 확인, 기기별 폐기 가능한 자격을 사용한다. QR을 쓰면 주소·서버 인증서 지문을 함께 전달하여 TLS 서버를 고정한다. 인증 우회/임의 인증서 수락은 하지 않는다.
3. 전송: PDF와 requestId(UUID), SHA-256, 페이지 수, 크기, A4/매수 설정을 보낸다. 수신기는 길이·해시·제한을 검증하고 디스크에 확정 저장한 다음 접수를 응답한다. 실제 데이터 대신 합성 한 페이지로 시작한다.
4. 인쇄: 수신기는 선택한 설치 프린터 이름을 사용한다. PDF를 페이지별 렌더링하여 Windows 드라이버 인쇄 경로에 보낸다. G1910이 RAW PDF를 해독한다고 가정하여 PDF 바이트를 그대로 WritePrinter에 보내지 않는다. 렌더러/배포 방식은 Windows 소규모 시험 뒤 확정한다.
5. 상태: 접수됨 → PC 저장됨 → 인쇄 큐 등록됨(jobId) → 인쇄 처리됨/오류/확인 필요. Windows 큐 상태를 종이가 실제 나온 것으로 단정하지 않는다. PC/USB/용지 상태가 불명확하면 사용자가 확인할 상태로 둔다.
6. 중복 방지: requestId와 내용 fingerprint를 영속 기록한다. 동일 id+동일 내용 재전송은 기존 상태를 돌려주고, 내용이 다르면 거절한다. 큐 등록 직후 중단되어 결과가 불명확한 작업을 자동 재출력하지 않는다. 다시 인쇄는 사용자가 명시적으로 새 작업을 만들 때만 한다.

Android NSD 동작 근거: https://developer.android.com/develop/connectivity/wifi/use-nsd
Windows 큐 등록/조회 API 근거: https://learn.microsoft.com/en-us/windows/win32/printdocs/startdocprinter
https://learn.microsoft.com/en-us/windows/win32/printdocs/getjob

## 합성자료와 실기기 검증의 경계

합성자료로 검증: 고정 snapshot/PDF 해시, 사진·행 순서, 잘린 전송/잘못된 해시, 중복 재시도, 동일 id 다른 내용, PC 저장 실패, 재시작 복구, 만료된 페어링, 모의 spooler의 작업상태/불명확한 접수. 실제 재고나 사진은 필요 없다.

Windows에서만 검증: 사용할 Windows 버전·Canon 드라이버 큐 이름, 상주/로그인 복귀, 프린터 목록, PDF 렌더러 배포, spooler 실제 반환값, 용지/USB 끊김 상태.
폰+PC에서 검증: 같은 공유기망 통신, 절전/화면 꺼짐, 연결 해제/재접속, DNS-SD 차단 시 수동 연결, 전송 중 종료와 재시도.
폰+PC+Canon에서 검증: A4 한 장의 사진/한글/여백/크기/색상, 여러 장 순서, 종이 부족과 USB 분리, PC 재부팅 후 중복 출력 여부. 현재 수행되지 않았다.

사용자에게 필요한 최소 선택은 첫 시험에서 ‘PC에서 인쇄 확인 후 출력’(권장) 또는 ‘페어링된 폰에서 보낸 자료 즉시 출력’이다. A4 세로·1부·지정 Canon 큐를 기본으로 하고 별도 요구가 있을 때만 바꾼다. 구현 전에 이 구체 설계를 확인한다.
