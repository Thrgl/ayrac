# Ayraç — geliştirme rehberi

Ayraç, İngilizce öğrenmek için yapılmış bir EPUB okuyucu. Aynı okuyucu sayfası iki yerde çalışır:

| Sürüm | Nerede | Veriler nerede |
|---|---|---|
| **Masaüstü (Windows)** | `%LOCALAPPDATA%\Programs\Ayrac\Ayrac.exe` (masaüstü ve Başlat menüsü kısayolu) | `%APPDATA%\Ayrac` |
| **Web** | claude.ai'de özel bir sayfa (bağlantısı depoda tutulmuyor; Claude'un yayımladığı sayfalar listesinde "Ayraç") | Tarayıcının hafızası (kitaplar); kelime defteri claude.ai hesabında |

Sürüm geçmişi: [CHANGELOG.md](CHANGELOG.md). Şu anki sürüm: [desktop/VERSION](desktop/VERSION).

---

## Klasör yapısı

```
Ayrac/
├── ayrac.html            Okuyucunun tamamı (HTML + CSS + JS). Masaüstü ve web aynı dosyayı kullanır.
├── CHANGELOG.md          Sürüm geçmişi
├── GELISTIRME.md         Bu dosya
├── .gitignore            dist, releases, .sqlite ve .exe depoya girmez
└── desktop/
    ├── tests/            pytest: sözlük araması ve yedekleme
    ├── ayrac_app.py      Masaüstü uygulaması: pencere (pywebview), dosya kaydı, sözlük, Claude API, günlük
    ├── build.ps1         Derleme betiği → dist\Ayrac.exe ve releases\Ayrac-<sürüm>.exe
    ├── build_dict.py     Çevrimdışı sözlüğü (SQLite) Wiktionary verisinden üretir
    ├── installer.iss     Kurulum programı (Inno Setup 6; %LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe)
    ├── make_icon.py      Uygulama simgesini çizer
    ├── requirements.txt  Python bağımlılıkları
    ├── VERSION           Sürüm numarası (tek kaynak)
    ├── dist/             Son derleme: Ayrac.exe + dict\en-tr.sqlite
    └── releases/         Her sürümün .exe'si (geri dönmek için)
```

---

## Mimari

**Tek kaynak: `ayrac.html`.** Sayfa başında `window.AYRAC_DESKTOP` var mı diye bakar:

- **Web (claude.ai):** Kitaplar `IndexedDB`'de, ayarlar `localStorage`'da. Kelime anlamları, toplu seçim ve testler claude.ai'nin `sample` yeteneğiyle Claude'dan gelir; kelime defteri `db` yeteneğiyle hesapta eşitlenir.
- **Masaüstü:** `build.ps1` sayfayı `<script>window.AYRAC_DESKTOP=true; window.AYRAC_VERSION="x.y.z"</script>` ile sarar. Sayfa her şeyi Python köprüsü `window.pywebview.api` üzerinden yapar:

