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

## İmza ve güncelleme güvenliği

APK, yalnızca GitHub Secrets'ta duran özel bir anahtarla imzalanır (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); anahtar depoda yoktur. Anahtar yoksa workflow release yayınlamaz. Uygulama içi güncelleme, indirilen APK'nın paket adını, sürüm numarasını ve imza sertifikasının SHA-256 parmak izini (`app/build.gradle.kts` içindeki `UPDATE_CERT_SHA256`) kurmadan önce kontrol eder; uyuşmazsa APK silinir. Anahtar değiştirilirse bu parmak izi de güncellenmeli ve TV'deki uygulama bir kez silinip elle kurulmalıdır. Yerel (anahtarsız) derlemeler güncelleme doğrulamasından geçmez.

## Ekran yansıtma (AirPlay) ve lisans

TV, ağda "TV Alici" adıyla bir AirPlay alıcısı olarak görünür; iPhone'un Denetim Merkezi'nden **Ekran Yansıtma** ile bağlanılır. Görüntü (H.264) ve ses (AAC-ELD) desteklenir. ALAC (Müzik uygulamasından AirPlay) desteklenmez. Ses ile görüntü arasındaki kayma elle ayarlanır (aşağıya bakın).

Bu özellik, FairPlay yayın anahtarını çözmek için [UxPlay](https://github.com/FDH2/UxPlay) projesindeki `playfair` kodunu (`app/src/main/cpp/playfair`, GNU GPL v3) ve aynı projenin `fairplay_playfair.c` dosyasındaki yanıt tablolarını (LGPL 2.1+, `AirPlayFairPlay.kt`) kullanır. Bu nedenle **bu depo GNU GPL v3 ile lisanslıdır** (bkz. `LICENSE`).

## Ses/görüntü senkronu

Ekran yansıtırken ses görüntüyle kayık geliyorsa TV kumandasında **SAĞ/SOL** tuşlarına basın (her basış 20 ms, en çok ±500 ms). Ses görüntüden **geç** geliyorsa SAĞ (görüntü geciktirilir), **erken** geliyorsa SOL (ses geciktirilir). Ayar kaydedilir; ekranda kısa bir bilgi çıkar ve günlüğe yazılır.

**Görüntü tamponu (Wi-Fi titremesi):** Kareler ağdan eşit aralıkla gelmez; geldikleri anda çizilirse görüntü takılır. Tampon, kareleri iPhone'un zaman damgasına göre eşit aralıkla çizer. Varsayılan **otomatik**: uygulama son birkaç saniyenin titremesini ölçüp tamponu 40–160 ms arasında kendisi ayarlar (artış hemen, azalış yavaş). Elle ayar için yansıtma sırasında **YUKARI/AŞAĞI** tuşları (20 ms'lik adımlar, 0–200 ms; 0 kapalı), **OK** tuşu otomatiğe döner. Tampon görüntüyü geciktirdiği için ses de aynı kadar geciktirilir (senkron bozulmaz), ama her şey o kadar daha geç görünür. Günlükteki `Titreme:` satırı titremenin dağılımını ve tamponu aşan kare sayısını, `Otomatik tampon:` satırı ayar değişimlerini gösterir.

## Bağlantı izni

Bilinmeyen bir cihaz AirPlay ile bağlanmak isteyince TV'de **İzin ver / Hep izin ver / Reddet** penceresi çıkar (uzaktan kumandayla seçilir). 30 saniye içinde cevap gelmezse ya da reddedilirse bağlantı kesilir ve aynı cihaz 1 dakika boyunca tekrar sorulmaz. "Hep izin ver" cihazın IP adresini kalıcı olarak güvenilir yapar.

Güvenilen cihazları görmek ve silmek için iPhone'dan `http://<tv-ip>:8080/trusted?pin=<PIN>` sayfasını açın (adres TV'nin ana ekranında da yazar). Silinen cihaz, açık bağlantısı kapanınca yeniden izin sorar.

## Fotoğraf AirPlay (`/photo`)

Fotoğraflar'da bir fotoğrafı açıp Paylaş > AirPlay > "TV Alici" ile TV'ye gönderin; fotoğraf tam ekran gösterilir. Geri tuşu ya da iPhone'dan durdurma ile QR ekranına dönülür. Slayt gösterisi (`/slideshows`) desteklenmez.

## Video AirPlay (`/play`)

Fotoğraflar uygulamasında bir videoyu açıp AirPlay simgesinden "TV Alici"yı seçince video TV'ye gönderilir ve ExoPlayer ile oynatılır (düz MP4 ve HLS). iPhone'un oynat/duraklat/ileri-geri sar komutları desteklenir. Yalnızca `http(s)` adresleri oynatılır; `mlhls://` gibi FairPlay korumalı akışlar (ör. YouTube) desteklenmez. Ekran yansıtmadan farkı: görüntüyü TV kendisi indirip çözer, bu yüzden kalite daha iyi, iPhone pili daha az harcanır.

AirPlay protokolü Apple'a aittir ve resmî olarak lisanslanmamıştır; bu proje kişisel/deneysel kullanım içindir.

## Hata ayıklama

- Günlük TV ekranında gösterilmez, yalnızca web sayfasından okunur.
- iPhone'dan `http://<tv-ip>:8080/log?pin=<PIN>` adresi tüm günlüğü gösterir.
