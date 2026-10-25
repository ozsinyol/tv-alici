# TV Alıcı

iPhone'dan (Safari, uygulama kurmadan) Android TV'ye video gönderme prototipi.

TV uygulaması açılınca QR kod gösterir. iPhone ile okutulur, açılan sayfadan video seçilir, TV'de oynar.

## APK'yı GitHub Actions ile derleme

1. Bu klasörü bir GitHub deposuna yükleyin (`main` dalı).
2. Depoda **Actions** sekmesi > **Build APK** iş akışı otomatik çalışır (ya da **Run workflow** ile elle başlatın).
3. İş bitince çalıştırmanın sayfasında **Artifacts** altından `tv-alici-debug-apk` dosyasını indirin.

## TV'ye kurma

- TV'de geliştirici seçeneklerini ve USB/ağ hata ayıklamayı açın, ardından:
  `adb connect <tv-ip>` ve `adb install app-debug.apk`
- Ya da APK'yı USB belleğe koyup TV'deki bir dosya yöneticisiyle kurun.

iPhone ve TV aynı Wi-Fi ağında olmalı.

## Ekran yansıtma (AirPlay) ve lisans

TV, ağda "TV Alici" adıyla bir AirPlay alıcısı olarak görünür; iPhone'un Denetim Merkezi'nden **Ekran Yansıtma** ile bağlanılır. Görüntü (H.264) ve ses (AAC-ELD) desteklenir. ALAC (Müzik uygulamasından AirPlay) desteklenmez. Ses ile görüntü arasında hassas senkron yoktur.

Bu özellik, FairPlay yayın anahtarını çözmek için [UxPlay](https://github.com/FDH2/UxPlay) projesindeki `playfair` kodunu (`app/src/main/cpp/playfair`, GNU GPL v3) ve aynı projenin `fairplay_playfair.c` dosyasındaki yanıt tablolarını (LGPL 2.1+, `AirPlayFairPlay.kt`) kullanır. Bu nedenle **bu depo GNU GPL v3 ile lisanslıdır** (bkz. `LICENSE`).

## Bağlantı izni

Bilinmeyen bir cihaz AirPlay ile bağlanmak isteyince TV'de **İzin ver / Hep izin ver / Reddet** penceresi çıkar (uzaktan kumandayla seçilir). 30 saniye içinde cevap gelmezse ya da reddedilirse bağlantı kesilir ve aynı cihaz 1 dakika boyunca tekrar sorulmaz. "Hep izin ver" cihazın IP adresini kalıcı olarak güvenilir yapar.

## Video AirPlay (`/play`)

Fotoğraflar uygulamasında bir videoyu açıp AirPlay simgesinden "TV Alici"yı seçince video TV'ye gönderilir ve ExoPlayer ile oynatılır (düz MP4 ve HLS). iPhone'un oynat/duraklat/ileri-geri sar komutları desteklenir. Yalnızca `http(s)` adresleri oynatılır; `mlhls://` gibi FairPlay korumalı akışlar (ör. YouTube) desteklenmez. Ekran yansıtmadan farkı: görüntüyü TV kendisi indirip çözer, bu yüzden kalite daha iyi, iPhone pili daha az harcanır.

AirPlay protokolü Apple'a aittir ve resmî olarak lisanslanmamıştır; bu proje kişisel/deneysel kullanım içindir.

## Hata ayıklama

- Günlük TV ekranında gösterilmez, yalnızca web sayfasından okunur.
- iPhone'dan `http://<tv-ip>:8080/log?pin=<PIN>` adresi tüm günlüğü gösterir.
