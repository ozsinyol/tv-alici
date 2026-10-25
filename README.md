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