| Köprü metodu | Ne yapar |
|---|---|
| `kv_all`, `kv_set`, `kv_del` | Ayarlar, kelime defteri, okuma konumları, notlar → `%APPDATA%\Ayrac\data.sqlite` (tablo `kv`; 2.1.0 öncesindeki `data.json` ilk açılışta taşınır ve `.bak` olarak saklanır) |
| `books_list`, `book_meta`, `book_put`, `book_file`, `book_url`, `book_delete` | Kitaplık → `%APPDATA%\Ayrac\books\<sha1>.epub / .json / .cover`. Kitap ve kapaklar köprüden base64 geçmez: `127.0.0.1` üzerinde rastgele portta, rastgele anahtarlı adresle (`BookServer`) dosya olarak verilir |
| `dict_info`, `dict_lookup`, `dict_lookup_many`, `dict_profile` | Çevrimdışı sözlük (kök bulma, phrasal verb tespiti); `dict_profile` çok sayıda kelimenin kökünü ve sıklığını tek seferde verir (kitabın seviyesi, bölüm öncesi zor kelimeler; masaüstü ve Android) |
| `open_cambridge` | Cambridge Dictionary sayfasını ayrı bir uygulama penceresinde açar |
| `ai_status`, `ai_set_key`, `ai_clear_key`, `ai_set_model`, `ask` | İsteğe bağlı Claude (Anthropic API). Anahtar Windows Kimlik Bilgileri Yöneticisi'nde |
| `initial_file` | Komut satırından verilen .epub (Birlikte aç) |
| `log`, `app_info`, `set_dev_mode`, `open_path` | Geliştirici modu ve günlük |
| `save_text_file` | Sayfanın ürettiği metni (defter CSV'si) kaydetme penceresiyle diske yazar |
| `open_external` | Ayraç açıkken açılan `.epub`'ı pencereye getirir (`window.ayracOpenExternal`) |
| `check_update`, `set_update_source`, `install_update` | Masaüstü: güncelleme manifestini okur; yeni kurulumu indirip paketin dışında çalıştırır. Android: güncelleme klasöründeki en yeni `Ayrac-x.y.z.apk`; `set_update_source` klasör seçtirir, `install_update` Android kurulum ekranını açar |
| `backup_export`, `backup_import` | Yedek: tek ZIP (`ayrac-backup.json`, `data.json`, `books/`). Geri yüklemeden önce mevcut hâl `%APPDATA%\Ayrac\backups`'a yedeklenir |

Sayfadaki `store` (ayarlar) ve `books` (kitaplık) nesneleri bu ikisini soyutlar; özellik kodu hangi sürümde çalıştığını bilmez. Masaüstünde Claude çağrıları da web'deki `sample` arayüzüyle aynı biçimde sarılır (`desktopSample()`).

**Kitap içeriği** shadow DOM içinde çizilir (kitabın CSS'i uygulamayı bozmaz). Her kelime `<x-w>` öğesine sarılır; dokunma, toplu seçim, defterdeki kelimelerin altını çizme ve sesli okumada kelime vurgusu bunun üzerine kuruludur. Script, iframe, form ve `on*` öznitelikleri temizlenir.

**ZIP/EPUB okuma** kütüphanesizdir: merkez dizin elle okunur, açma `DecompressionStream('deflate-raw')` ile yapılır.

---

## Geliştirme ortamı (bir kez)

Bu bilgisayarda Microsoft Store Python 3.13 var. Store Python **AppData\Roaming'e yazamaz** (yazdıklarını kendi gizli kopyasına yönlendirir) ve uzun yollarda `venv` kuramaz. Bu yüzden bütün derleme işi kısa bir geçici klasörde yapılır:

```
%TEMP%\ayrac-build\
├── venv\     Python sanal ortamı (pywebview, anthropic, keyring, pyinstaller, pillow, wordfreq)
├── src\      Derleme için toplanan kaynaklar (build.ps1 her seferinde yeniden oluşturur)
├── dict\     Sözlük: en-tr.sqlite + indirilen Wiktionary dosyaları
├── dist\, build\   PyInstaller çıktısı
```

`build.ps1` venv yoksa kendisi kurar. Elle kurmak için:

```powershell
python -m venv "$env:TEMP\ayrac-build\venv"
& "$env:TEMP\ayrac-build\venv\Scripts\python.exe" -m pip install -r desktop\requirements.txt wordfreq
```

> **Dikkat:** Store Python ile çalışan teşhis betikleri `%APPDATA%\Ayrac`'ı göremeyebilir (boş kitaplık görürsünüz). Gerçek uygulamayı test etmek için kurulu `.exe`'yi kullanın (aşağıda "Test").

---

## Derleme

```powershell
powershell -ExecutionPolicy Bypass -File desktop\build.ps1        # Ayrac.exe üret
powershell -ExecutionPolicy Bypass -File desktop\build.ps1 -Run   # derlemeden, Python ile çalıştır
```

Çıktı:
- `desktop\dist\Ayrac.exe` (~20 MB, tek dosya) ve `desktop\dist\dict\en-tr.sqlite` (~38 MB)
- `desktop\releases\Ayrac-<sürüm>.exe` (arşiv)

Sözlük `.exe`'nin **içinde değil, yanında** durur (`dict\en-tr.sqlite`); uygulama onu `.exe`'nin klasöründe arar.

---

## Sürüm kontrolü (git)

Taşınabilir git: `%LOCALAPPDATA%\Programs\MinGit\cmd\git.exe` (PATH'te değil). Her sürüm kendi dalında geliştirilir (`v1.6.0` gibi), bitince `main`'e birleştirilip etiketlenir:

```powershell
$git = "$env:LOCALAPPDATA\Programs\MinGit\cmd\git.exe"
& $git switch -c v1.7.0          # yeni sürüm dalı
& $git add -A; & $git commit -m "..."
& $git switch main; & $git merge --no-ff v1.7.0; & $git tag v1.7.0
```

## GitHub

Depo herkese açık: https://github.com/Thrgl/ayrac (yalnız `main` ve `v*` etiketleri gönderilir; sürüm dalları yereldedir). Commit'ler GitHub'ın gizli adresiyle yazılır (`142834235+Thrgl@users.noreply.github.com`, depo `.git\config`'inde ayarlı); kişisel e-posta kullanma.

GitHub CLI: `%LOCALAPPDATA%\Programs\gh\bin\gh.exe` (PATH'te değil; git'in kimlik bilgisi yardımcısı olarak ayarlı, bu yüzden çalıştırırken MinGit PATH'e eklenmeli). Yeni sürümü yayımlamak:

```powershell
$env:PATH = "$env:LOCALAPPDATA\Programs\MinGit\cmd;$env:PATH"
$gh = "$env:LOCALAPPDATA\Programs\gh\bin\gh.exe"
git push origin main --tags
& $gh release create v2.8.0 --repo Thrgl/ayrac --title "Ayraç 2.8.0" --notes-file notlar.md `
    desktop\releases\Ayrac-Setup-2.8.0.exe android\releases\Ayrac-2.8.0.apk
```

## Yeni sürüm çıkarma

1. Değişikliği yap (`ayrac.html` ve/veya `desktop\ayrac_app.py`), testleri çalıştır.
2. `desktop\VERSION` dosyasındaki numarayı artır:
   - küçük düzeltme: `1.5.0 → 1.5.1`
   - yeni özellik: `1.5.0 → 1.6.0`
3. `CHANGELOG.md`'nin en üstüne yeni sürümü ve değişiklikleri yaz.
4. Derle: `powershell -ExecutionPolicy Bypass -File desktop\build.ps1`
5. Kur: `build.ps1` `desktop\releases\Ayrac-Setup-<sürüm>.exe` ve `releases\latest.json` üretir. İki yol:
   - Ayraç'ı aç: açılışta yeni sürümü görür, kitaplığın üstünde **Güncelle** çıkar (indirir, kurar, yeniden açar).
   - Ya da kurulum dosyasını çift tıkla (Ayraç açıksa kurulum kapatır; kitaplar ve defter etkilenmez).

   Claude'un terminalinden kurarken kurulumu **paketin dışında** başlat (aşağıdaki nota bak):

```powershell
$setup = (Resolve-Path desktop\releases\Ayrac-Setup-1.9.0.exe).Path
Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
  CommandLine = "`"$setup`" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /TASKS=`"desktopicon,epubassoc`"" }
```

6. Web sürümünü de güncellemek için `ayrac.html` claude.ai'de aynı adrese yeniden yayımlanır (Claude'a "web sürümünü de güncelle" demek yeterli).

**Eski sürüme dönmek:** `desktop\releases\Ayrac-Setup-<sürüm>.exe`'yi çalıştır (1.9.0 öncesi için `Ayrac-<sürüm>.exe`'yi `%LOCALAPPDATA%\Programs\Ayrac\Ayrac.exe` üzerine kopyala).

**Güncelleme kaynağı:** kurulum, `.exe`'nin yanına `update-source.txt` yazar (bu bilgisayarda `releases\latest.json`'ın `file:///` adresi). Biçim: `{"version": "1.9.1", "setup_url": "Ayrac-Setup-1.9.1.exe", "notes": "..."}` — `setup_url` manifeste göre görelidir, yani aynı yapı ileride bir web adresinde (ör. GitHub Releases) de çalışır; ayarlardaki `updateUrl` bu dosyayı geçersiz kılar.

---

## Test

**Hızlı arayüz testi (tarayıcıda):** `ayrac.html`'yi bir test sayfasına sarıp `window.pywebview.api`'yi sahte (bellekte çalışan) bir nesneyle taklit etmek, masaüstü kod yolunu tarayıcıda denemeye yeter. Web kod yolu için `window.claude.use('sample')` taklit edilir. Kitap olarak `C:\Users\Tahir\Downloads\Its_Only_a_Trick-David_Evans.epub` kullanıldı.

**Otomatik testler (`desktop\tests`):** sözlük araması (kalıplar, kök bulma, düzensiz çoğullar) ve yedekleme/geri yükleme. Sözlük testleri `en-tr.sqlite`'ı `AYRAC_DICT`, `%TEMP%\ayrac-build\dict` ya da `desktop\dist\dict` içinde arar; bulamazsa atlanır. Testler `APPDATA`'yı geçici bir klasöre çevirir, gerçek kitaplığa dokunmaz. Sözlük ya da arama mantığı her değiştiğinde çalıştır:

```powershell
& "$env:TEMP\ayrac-build\venv\Scripts\python.exe" -m pytest desktop\tests -q -p no:cacheprovider
```

**Android sözlük testi (`android\test.ps1`):** aynı `desktop\tests\dict_cases.json` vakalarını `Dictionary.java` üzerinde JUnit ile çalıştırır (veritabanı testte JDBC ile açılır, `Dictionary.Source`). Sözlük kuralını bir tarafta değiştirince ikisini de çalıştır; yeni bir vaka eklemek için JSON'a bir satır yeter.

**Python köprüsü:** `ayrac_app.Api` ve `ayrac_app.Dictionary` doğrudan içe aktarılıp çağrılabilir (veri klasörünü bozmamak için `APPDATA`'yı geçici bir klasöre yönlendirin).

**Gerçek uygulamanın içine bakmak:** WebView2'yi hata ayıklama portuyla başlatıp sayfada JS çalıştırın:

```powershell
$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = '--remote-debugging-port=9333'
Start-Process "$env:LOCALAPPDATA\Programs\Ayrac\Ayrac.exe"
Remove-Item Env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS
Invoke-RestMethod http://127.0.0.1:9333/json    # sayfanın webSocketDebuggerUrl'si → CDP Runtime.evaluate
```

İş bitince uygulamayı kapatıp kısayoldan normal açın (port açık kalmasın).

**Günlük:** `%APPDATA%\Ayrac\logs\ayrac.log` — her açılış, köprünün hazır olma süresi, kitaplıkta kaç kitap gösterildiği ve JS/Python hataları.

---

## Android uygulaması (2.0.0+)

Aynı `ayrac.html`, bir Android WebView'ın içinde çalışır. `android\web\android-bridge.js`, sayfaya masaüstündekiyle aynı `window.pywebview.api`'yi verir; her çağrı `MainActivity.Native.call(id, metod, argümanlar)` ile Java'ya gider, cevap `window.__ayracResolve` ile döner. Sayfa `window.AYRAC_PLATFORM = "android"` görür (metinler "bu telefonda", geri tuşu `window.ayracBack`). Telefona özel köprü metodu `set_bar_colors(renk, koyu)`: pencere, durum çubuğu ve alt gezinme çubuğu sayfanın arka plan rengini alır (tema değişince sayfa çağırır). Kitabın seviyesi ve bölüm öncesi zor kelimeler (`dict_profile`) şimdilik yalnız masaüstünde.

```
android/
├── build.ps1              Derleme → android\releases\Ayrac-<sürüm>.apk
├── make_icons.py          Başlatıcı simgeleri (res\mipmap-*)
├── web/android-bridge.js  Köprü + Android sesli okuma için speechSynthesis yerine geçen kod
├── app/build.gradle       Sürüm desktop\VERSION'dan okunur (tek sürüm numarası)
└── app/src/main/java/com/tahir/ayrac/
    ├── MainActivity.java  WebView, köprü metodları, dosya seçme/kaydetme, "Birlikte aç", geri tuşu
    ├── Store.java         data.json, books\, logs\, yedek ZIP (masaüstüyle aynı biçim)
    ├── Dictionary.java    ayrac_app.Dictionary'nin Java karşılığı (aynı kurallar)
    ├── Speech.java        Android TextToSpeech (kelime vurgusu: onRangeStart)
    └── Claude.java        Anthropic Java SDK (isteğe bağlı API anahtarıyla)
```

**Araçlar (bir kez):** JDK 17 ve Gradle 8.11.1 `%USERPROFILE%\Tools` içinde, Android SDK `%USERPROFILE%\Android\Sdk` içinde (platform 35, build-tools 35.0.0; yalnızca `android-sdk-license` kabul edildi). Bunlar AppData dışında, Claude terminalinin yönlendirmesinden etkilenmez.

**Derleme:** önce masaüstü sözlüğü ve venv hazır olmalı (`desktop\build.ps1` bir kez çalışmış olsun), sonra:

```powershell
powershell -ExecutionPolicy Bypass -File android\build.ps1
```

**İmzalama anahtarı:** ilk derlemede `android\keystore.jks` ve `android\keystore.properties` oluşur (git'e girmez). **Bu ikisini yedekle:** kaybolursa telefondaki uygulama güncellenemez, kaldırıp yeniden kurmak gerekir (veriler silinir; önce uygulamadan yedek al).

**Telefona kurma:** APK'yı telefona at (USB, Drive, e-posta), dosyaya dokun; ilk seferde "bilinmeyen kaynaklardan yükleme" izni istenir. Güncelleme: yeni APK'yı aynı şekilde kur (veriler korunur).

**Veriler telefonda:** uygulamanın kendi alanında (`/data/data/com.tahir.ayrac/files/Ayrac`). Bilgisayara taşımak için: Ayraç'ta "Yedek al" → ZIP → bilgisayarda "Yedekten yükle" (aynı biçim). Eşitleme (Firebase) ileride.

**Telefonda hata ayıklama:** geliştirici modu açıkken WebView hata ayıklaması açılır; telefon USB'yle bağlıyken bilgisayarda Chrome'da `chrome://inspect`.

## Geliştirici modu

Kitaplığın altındaki **sürüm numarasına 5 kez dokun** (ya da **Ctrl+Shift+D**). Panelde: sürüm, program yolu, veri klasörü, kitap sayısı, sözlük yolu, günlük. Düğmeler: günlüğü aç, veri klasörünü aç, kitaplığı yenile. Geliştirici modu açıkken uygulama yeniden başlatılınca **sağ tık → İncele** ile web inceleyicisi açılır.

---

## Yapay zekâ sesi (2.7.0+)

Sesli okuma için cihazda çalışan sinir ağı sesleri: **Piper** (VITS) modelleri, **sherpa-onnx** 1.13.8 ile (Apache-2.0). Dört ses, aynı listede iki tarafta: `ayrac_app.VOICES` ve `NeuralVoice.VOICES` — `lessac`, `ryan` (en-US), `alba`, `northern` (en-GB). Ses dosyaları k2-fsa/sherpa-onnx'in `tts-models` sürümünden, kullanıcı "İndir"e basınca bir kez indirilir: masaüstünde `%APPDATA%\Ayrac\voices`, telefonda `files/Ayrac/voices`. Önce geçici klasöre açılır, bitince yerine taşınır (yarım kurulum "kurulu" sayılmaz).

- **Masaüstü:** `Voices` sınıfı; `voice_speak` bir cümleyi WAV yapar, yerel kitap sunucusu `/<token>/tts/<id>` adresinden verir, sayfa `Audio` ile çalar. Sayfa sonraki iki cümleyi önceden ister (`prefetchNext`). Paket: `build.ps1` `--collect-all sherpa_onnx` (exe ~+10 MB). Paketlenmiş motoru denemek: `Ayrac.exe --voice-selftest <ses klasörü> <çıktı.txt>`.
- **Android:** `NeuralVoice` cümleyi işçi iş parçacığında üretir, `AudioTrack` ile çalar (ekran kapalıyken de); `Speech` sistem sesi ile bunu seçer (`voice_select`). Sonraki cümle `AyracNative.prefetch` ile önceden. Kütüphane `android\app\libs\sherpa-onnx-static-link-onnxruntime-1.13.8.aar` (git'te yok, `android\build.ps1` indirir); APK'ya yalnız `arm64-v8a` girer. Ses arşivleri için `commons-compress` (bzip2 + tar).
- **Neden Piper:** bu bilgisayarda 2 çekirdekle gerçek zamanın ~21 katı hızlı (telefonda da cümlelere yetişir). Kokoro (`kokoro-int8-en-v0_19`) daha doğal ama 8 çekirdekte bile ~1 katı; Kitten nano ~4 katı. Piper'ın fp16 sürümü bu motorla açılmıyor, int8 sürümü daha yavaş ve daha kötü.
- **Lisans notu:** seslerin eğitim verisi lisansları farklı (ör. lessac: Blizzard 2013, araştırma/kişisel kullanım). Kişisel kullanımda sorun yok; uygulama ticari dağıtılacaksa her sesin MODEL_CARD'ındaki lisansa bakılmalı.

---

## Çevrimdışı sözlük

`build_dict.py` iki Wiktionary dökümünden `en-tr.sqlite` üretir (kaikki.org / wiktextract, **CC BY-SA 4.0**):

| Kaynak | Adres | Boyut |
|---|---|---|
| İngilizce Wiktionary (İngilizce kelimeler) | https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz | ~499 MB |
| Türkçe Vikisözlük | https://kaikki.org/dictionary/downloads/tr/tr-extract.jsonl.gz | ~42 MB |

Tablolar: `entries(word, data)` — her kelime için JSON dizisi (`p` tür, `i` [ABD, İngiliz] IPA, `d` İngilizce tanımlar, `t` [anlam, Türkçe karşılıklar] grupları, `k` Türkçe tanımlar); `forms(form, lemma, note)` — çekimli biçimler; `freq(word, zipf)` — wordfreq sıklığı (uygulama bundan kaba CEFR tahmini çıkarır: `cefr_from_zipf`); `meta`. Hangi kelimeler alınır: en sık 60.000 İngilizce kelime (wordfreq) + Türkçe karşılığı ya da Türkçe tanımı olan her kelime/kalıp.

```powershell
# İndirip oluştur (yarıda kalırsa kaldığı yerden devam eder):
& "$env:TEMP\ayrac-build\venv\Scripts\python.exe" -u desktop\build_dict.py "$env:TEMP\ayrac-build\dict\en-tr.sqlite"
# Dosyalar zaten %TEMP%\ayrac-build\dict içindeyse, indirmeden:
$env:AYRAC_DICT_OFFLINE = '1'
# Denemek için ilk N satırla:
$env:AYRAC_DICT_LIMIT = '400'
```

İnternet yavaşsa büyük dosya IDM ile indirilip `%TEMP%\ayrac-build\dict\` içine aynı adla konabilir, sonra `AYRAC_DICT_OFFLINE=1` ile oluşturulur. Lisans gereği uygulamada "Sözlük: Wiktionary (CC BY-SA 4.0)" yazısı korunmalı.

Arama mantığı `ayrac_app.Dictionary.lookup` içinde: çekimli kelimede (Türkçe karşılığı yoksa) kök kelimeyle başlar; kök bulma sadece çekim eklerini (-s, -ed, -ing, 's) atar; kalıpları tıklanan kelimenin iki önü ve iki arkasından arar, geçmiş zamanı (`took off`) ve araya giren nesneyi (`take it off`) tanır.

---

## Claude (isteğe bağlı)

- Masaüstü: Anthropic Python SDK, varsayılan model `claude-opus-5-5` (ayarlardan `claude-haiku-5-5`), kısa işlerde `effort: low`, testlerde `medium`; Opus'ta reddedilen isteklerde sunucu tarafı yedek model (`fallbacks: "default"`).
- Web: claude.ai `sample` yeteneği (kullanıcının kendi Claude hakkından).
- Kelime anlamları masaüstünde **sözlükten** gelir; Claude sadece "Bu cümlede ne demek?", cümle çevirisi ve bölüm testi içindir.

---

## Bilinen notlar

- **Claude'un terminali bir paket kabının içinde (önemli):** Claude masaüstü uygulaması MSIX paketi olarak çalışır; terminalinden başlatılan programların `AppData\Roaming` ve `AppData\Local` yazmaları `%LOCALAPPDATA%\Packages\Claude_…\LocalCache\…` altına yönlendirilir (`Belgeler` ve `AppData\Local\Programs` yönlendirilmez). Bu yüzden 1.9.0'dan önce Claude'dan başlatılan Ayraç, masaüstü kısayolunun görmediği ayrı bir kitaplık kullandı. Kurallar:
  - Ayraç 1.9.0+ bunu kendisi fark eder (`data_dir_is_redirected`) ve kendini WMI ile paketin dışında yeniden başlatır.
  - Kurulum, test ve "gerçek" veriye erişim için programları WMI ile başlat: `Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = '...' }`. Bu terminalden `%APPDATA%\Ayrac`'ı okumak/yazmak **gizli kopyayı** gösterir; gerçeğini görmek için dosyaları WMI ile başlatılan bir PowerShell ile `Belgeler`'e kopyala.
  - Kayıt defteri (HKCU) yazmaları da yönlendirilebilir; kurulumu da WMI ile çalıştır.
- **Uygulamanın başlattığı programlar:** PyInstaller'ın tek dosyalı `.exe`'si alt süreçleri kendi grubunda tutar; güncelleme kurulumu bu yüzden de WMI ile başlatılır (`start_outside_container`).
- **Tek kopya:** Aynı anda iki Ayraç penceresi aynı WebView2 veri klasöründe birbirini kapatıyordu; ikinci açılış artık açık pencereyi öne getirir ve kendisine verilen `.epub`'ı adlandırılmış boru (`\\.\pipe\Ayrac-<kullanıcı>`, `%APPDATA%\Ayrac\.pipekey` ile korunur) üzerinden açık pencereye iletir.
- **pywebview ayarları:** `text_select=True` (yoksa metin seçilemez), `private_mode=False` + `storage_path=%APPDATA%\Ayrac\webview`, dış bağlantılar sistem tarayıcısında açılır.
- **Web sürümünün kitaplığı** tarayıcıya özeldir; Claude uygulamasının veri klasörü değişince sıfırlanabilir. Kalıcı kitaplık masaüstü sürümündedir.
- **Sesli okuma** Windows'un İngilizce seslerini kullanır (Edge/WebView2 `speechSynthesis`).

---

## Yol haritası (bekleyen)

- ✅ 2.0.0 — **Android uygulaması** (yukarıdaki "Android uygulaması" bölümü). Kalan: **bilgisayarla eşitleme** (Firebase projesi bekleniyor):
  - Okuyucu sayfası bir Android WebView uygulamasına (APK) gömülür; köprü (`window.pywebview.api` ile aynı metodlar) Java'da yazılır, sözlük SQLite olarak APK'da, sesli okuma Android TTS ile.
  - Eşitleme: Firebase (ücretsiz Spark planı) — e-posta/şifre girişi + Firestore. Kitaplar parçalara bölünerek, defter/okuma yeri/okundu bilgisi/ayarlar belge olarak. Eşitleme kodu `ayrac.html` içinde ortak yazılır, böylece masaüstü ve telefon aynı mantığı kullanır.
  - Gerekenler: Android geliştirme araçları (~1,5 GB, JDK + Android SDK + Gradle; Android SDK lisansının kabulü) ve kullanıcının açacağı Firebase projesi (`apiKey`, `projectId`).

---

## Eklenebilecekler (öneri listesi, 2026-10-17)

1.5.0 kodu incelenerek çıkarıldı. Her maddede **Emek** kaba bir tahmindir (S: birkaç saat, M: bir gün, L: birkaç gün). Önce "Hemen yapılmalı" bölümü, sonra özellikler.

### Hemen yapılmalı (altyapı ve küçük hatalar)

| Ne | Neden | Emek |
|---|---|---|
| ✅ 1.6.0 — **Klasörü git deposu yapmak** (`git init`, `desktop\dist`, `desktop\releases`, `*.sqlite` `.gitignore`'a) | Şu an sürüm kontrolü yok; `releases\` içindeki .exe'ler tek geri dönüş yolu. Bir değişikliğin neyi bozduğunu görmek imkânsız. | S |
| ✅ 1.6.0 — **Veri yedeği: dışa/içe aktar** | Defter, okuma yerleri ve ayarlar tek bir `data.json`'da. Bir "Yedek al / Yedekten yükle" düğmesi (ZIP: `data.json` + `books\`) disk değişiminde ya da bozulmada her şeyi kurtarır. Web'de de defteri JSON olarak indirip yükleyebilmek gerekir. | S |
| ✅ 1.6.0 — **Defterde altı çizme hatası** | `ayrac.html` `variants()` `-er`, `-est`, `-ly` eklerini de atıyor (Python tarafı bilerek atmıyor). Sonuç: defterde `on` varsa metindeki `only`, `ear` varsa `early`, `numb` varsa `number` da altı çizili görünür. Python'daki gibi yalnız çekim ekleri atılmalı, ya da önce kelimenin kendisi sözlükte aranmalı. | S |
| ✅ 1.6.0 — **Otomatik testler** | `Dictionary.lookup` için küçük bir `pytest` dosyası (örn. `took off → take off`, `take it off`, `looked`, `travelled`, `children`). Sözlük her yeniden üretildiğinde ve arama mantığı değiştiğinde çalıştırılır. | S |
| ✅ 2.1.0 — **`data.json` her yazışta baştan yazılıyor** | Okuma yeri sık kaydediliyor; defter büyüdükçe (binlerce kelime) her kaydırmada tüm dosya yeniden yazılır. Okuma yerlerini ayrı dosyaya almak ya da `kv` için SQLite kullanmak yeterli. | M |
| ✅ 2.1.0 — **Kitap köprüden base64 geçiyor** | `book_file` tüm EPUB'u base64 olarak JS'e yolluyor, `books_list` her açılışta bütün kapakları base64 gönderiyor. Büyük (resimli) kitaplarda ve kalabalık kitaplıkta yavaşlar. pywebview'in yerel HTTP sunucusundan dosya olarak servis etmek ya da kapakları küçük küçük resimlere (thumbnail) indirmek çözer. | M |

### Okuma deneyimi

| Ne | Açıklama | Emek |
|---|---|---|
| ✅ 1.7.0 — **Kitap içinde arama** | Tüm bölümlerde kelime/ifade arama, sonuç listesi ve sonuca atlama. Bir kelimenin kitapta başka nerede geçtiğini görmek öğrenme için de faydalı ("Bu kelime kitapta 14 kez geçiyor"). | M |
| ✅ 2.1.0 — **Vurgulama ve not** | Cümle/paragraf seçip renkle işaretleme, not ekleme; İçindekiler çekmecesinde "Notlarım" sekmesi. | M |
| ✅ 2.1.0 — **Yer imleri** | Kitap içinde birden çok yer imi (şu an sadece "kaldığın yer" var). | S |
| ✅ 2.1.0 — **Kalan süre** | Alt çubukta "Bölümün bitmesine ~6 dk" (okuma hızı kullanıcının kendi geçmişinden hesaplanır). | S |
| ✅ 2.1.0 — **Sayfa modu** | Kaydırma yerine sütunlu sayfa çevirme (CSS columns). Bazı okurlar için daha az yorucu. | M |
| ✅ 2.1.0 — **Dipnot açılır pencereleri** | EPUB'daki `epub:type="noteref"` bağlantılarını sayfa atlamadan kart içinde göstermek. | S |
| ✅ 2.1.0 — **Ekran kenarına dokunarak sayfa** | Dokunmatik ekran / ileride Android için sol-sağ kenar dokunuşları. 2.4.0'da Android'den kaldırıldı: kenardaki kelimeye dokununca sayfa değişiyordu; telefonda yerine parmakla yatay kaydırma (her iki düzende, yalnız dokunmatik/kalem). Bilgisayarda eski davranış sürer (`ANDROID` sabiti, `ayrac.html` "Turning pages"). | S |
| ✅ 2.1.0 — **Klavye kısayolları listesi** | `?` tuşuyla kısayol kartı (Alt+←/→, Boşluk, tekrar ekranındaki ←/→ zaten var ama görünmüyor). | S |

### Kelime öğrenme

| Ne | Açıklama | Emek |
|---|---|---|
| ✅ 1.8.0 — **Daha iyi aralıklı tekrar** | Şu an 6 kutulu Leitner, iki düğme (`INTERVAL_DAYS = [0,0,1,3,7,16,40]`). "Zor / İyi / Kolay" üçlüsü ve FSRS benzeri bir algoritma, unutulan kelimeyi 1. kutuya atmak yerine bir-iki kutu geri almak daha verimli. Günlük yeni kelime / tekrar sınırı da eklenmeli. | M |
| ✅ 1.8.0 — **Ters yön ve yazma kartları** | Şu an sadece İngilizce → Türkçe. Türkçe → İngilizce, boşluk doldurma (kitaptaki cümlede kelime gizli) ve "dinle, yaz" kartları kalıcı öğrenmeyi artırır. | M |
| ✅ 1.7.0 — **Anki / CSV dışa aktarma** | Defteri `kelime; anlam; cümle; IPA` olarak CSV ya da Anki paketi (`.apkg`) yapmak. Telefonda Anki kullananlar için Android uygulaması bitene kadar köprü olur. | S |
| ✅ 1.7.0 — **Seviye etiketi (CEFR) sözlükten** | Masaüstünde sözlük modu `cefr: ''` döndürüyor; etiket sadece Claude'dan geliyor. `build_dict.py` zaten `wordfreq` kullanıyor: Zipf sıklığından kabaca A1–C2 tahmini `entries`'e yazılabilir. | S |
| ✅ 2.2.0 — **Kitabın zorluğu** | Kitap eklenince "kelimelerin %94'ü ilk 3000 kelimede → B1 için uygun" gibi bir özet; kitaplıkta kapak altında seviye rozeti. | M |
| ✅ 2.2.0 — **Bölüm öncesi kelime listesi** | Bölüme başlamadan önce, kullanıcının seviyesine göre bilinmesi muhtemel olmayan 10–15 kelimeyi gösterip tek dokunuşla deftere ekleme. | M |
| ✅ 2.2.0 — **Bilinen kelimeler** | "Bunu biliyorum" işareti: o kelime bir daha toplu listelerde ve zorluk hesabında bilinmeyen sayılmaz. | S |
| ✅ 2.2.0 — **Defterde sıralama ve filtre** | Kitaba göre, kutuya göre, ekleme tarihine göre; "sadece bu kitabın kelimeleri" ile tekrar. | S |

### İstatistik ve motivasyon

| Ne | Açıklama | Emek |
|---|---|---|
| ✅ 1.8.0 — **Günlük seri ve hedef** | "Bugün 20 dk okudun, 12 tekrar yaptın · 7 günlük seri". Kitaplık başındaki `vocab-strip`'e eklenebilir. | S |
| ✅ 2.2.0 — **İlerleme grafiği** | Haftalık okuma süresi, eklenen / öğrenilen kelime sayısı. Veriler zaten var (`last`, `reviews`, `box`), okuma süresinin kaydı eklenmeli. | M |

### Claude ile

(2026-10-17: kullanıcının isteğiyle şimdilik ertelendi.)

| Ne | Açıklama | Emek |
|---|---|---|
| **Cümlenin dilbilgisi açıklaması** | Kelime kartında "Bu cümleyi açıkla": zaman, yapı, deyim, Türkçe açıklama. | S |
| **"Önceki bölümlerde ne olmuştu?"** | Kitaba uzun aradan sonra dönülünce kısa özet (sadece okunan kısımdan, sürprizi bozmadan). | M |
| **Bölüm hakkında sohbet** | Test yerine serbest soru-cevap ya da "bu bölümü 3 cümleyle İngilizce özetle, Claude düzeltsin" yazma alıştırması. | M |
| **Toplu çevirinin önbelleği** | Aynı cümlenin çevirisini / testi tekrar istememek için sonuçları kitap+bölüm bazında saklamak (API ücretini azaltır). | S |

### Ses

| Ne | Açıklama | Emek |
|---|---|---|
| ✅ 2.3.0 — **Doğal (neural) sesler** | WebView2'de Microsoft'un "Natural" sesleri çıkabiliyor; ses seçici eklenip en iyi ses varsayılan yapılmalı. | S |
| ✅ 2.3.0 — **Cümleyi tekrarla / gölgeleme** | Sesli okumada her cümleden sonra durup kullanıcının tekrar etmesini bekleyen mod. | M |

### Dağıtım (Windows)

| Ne | Açıklama | Emek |
|---|---|---|
| ✅ 1.9.0 — **Kurulum programı** | Inno Setup ile `Ayrac-Setup.exe`: Program Files / kullanıcı klasörüne kurulum, kısayollar, `.epub` dosya ilişkilendirmesi ("Birlikte aç" köprüsü `initial_file` hazır), kaldırma. Şu anki elle kopyalama adımı ortadan kalkar. | M |
| ✅ 1.9.0 — **Güncelleme kontrolü** | Açılışta yeni sürüm var mı diye bakıp (GitHub Releases vb.) indirip kuran küçük bir güncelleyici. | M |
| ✅ 1.9.0 — **İkinci açılışta dosyayı iletmek** | `focus_running_instance` ikinci açılışı kapatıyor ama ona verilen `.epub` kaybolur. Dosya yolu açık pencereye iletilmeli (adlandırılmış kanal ya da `WM_COPYDATA`). | S |
| **Kod imzalama** | İmzasız `.exe` SmartScreen uyarısı verir; başkalarına dağıtılacaksa gerekli. | S (ücretli) |

### Önerilen sıra

1. git + yedek + `variants()` hatası + sözlük testleri (bir oturumda biter, sonraki her şeyi güvenli kılar)
2. Kitap içinde arama, Anki/CSV dışa aktarma, sözlükten CEFR
3. Tekrar algoritması ve yeni kart türleri, günlük seri
4. Kurulum programı + dosya ilişkilendirme + güncelleyici
5. Android + eşitleme (yukarıdaki yol haritası)

---

## Eklenebilecekler — ikinci liste (2026-10-18)

2.4.x kodu incelenerek çıkarıldı. Yukarıdaki listede açık kalanlar: Claude özellikleri (ertelendi), eşitleme (Firebase projesi bekleniyor), kod imzalama. Aşağıdakiler yeni.

### Önce bunlar

| Ne | Neden | Emek |
|---|---|---|
| ✅ 2.5.0 — **Android imza anahtarının yedeği** (kopyası `Belgeler\Ayrac-veri\android-imza-anahtari`; bilgisayar dışına da konmalı) | `android\keystore.jks` ve `android\keystore.properties` yalnız bu bilgisayarda, git'e de girmiyor. Kaybolursa telefondaki Ayraç güncellenemez; kaldırıp yeniden kurmak gerekir ve telefondaki veriler gider. Bir kopyası USB'de ya da bulutta durmalı. | 5 dk |
| ✅ 2.5.0 — **Java sözlüğünün testi** (`desktop\tests\dict_cases.json` iki tarafta: `pytest desktop\tests` ve `android\test.ps1`) | `Dictionary.java`, `ayrac_app.Dictionary`'nin elle yazılmış kopyası; testler (`desktop\tests`) yalnız Python tarafını deniyor. Bir tarafta yapılan düzeltme ötekine geçmezse telefon ve bilgisayar farklı sonuç verir. Test vakaları ortak bir JSON dosyasına taşınıp iki tarafta da çalıştırılmalı. | S |

### Telefonda eksik kalanlar

| Ne | Açıklama | Emek |
|---|---|---|
| ✅ 2.5.0 — **Kitap seviyesi ve bölüm başı zor kelimeler** | `dict_profile` Android köprüsünde yok, bu yüzden "≈B1" rozeti ve bölüm öncesi kelime listesi telefonda çalışmıyor. | S |
| ✅ 2.6.0 — **Güncelleme denetimi** (web adresi yerine telefondaki güncelleme klasörü: geliştirici panelinde seçilir, `Ayrac-x.y.z.apk` aranır, Android kurulum ekranı açılır) | Android'de `check_update` / `install_update` boş; her yeni APK telefona elle taşınıyor. APK'nın bir web adresinde (örn. GitHub Releases) durması gerekir; aynı adres masaüstü güncelleyicisinin `latest.json`'ı için de kullanılabilir. | M |

### Yeni özellikler

| Ne | Açıklama | Emek |
|---|---|---|
| ✅ 2.5.0 — **Defterde anlamı düzenleme** (anlam + not; not tekrar kartında ve CSV'de) | Sözlükten gelen anlam bazen yanlış ya da çok uzun; defterde şu an yalnız "Sil" var (`renderNotebookList`). Anlamı ve notu elle düzeltebilmek. | S |
| ✅ 2.5.0 — **Defterden kitaptaki yere git** (yeni kayıtlarda `bookId` ve bölüm; eskilerde kitap adı ve cümle aranır) | Kelimenin cümlesine dokununca kitap o yerde açılsın. | S |
| ✅ 2.5.0 — **Bir türlü öğrenilemeyen kelimeler** ("inatçı": 6+ kez unutulan; işaret, filtre, cümleli soru, "Baştan öğren") | Tekrar sistemi kaç kez unutulduğunu sayıyor (`lapses`) ama yalnız "en zor önce" sıralamasında kullanıyor. 6–8 kez unutulan kelime işaretlenip ayrı çalışılabilir ya da kart türü değiştirilebilir. | S |
| ✅ 2.6.0 — **Günlük hatırlatma** (telefon: `ReminderReceiver`, başlangıç ekranının Ayarlar'ında saat) | Telefonda bildirim: "12 kelime tekrar bekliyor · 7 günlük serin bozulmasın". Bilgisayarda isteğe bağlı. | M |
| ✅ 2.6.0 — **Ücretsiz kitap kataloğu** (Project Gutenberg OPDS; köprü yalnız gutenberg.org'a bağlanır, yalnız arama kelimesi ve kitap numarası alır) | Standard Ebooks / Project Gutenberg'den kitap arama ve tek dokunuşla ekleme; seviye hesabı zaten var, kitaplar seviyeye göre sıralanabilir. | M |
| ✅ 2.5.0 — **TXT** (açarken bellekte EPUB'a çevrilir; Gutenberg başlığı/lisansı ayıklanır) | Şu an yalnız EPUB açılıyor (`fileInput` `accept=".epub"`). Düz metin (.txt) kolayca eklenir. | S |

### Ufak notlar

- **Web yedeği kitapları içermiyor:** yalnız defter, okuma yerleri ve ayarlar (JSON). Masaüstü ve telefondaki ZIP yedekte kitaplar da var.
- ✅ 2.5.0 — **`desktop\releases` büyüyor:** `build.ps1` (masaüstü ve Android) artık yalnız en yeni 4 sürümü tutuyor. ~18 dosya, ~600 MB. Yalnız son 3–4 sürümü tutma kuralı konabilir (`build.ps1` sonunda).
