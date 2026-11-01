# AirPlay for Android

🌐 [Türkçe](README.tr.md) · [English](README.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português](README.pt.md) · [Italiano](README.it.md) · [Русский](README.ru.md) · [日本語](README.ja.md) · **한국어** · [简体中文](README.zh.md) · [العربية](README.ar.md)

[![Build](https://github.com/ozsinyol/tv-alici/actions/workflows/build.yml/badge.svg)](https://github.com/ozsinyol/tv-alici/actions/workflows/build.yml) [![Release](https://img.shields.io/github/v/release/ozsinyol/tv-alici)](https://github.com/ozsinyol/tv-alici/releases/latest) [![License: GPL v3](https://img.shields.io/badge/license-GPL%20v3-blue.svg)](LICENSE) ![Android 6.0+](https://img.shields.io/badge/Android-6.0%2B-3DDC84)

Android 기기(Android TV, 휴대폰, 태블릿, 셋톱박스)를 iPhone과 iPad용 **AirPlay 수신기**로 만들어 줍니다. 화면 미러링, 비디오, 사진, 오디오 전용 스트리밍(음악, Spotify 등), 그리고 Safari에서 아무것도 설치하지 않고 비디오 전송을 지원합니다.

앱을 시작하면 QR 코드가 표시됩니다. iPhone 카메라로 스캔하여 웹 리모컨을 열거나, 제어 센터 > 화면 미러링 또는 AirPlay 목록에서 **TV Alici**(이름 변경 가능)를 선택하세요.

## 기능

- **화면 미러링**(H.264 영상, AAC-ELD 오디오): 오디오/비디오 싱크 및 버퍼 설정, 확대, 맞춤/채우기 모드.
- **비디오 AirPlay**(MP4 및 HLS)와 **사진 AirPlay**.
- **오디오 전용 AirPlay**(음악, Spotify, 팟캐스트): 앨범 커버와 곡 정보를 보여 주는 전체 화면 *재생 중* 화면.
- **웹 리모컨** `http://<기기-IP>:8080`(PIN 보호): Safari에서 설치 없이 비디오 전송, 여러 비디오 대기열, 재생/일시정지/탐색, 볼륨, TV에 메시지 표시, 비디오 링크 재생, 홈 화면에 앱으로 추가.
- **TV 화면 메뉴** — **MENU**를 누르세요(Mi TV Stick처럼 MENU 키가 없는 리모컨은 **OK**): 자동 시작, 수면 타이머, A/V 싱크, 버퍼, 확대, 화면 맞춤, 시계, 통계, 신뢰하는 기기, 업데이트 확인.
- **접근 제어:** 새 기기는 TV에서 승인해야 합니다(한 번 허용 / 항상 허용 / 거부).
- **앱 내 업데이트**: 패키지 이름, 버전, 서명 인증서로 검증합니다.
- **12개 언어**(기기/브라우저 언어를 따름): English, Türkçe, Español, Français, Deutsch, Português, Italiano, Русский, 日本語, 한국어, 简体中文, العربية.

## 빠른 시작

1. GitHub Actions로 APK를 빌드하거나(`main`에 push한 뒤 `tv-alici-apk` 아티팩트 다운로드) [Releases](https://github.com/ozsinyol/tv-alici/releases)에서 서명된 APK를 내려받으세요.
2. Android 기기에 설치하세요(`adb install app-release.apk` 또는 휴대폰/태블릿에서 APK 열기). 두 기기는 같은 Wi-Fi 네트워크에 있어야 합니다.
3. 앱을 열고 iPhone 카메라로 QR 코드를 스캔하거나, AirPlay / 화면 미러링에서 “TV Alici”를 선택하세요.

## 제한 사항

- 기존 AirPlay(1)와 화면 미러링 프로토콜만 지원합니다. AirPlay 2(멀티룸, 버퍼 오디오, Siri/홈)는 지원하지 않습니다.
- FairPlay로 보호된 비디오(YouTube, Netflix, Apple TV+ 등)는 재생되지 않으며, DRM 콘텐츠는 미러링 시 검게 표시됩니다.
- 사진 슬라이드쇼는 지원하지 않으며, 화면 미러링은 H.264만 지원합니다.
- AirPlay는 Apple의 독점 프로토콜이며 공식 라이선스가 없습니다. 이 프로젝트는 개인/실험용입니다.

## 보안 및 업데이트

웹 리모컨은 TV에 표시된 PIN이 필요합니다(요청 헤더로 전송되며, 5번 틀리면 해당 기기는 5분간 차단됩니다). 릴리스 APK는 GitHub Secrets에 보관된 개인 키로 서명되며, 앱 내 업데이트는 패키지 이름, 버전, 인증서 SHA-256이 모두 일치하는 APK만 설치합니다.

## 전체 문서

전체 문서(영어)는 [README.md](README.md)에 있습니다: 빌드 및 서명, 신뢰하는 기기, 싱크 조정, 웹 API, 수동 업데이트, 디버깅. 앱에 언어를 추가하려면 `app/src/main/res/values/strings.xml`을 `values-<코드>/`로 복사하고 `VideoServer.kt`의 `DIL` 표에 블록을 추가하세요.

## 라이선스

GNU GPL v3 — [LICENSE](LICENSE)를 참조하세요. 이 프로젝트는 [UxPlay](https://github.com/FDH2/UxPlay)의 `playfair` 코드(GPL v3)와 Apple의 오픈 소스 ALAC 디코더(Apple Public Source License 2.0, `app/src/main/cpp/alac/APPLE_LICENSE.txt` 참조)를 사용합니다.
