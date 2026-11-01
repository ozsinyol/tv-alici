# AirPlay for Android

🌐 [Türkçe](README.tr.md) · [English](README.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português](README.pt.md) · [Italiano](README.it.md) · [Русский](README.ru.md) · **日本語** · [한국어](README.ko.md) · [简体中文](README.zh.md) · [العربية](README.ar.md)

Androidデバイス（Android TV、スマートフォン、タブレット、セットトップボックス）を、iPhoneやiPad向けの**AirPlayレシーバー**にします。画面ミラーリング、ビデオ、写真、音声のみのストリーミング（ミュージック、Spotifyなど）、そしてSafariから何もインストールせずにビデオを送信できます。

起動するとアプリにQRコードが表示されます。iPhoneのカメラで読み取るとWebリモコンが開きます。または、コントロールセンターの「画面ミラーリング」やAirPlayの一覧から**TV Alici**（名前は変更できます）を選んでください。

## 機能

- **画面ミラーリング**（H.264映像、AAC-ELD音声）。音声と映像の同期・バッファ設定、ズーム、全体表示/全画面表示に対応。
- **ビデオのAirPlay**（MP4とHLS）と**写真のAirPlay**。
- **音声のみのAirPlay**（ミュージック、Spotify、Podcast）。ジャケット画像と曲情報を表示するフルスクリーンの「再生中」画面付き。
- **Webリモコン** `http://<デバイスのIP>:8080`（PIN保護）：Safariからインストール不要でビデオを送信、複数ビデオのキュー、再生/一時停止/シーク、音量、TVへのメッセージ表示、動画リンクの再生、ホーム画面にアプリとして追加。
- **TVのオンスクリーンメニュー** — **MENU**キー（Mi TV StickなどMENUキーのないリモコンでは**OK**）で開きます：自動起動、スリープタイマー、A/V同期、バッファ、ズーム、画面サイズ、時計、統計、信頼済みデバイス、アップデート確認。
- **アクセス制御：**新しいデバイスはTVで承認が必要です（今回のみ許可/常に許可/拒否）。
- **アプリ内アップデート**：パッケージ名、バージョン、署名証明書で検証します。
- **12言語**（デバイス/ブラウザの言語に従います）：English、Türkçe、Español、Français、Deutsch、Português、Italiano、Русский、日本語、한국어、简体中文、العربية。

## クイックスタート

1. GitHub ActionsでAPKをビルドする（`main`にpushし、`tv-alici-apk`アーティファクトをダウンロード）か、[Releases](https://github.com/ozsinyol/tv-alici/releases)から署名済みAPKをダウンロードします。
2. Androidデバイスにインストールします（`adb install app-release.apk`、またはスマートフォン/タブレットでAPKを開く）。2台は同じWi-Fiネットワークに接続してください。
3. アプリを開き、iPhoneのカメラでQRコードを読み取るか、AirPlay /「画面ミラーリング」で「TV Alici」を選びます。

## 制限事項

- 従来のAirPlay（1）と画面ミラーリングのプロトコルのみ対応です。AirPlay 2（マルチルーム、バッファ再生、Siri/ホーム）は非対応です。
- FairPlayで保護されたビデオ（YouTube、Netflix、Apple TV+など）は再生できず、DRMコンテンツはミラーリングすると黒く表示されます。
- 写真のスライドショーには対応していません。画面ミラーリングはH.264のみです。
- AirPlayはAppleの独自プロトコルで、公式のライセンスはありません。このプロジェクトは個人/実験用途向けです。

## セキュリティとアップデート

WebリモコンにはTVに表示されるPINが必要です（リクエストヘッダで送信され、5回間違えるとそのデバイスは5分間ロックされます）。リリースAPKはGitHub Secretsに保管された秘密鍵で署名され、アプリ内アップデータはパッケージ名、バージョン、証明書のSHA-256がすべて一致するAPKのみをインストールします。

## 詳細ドキュメント

完全なドキュメント（英語）は[README.md](README.md)にあります：ビルドと署名、信頼済みデバイス、同期の調整、Web API、手動アップデート、デバッグ。アプリに言語を追加するには、`app/src/main/res/values/strings.xml`を`values-<コード>/`にコピーし、`VideoServer.kt`の`DIL`テーブルにブロックを追加してください。

## ライセンス

GNU GPL v3 — [LICENSE](LICENSE)を参照。このプロジェクトは[UxPlay](https://github.com/FDH2/UxPlay)の`playfair`コード（GPL v3）と、Appleのオープンソース ALACデコーダー（Apple Public Source License 2.0、`app/src/main/cpp/alac/APPLE_LICENSE.txt`参照）を使用しています。
