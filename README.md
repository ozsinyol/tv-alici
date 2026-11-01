# AirPlay for Android

Android cihazı (Android TV, telefon, tablet, Android kutu/ekran) iPhone'lar için bir **AirPlay sunucusu** yapan uygulama: iPhone'dan ekran yansıtma, video, fotoğraf ve (Safari'den, uygulama kurmadan) video yükleme.

Uygulama açılınca QR kod gösterir. iPhone ile okutulur, açılan sayfadan video seçilir, cihazda oynar. Ayrıca ağda "TV Alici" adıyla bir AirPlay sunucusu olarak görünür; iPhone'un Denetim Merkezi ve AirPlay listesinde bu adla seçilir.

> Bu belgede geçen "TV", uygulamanın kurulu olduğu Android cihaz anlamına da gelir; telefon ve tablette de aynı şekilde çalışır. Kumanda tuşlarıyla yapılan ayarlar için bkz. [Android telefon/tablette kullanım](#android-telefontablette-kullanım).

## Diller

Uygulama arayüzü ve web sayfası (8080) cihaz/tarayıcı diline uyar: **İngilizce** (varsayılan), **Türkçe**, İspanyolca, Fransızca, Almanca, Portekizce, İtalyanca, Rusça, Japonca, Korece, Çince (Basitleştirilmiş) ve Arapça (sağdan sola). English summary: [README.en.md](README.en.md). Yeni dil eklemek için `app/src/main/res/values-<kod>/strings.xml` oluşturun ve `VideoServer.kt` içindeki `DIL` tablosuna bir blok ekleyin. Geliştirici günlüğü Türkçe kalır.

## APK'yı GitHub Actions ile derleme

1. Bu klasörü bir GitHub deposuna yükleyin (`main` dalı).
2. Depoda **Actions** sekmesi > **Build APK** iş akışı otomatik çalışır (ya da **Run workflow** ile elle başlatın).
3. İş bitince çalıştırmanın sayfasında **Artifacts** altından `tv-alici-debug-apk` dosyasını indirin.

## Cihaza kurma

- **Android TV:** geliştirici seçeneklerini ve USB/ağ hata ayıklamayı açın, ardından:
  `adb connect <tv-ip>` ve `adb install app-debug.apk`. Ya da APK'yı USB belleğe koyup TV'deki bir dosya yöneticisiyle kurun.
- **Android telefon/tablet:** APK'yı [Releases](https://github.com/ozsinyol/tv-alici/releases) sayfasından cihazda indirip açın (gerekirse "bilinmeyen kaynaklardan yükleme" iznini verin) ya da `adb install` kullanın. Uygulama simgesi başlatıcıda görünür.

iPhone ve Android cihaz aynı Wi-Fi ağında olmalı.

## Android telefon/tablette kullanım

Uygulama Android TV'de ve diğer Android cihazlarda aynı şekilde çalışır: QR ekranı, AirPlay sunucusu, bağlantı izni penceresi (dokunmayla seçilir), video, fotoğraf ve ekran yansıtma. Aşağıdaki kumanda tuşu ayarları (ses/görüntü kaydırma, görüntü tamponu, MENU ile otomatik başlatma) yön/OK/MENU tuşlarıyla yapılır; dokunmatik ekranda bu tuşlar yoktur, bunlar için Bluetooth klavye ya da uzaktan kumanda gerekir. Telefon/tablette cihazın kendi ekranı ve hoparlörü kullanılır, ekran yansıtma sırasında ekran kapanmaz.

## İmza ve güncelleme güvenliği

APK, yalnızca GitHub Secrets'ta duran özel bir anahtarla imzalanır (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); anahtar depoda yoktur. Anahtar yoksa workflow release yayınlamaz. Uygulama içi güncelleme, indirilen APK'nın paket adını, sürüm numarasını ve imza sertifikasının SHA-256 parmak izini (`app/build.gradle.kts` içindeki `UPDATE_CERT_SHA256`) kurmadan önce kontrol eder; uyuşmazsa APK silinir. Anahtar değiştirilirse bu parmak izi de güncellenmeli ve TV'deki uygulama bir kez silinip elle kurulmalıdır. Yerel (anahtarsız) derlemeler güncelleme doğrulamasından geçmez.

## Ekran yansıtma (AirPlay) ve lisans

Android cihaz, ağda "TV Alici" adıyla bir AirPlay sunucusu olarak görünür; iPhone'un Denetim Merkezi'nden **Ekran Yansıtma** ile bağlanılır. Görüntü (H.264) ve ses (AAC-ELD) desteklenir. Yalnızca ses (Müzik, Spotify vb.) için bkz. [Ses AirPlay](#ses-airplay-müzik-vb). Ses ile görüntü arasındaki kayma elle ayarlanır (aşağıya bakın).

Bu özellik, FairPlay yayın anahtarını çözmek için [UxPlay](https://github.com/FDH2/UxPlay) projesindeki `playfair` kodunu (`app/src/main/cpp/playfair`, GNU GPL v3) ve aynı projenin `fairplay_playfair.c` dosyasındaki yanıt tablolarını (LGPL 2.1+, `AirPlayFairPlay.kt`) kullanır. Bu nedenle **bu depo GNU GPL v3 ile lisanslıdır** (bkz. `LICENSE`).

## Ses AirPlay (Müzik vb.)

iPhone'da Müzik, Spotify, Podcasts gibi uygulamalarda AirPlay simgesinden "TV Alici"yı seçince yalnızca ses TV'ye gelir (ALAC, 44100 Hz, 16 bit, stereo). Ses, `app/src/main/cpp/alac` altındaki Apple'ın açık kaynak ALAC çözücüsüyle (Apache 2.0) çözülür ve yaklaşık 600 ms'lik bir tamponla çalınır; müzikte gecikme önemli olmadığı için Wi-Fi titremesi bu tamponla gizlenir. iPhone'dan ses seviyesi, duraklatma ve sarma (FLUSH) komutları desteklenir. Ekranda şarkı bilgisi ya da kapak resmi gösterilmez; kayıp paket yeniden istenmez (çok zayıf Wi-Fi'de ara sıra çıtırtı olabilir).

## Ses/görüntü senkronu

Ekran yansıtırken ses görüntüyle kayık geliyorsa TV kumandasında **SAĞ/SOL** tuşlarına basın (her basış 20 ms, en çok ±500 ms). Ses görüntüden **geç** geliyorsa SAĞ (görüntü geciktirilir), **erken** geliyorsa SOL (ses geciktirilir). Ayar kaydedilir; ekranda kısa bir bilgi çıkar ve günlüğe yazılır.

**Görüntü tamponu (Wi-Fi titremesi):** Kareler ağdan eşit aralıkla gelmez; geldikleri anda çizilirse görüntü takılır. Tampon, kareleri iPhone'un zaman damgasına göre eşit aralıkla çizer. Varsayılan **otomatik**: uygulama son birkaç saniyenin titremesini ölçüp tamponu 40–160 ms arasında kendisi ayarlar (artış hemen, azalış yavaş). Elle ayar için yansıtma sırasında **YUKARI/AŞAĞI** tuşları (20 ms'lik adımlar, 0–200 ms; 0 kapalı), **OK** tuşu otomatiğe döner. Tampon görüntüyü geciktirdiği için ses de aynı kadar geciktirilir (senkron bozulmaz), ama her şey o kadar daha geç görünür. Günlükteki `Titreme:` satırı titremenin dağılımını ve tamponu aşan kare sayısını, `Otomatik tampon:` satırı ayar değişimlerini gösterir.

## AirPlay adını değiştirme

iPhone'un AirPlay listesinde görünen ad varsayılan olarak "TV Alici"dır. Değiştirmek için `http://<cihaz-ip>:8080/name?pin=<PIN>` sayfasını açıp yeni adı yazın (1-32 karakter; adres ana ekranda da yazar). Ad kaydedilir ve servis yeni adla yeniden yayınlanır; iPhone listesi birkaç saniye içinde yenilenir. Telefon/tablette cihazları ayırt etmek için ("Salon TV", "Pixel 8" gibi) kullanışlıdır.

## Bağlantı izni

Bilinmeyen bir cihaz AirPlay ile bağlanmak isteyince TV'de **İzin ver / Hep izin ver / Reddet** penceresi çıkar (uzaktan kumandayla ya da dokunarak seçilir). 30 saniye içinde cevap gelmezse ya da reddedilirse bağlantı kesilir ve aynı cihaz 1 dakika boyunca tekrar sorulmaz. "Hep izin ver" cihazın IP adresini kalıcı olarak güvenilir yapar.

"Hep izin ver" denilen bir cihaz 30 gün hiç bağlanmazsa güveni kendiliğinden kalkar ve bir sonraki bağlantıda yeniden sorulur; düzenli kullanılan cihazın süresi her kullanımda yenilenir.

Güvenilen cihazları görmek ve silmek için iPhone'dan `http://<tv-ip>:8080/trusted?pin=<PIN>` sayfasını açın (adres TV'nin ana ekranında da yazar). Silinen cihaz, açık bağlantısı kapanınca yeniden izin sorar.

## Bağlanan cihaza selam

Bir iPhone ya da iPad ekran yansıtma/ses için bağlanınca TV'de kısa bir 👋 bildirimi cihazın adıyla görünür (ad gelmezse "iPhone").

## Boştayken zıplayan QR

QR ekranı 20 saniye boşta (tuşa basılmadan, dokunulmadan) kalırsa QR kodu ve yazılar eski DVD logosu gibi ekranda sekmeye başlar, duvara çarpınca renk değiştirir ve köşeye tam isabet ederse 🎉 çıkar. Aynı zamanda ekran yanmasını önler. Herhangi bir tuşa basınca ya da dokununca durur ve QR yerine döner; bir bağlantı başlayınca ekran zaten değişir.

## Açılışta otomatik başlatma

Ana (QR) ekranındayken kumandanın **MENU** tuşu "TV açılınca otomatik başlat" ayarını açıp kapatır (varsayılan kapalı). MENU tuşu olmayan kumandalarda (ör. Mi TV Stick) **OK (orta) tuşuna basmak** aynı işi yapar. Açıkken TV açılışında uygulama kendini başlatır. Android 10 ve sonrasında bazı TV'ler arka plandan ekran açmayı engeller; bu durumda Ayarlar > Uygulamalar > AirPlay for Android > "Diğer uygulamaların üzerinde göster" izni gerekebilir.

## Fotoğraf AirPlay (`/photo`)

Fotoğraflar'da bir fotoğrafı açıp Paylaş > AirPlay > "TV Alici" ile TV'ye gönderin; fotoğraf tam ekran gösterilir. Geri tuşu ya da iPhone'dan durdurma ile QR ekranına dönülür. Slayt gösterisi (`/slideshows`) desteklenmez.

## Video AirPlay (`/play`)

Fotoğraflar uygulamasında bir videoyu açıp AirPlay simgesinden "TV Alici"yı seçince video TV'ye gönderilir ve ExoPlayer ile oynatılır (düz MP4 ve HLS). iPhone'un oynat/duraklat/ileri-geri sar komutları desteklenir. Yalnızca `http(s)` adresleri oynatılır; `mlhls://` gibi FairPlay korumalı akışlar (ör. YouTube) desteklenmez. Ekran yansıtmadan farkı: görüntüyü TV kendisi indirip çözer, bu yüzden kalite daha iyi, iPhone pili daha az harcanır.

AirPlay protokolü Apple'a aittir ve resmî olarak lisanslanmamıştır; bu proje kişisel/deneysel kullanım içindir.

## Web arayüzü (port 8080)

Cihazın ekranındaki adres (`http://<cihaz-ip>:8080`) her şeyin bir arada olduğu tek bir sayfa açar; üstte PIN alanı, altında sırayla şu bölümler vardır:

- **TV'ye mesaj:** Yazılan kısa metin TV ekranında 20 saniye büyük yazıyla görünür ("Yemek hazır" gibi); boş gönderince kalkar.
- **Gönder:** Video seçip gönderme ve oynatma kumandası (Safari'den, uygulama kurmadan). Birden fazla video seçilirse ilki hemen oynar, diğerleri sırayla yüklenip bitince otomatik oynatılır; **Sonraki** düğmesi bir sonraki videoya atlar, **Durdur** sırayı da temizler.
- **Ayarlar:** AirPlay sunucu adını değiştirme ve güvenilen cihazları silme.
- **Uygulamayı güncelle:** Release anahtarıyla imzalı APK'yı tarayıcıdan gönderme (aşağıya bakın).
- **Günlük:** Uygulama günlüğü (açılır bölüm; **Yenile** ile güncellenir).

Eski adresler çalışmaya devam eder: `/name?pin=<PIN>` ve `/trusted?pin=<PIN>` aynı sayfayı Ayarlar bölümünde açar, `/log?pin=<PIN>` düz metin günlüğü verir.

Güvenlik: PIN istekte `X-Pin` başlığıyla gider (adres çubuğuna yazılmaz). Aynı cihazdan 5 yanlış denemeden sonra 5 dakika boyunca PIN denemesi reddedilir. İsim değiştirme ve cihaz silme gibi değişiklikler `POST` ile yapılır; bir bağlantıya tıklamakla tetiklenemez.

## Elle güncelleme (APK gönderme)

İnternet ya da GitHub erişimi olmayan cihazlarda, web arayüzündeki **Uygulamayı güncelle** bölümünden APK gönderilebilir. Sayfa kurulu sürümü de gösterir (ör. `v33`).

1. APK'yı seçip **APK gönder**'e basın (en fazla 150 MB).
2. TV, otomatik güncellemeyle aynı kontrolü yapar: paket adı aynı olmalı, imza sertifikasının SHA-256 parmak izi `UPDATE_CERT_SHA256` ile eşleşmeli ve sürüm kurulu sürümden eski olmamalı. Reddedilirse nedeni sayfada yazar.
3. Kontrol geçerse TV'de sistemin kurulum ekranı açılır; kumandayla onaylayın. Bu onay olmadan kurulum yapılmaz.

Notlar: Uygulama TV'de açık (QR ekranında) olmalıdır. Uygulamaya "bilinmeyen kaynaklardan yükleme" izni verilmemişse TV'de izin ekranı açılır; izni verip APK'yı tekrar gönderin. Başka anahtarla imzalı (ör. yerel/debug) derlemeler reddedilir.

## Hata ayıklama

- Günlük TV ekranında gösterilmez, yalnızca web sayfasından okunur.
- iPhone'dan `http://<tv-ip>:8080/log?pin=<PIN>` adresi tüm günlüğü gösterir.
