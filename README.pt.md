# AirPlay for Android

🌐 [Türkçe](README.tr.md) · [English](README.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · **Português** · [Italiano](README.it.md) · [Русский](README.ru.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [简体中文](README.zh.md) · [العربية](README.ar.md)

Transforma um dispositivo Android (Android TV, celular, tablet, set-top box) em um **receptor AirPlay** para iPhone e iPad: espelhamento de tela, vídeo, fotos, transmissão somente de áudio (Música, Spotify, …) e envio de vídeos pelo Safari sem instalar nada.

Ao iniciar, o app mostra um código QR. Escaneie com a câmera do iPhone para abrir o controle web, ou escolha **TV Alici** (você pode renomear) na Central de Controle > Espelhamento de Tela ou na lista do AirPlay.

## Recursos

- **Espelhamento de tela** (vídeo H.264, áudio AAC-ELD) com ajustes de sincronia áudio/vídeo e buffer, zoom e modos ajustar/preencher.
- **AirPlay de vídeo** (MP4 e HLS) e **AirPlay de fotos**.
- **AirPlay somente áudio** (Música, Spotify, Podcasts) com uma tela *Reproduzindo agora* em tela cheia, com capa e informações da música.
- **Controle web** em `http://<ip-do-dispositivo>:8080` (protegido por PIN): envie vídeos pelo Safari sem instalar nada, coloque vários vídeos em fila, reproduzir/pausar/avançar, volume, mostrar uma mensagem na TV, reproduzir um link de vídeo e adicioná-lo à tela inicial como app.
- **Menu na tela da TV** — pressione **MENU** (ou **OK** em controles sem tecla MENU, como o Mi TV Stick): início automático, temporizador de desligamento, sincronia A/V, buffer, zoom, ajuste de tela, relógio, estatísticas, dispositivos confiáveis, verificar atualizações.
- **Controle de acesso:** novos dispositivos precisam ser aprovados na TV (permitir uma vez / sempre / negar).
- **Atualizações no app** verificadas por nome do pacote, versão e certificado de assinatura.
- **12 idiomas** (conforme o idioma do dispositivo/navegador): English, Türkçe, Español, Français, Deutsch, Português, Italiano, Русский, 日本語, 한국어, 简体中文, العربية.

## Início rápido

1. Compile o APK com o GitHub Actions (push em `main` e baixe o artefato `tv-alici-debug-apk`) ou baixe um APK assinado em [Releases](https://github.com/ozsinyol/tv-alici/releases).
2. Instale no dispositivo Android (`adb install app-debug.apk`, ou abra o APK no celular/tablet). Os dois dispositivos devem estar na mesma rede Wi-Fi.
3. Abra o app e escaneie o código QR com a câmera do iPhone, ou escolha “TV Alici” em AirPlay / Espelhamento de Tela.

## Limitações

- Somente os protocolos clássicos do AirPlay (1) e de espelhamento de tela — sem AirPlay 2 (multiambiente, áudio em buffer, Siri/Casa).
- Vídeos protegidos por FairPlay (YouTube, Netflix, Apple TV+ …) não são reproduzidos, e conteúdo com DRM aparece preto no espelhamento.
- Apresentações de fotos não são suportadas; o espelhamento de tela é apenas H.264.
- O AirPlay é um protocolo proprietário da Apple, sem licença oficial; este projeto é para uso pessoal/experimental.

## Segurança e atualizações

O controle web exige o PIN exibido na TV (enviado em um cabeçalho da requisição; cinco tentativas erradas bloqueiam esse dispositivo por 5 minutos). Os APKs de versão são assinados com uma chave privada guardada no GitHub Secrets, e o atualizador do app só instala um APK se o nome do pacote, a versão e o SHA-256 do certificado coincidirem.

## Documentação completa

A documentação completa (em inglês) está em [README.md](README.md): compilação e assinatura, dispositivos confiáveis, ajuste de sincronia, API web, atualização manual e depuração. Para adicionar um idioma ao app, copie `app/src/main/res/values/strings.xml` para `values-<código>/` e adicione um bloco à tabela `DIL` em `VideoServer.kt`.

## Licença

GNU GPL v3 — veja [LICENSE](LICENSE). O projeto usa o código `playfair` do [UxPlay](https://github.com/FDH2/UxPlay) (GPL v3) e o decodificador ALAC de código aberto da Apple (Apple Public Source License 2.0, veja `app/src/main/cpp/alac/APPLE_LICENSE.txt`).
