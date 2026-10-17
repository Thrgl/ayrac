# Ayraç — geliştirme rehberi

Ayraç, İngilizce öğrenmek için yapılmış bir EPUB okuyucu. Aynı okuyucu sayfası iki yerde çalışır:

| Sürüm | Nerede | Veriler nerede |
|---|---|---|
| **Masaüstü (Windows)** | `%LOCALAPPDATA%\Programs\Ayrac\Ayrac.exe` (masaüstü ve Başlat menüsü kısayolu) | `%APPDATA%\Ayrac` |
| **Web** | https://claude.ai/artifact/UbkBdjJpYHuCi2tVxGwD1u | Tarayıcının hafızası (kitaplar); kelime defteri claude.ai hesabında |

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
| `kv_all`, `kv_set`, `kv_del` | Ayarlar, kelime defteri, okuma konumları → `%APPDATA%\Ayrac\data.json` |
| `books_list`, `book_meta`, `book_put`, `book_file`, `book_delete` | Kitaplık → `%APPDATA%\Ayrac\books\<sha1>.epub / .json / .cover` |
| `dict_info`, `dict_lookup`, `dict_lookup_many` | Çevrimdışı sözlük (kök bulma, phrasal verb tespiti) |
| `open_cambridge` | Cambridge Dictionary sayfasını ayrı bir uygulama penceresinde açar |
| `ai_status`, `ai_set_key`, `ai_clear_key`, `ai_set_model`, `ask` | İsteğe bağlı Claude (Anthropic API). Anahtar Windows Kimlik Bilgileri Yöneticisi'nde |
| `initial_file` | Komut satırından verilen .epub (Birlikte aç) |
| `log`, `app_info`, `set_dev_mode`, `open_path` | Geliştirici modu ve günlük |
| `save_text_file` | Sayfanın ürettiği metni (defter CSV'si) kaydetme penceresiyle diske yazar |
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

## Yeni sürüm çıkarma

1. Değişikliği yap (`ayrac.html` ve/veya `desktop\ayrac_app.py`), testleri çalıştır.
2. `desktop\VERSION` dosyasındaki numarayı artır:
   - küçük düzeltme: `1.5.0 → 1.5.1`
   - yeni özellik: `1.5.0 → 1.6.0`
3. `CHANGELOG.md`'nin en üstüne yeni sürümü ve değişiklikleri yaz.
4. Derle: `powershell -ExecutionPolicy Bypass -File desktop\build.ps1`
5. Kurulu uygulamayı güncelle (Ayraç açıksa kapanır; kitaplar ve defter etkilenmez):

```powershell
$dest = "$env:LOCALAPPDATA\Programs\Ayrac"
Get-Process Ayrac -EA SilentlyContinue | % { [void]$_.CloseMainWindow() }
# Ayrac.exe iki süreçtir (PyInstaller); ikisi de kapanana kadar bekle, gerekirse zorla kapat
foreach ($i in 1..20) { if (-not (Get-Process Ayrac -EA SilentlyContinue)) { break }; Start-Sleep 1
  if ($i -eq 8) { Get-Process Ayrac -EA SilentlyContinue | Stop-Process -Force } }
foreach ($i in 1..10) { try { Copy-Item desktop\dist\Ayrac.exe $dest -Force -ErrorAction Stop; break } catch { Start-Sleep 1 } }
Copy-Item desktop\dist\dict\en-tr.sqlite "$dest\dict" -Force   # sadece sözlük değiştiyse
Start-Process "$([Environment]::GetFolderPath('Desktop'))\Ayraç.lnk"
```

6. Web sürümünü de güncellemek için `ayrac.html` claude.ai'de aynı adrese yeniden yayımlanır (Claude'a "web sürümünü de güncelle" demek yeterli).

**Eski sürüme dönmek:** `desktop\releases\Ayrac-<sürüm>.exe` dosyasını `%LOCALAPPDATA%\Programs\Ayrac\Ayrac.exe` üzerine kopyala.

---

## Test

**Hızlı arayüz testi (tarayıcıda):** `ayrac.html`'yi bir test sayfasına sarıp `window.pywebview.api`'yi sahte (bellekte çalışan) bir nesneyle taklit etmek, masaüstü kod yolunu tarayıcıda denemeye yeter. Web kod yolu için `window.claude.use('sample')` taklit edilir. Kitap olarak `C:\Users\Tahir\Downloads\Its_Only_a_Trick-David_Evans.epub` kullanıldı.

**Otomatik testler (`desktop\tests`):** sözlük araması (kalıplar, kök bulma, düzensiz çoğullar) ve yedekleme/geri yükleme. Sözlük testleri `en-tr.sqlite`'ı `AYRAC_DICT`, `%TEMP%\ayrac-build\dict` ya da `desktop\dist\dict` içinde arar; bulamazsa atlanır. Testler `APPDATA`'yı geçici bir klasöre çevirir, gerçek kitaplığa dokunmaz. Sözlük ya da arama mantığı her değiştiğinde çalıştır:

```powershell
& "$env:TEMP\ayrac-build\venv\Scripts\python.exe" -m pytest desktop\tests -q -p no:cacheprovider
```

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

## Geliştirici modu

Kitaplığın altındaki **sürüm numarasına 5 kez dokun** (ya da **Ctrl+Shift+D**). Panelde: sürüm, program yolu, veri klasörü, kitap sayısı, sözlük yolu, günlük. Düğmeler: günlüğü aç, veri klasörünü aç, kitaplığı yenile. Geliştirici modu açıkken uygulama yeniden başlatılınca **sağ tık → İncele** ile web inceleyicisi açılır.

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

- **Tek kopya:** Aynı anda iki Ayraç penceresi aynı WebView2 veri klasöründe birbirini kapatıyordu; ikinci açılış artık açık pencereyi öne getirip çıkar.
- **pywebview ayarları:** `text_select=True` (yoksa metin seçilemez), `private_mode=False` + `storage_path=%APPDATA%\Ayrac\webview`, dış bağlantılar sistem tarayıcısında açılır.
- **Web sürümünün kitaplığı** tarayıcıya özeldir; Claude uygulamasının veri klasörü değişince sıfırlanabilir. Kalıcı kitaplık masaüstü sürümündedir.
- **Sesli okuma** Windows'un İngilizce seslerini kullanır (Edge/WebView2 `speechSynthesis`).

---

## Yol haritası (bekleyen)

- **Android uygulaması + bilgisayarla eşitleme** (planlandı, onay bekliyor):
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
| **`data.json` her yazışta baştan yazılıyor** | Okuma yeri sık kaydediliyor; defter büyüdükçe (binlerce kelime) her kaydırmada tüm dosya yeniden yazılır. Okuma yerlerini ayrı dosyaya almak ya da `kv` için SQLite kullanmak yeterli. | M |
| **Kitap köprüden base64 geçiyor** | `book_file` tüm EPUB'u base64 olarak JS'e yolluyor, `books_list` her açılışta bütün kapakları base64 gönderiyor. Büyük (resimli) kitaplarda ve kalabalık kitaplıkta yavaşlar. pywebview'in yerel HTTP sunucusundan dosya olarak servis etmek ya da kapakları küçük küçük resimlere (thumbnail) indirmek çözer. | M |

### Okuma deneyimi

| Ne | Açıklama | Emek |
|---|---|---|
| ✅ 1.7.0 — **Kitap içinde arama** | Tüm bölümlerde kelime/ifade arama, sonuç listesi ve sonuca atlama. Bir kelimenin kitapta başka nerede geçtiğini görmek öğrenme için de faydalı ("Bu kelime kitapta 14 kez geçiyor"). | M |
| **Vurgulama ve not** | Cümle/paragraf seçip renkle işaretleme, not ekleme; İçindekiler çekmecesinde "Notlarım" sekmesi. | M |
| **Yer imleri** | Kitap içinde birden çok yer imi (şu an sadece "kaldığın yer" var). | S |
| **Kalan süre** | Alt çubukta "Bölümün bitmesine ~6 dk" (okuma hızı kullanıcının kendi geçmişinden hesaplanır). | S |
| **Sayfa modu** | Kaydırma yerine sütunlu sayfa çevirme (CSS columns). Bazı okurlar için daha az yorucu. | M |
| **Dipnot açılır pencereleri** | EPUB'daki `epub:type="noteref"` bağlantılarını sayfa atlamadan kart içinde göstermek. | S |
| **Ekran kenarına dokunarak sayfa** | Dokunmatik ekran / ileride Android için sol-sağ kenar dokunuşları. | S |
| **Klavye kısayolları listesi** | `?` tuşuyla kısayol kartı (Alt+←/→, Boşluk, tekrar ekranındaki ←/→ zaten var ama görünmüyor). | S |

### Kelime öğrenme

| Ne | Açıklama | Emek |
|---|---|---|
| **Daha iyi aralıklı tekrar** | Şu an 6 kutulu Leitner, iki düğme (`INTERVAL_DAYS = [0,0,1,3,7,16,40]`). "Zor / İyi / Kolay" üçlüsü ve FSRS benzeri bir algoritma, unutulan kelimeyi 1. kutuya atmak yerine bir-iki kutu geri almak daha verimli. Günlük yeni kelime / tekrar sınırı da eklenmeli. | M |
| **Ters yön ve yazma kartları** | Şu an sadece İngilizce → Türkçe. Türkçe → İngilizce, boşluk doldurma (kitaptaki cümlede kelime gizli) ve "dinle, yaz" kartları kalıcı öğrenmeyi artırır. | M |
| ✅ 1.7.0 — **Anki / CSV dışa aktarma** | Defteri `kelime; anlam; cümle; IPA` olarak CSV ya da Anki paketi (`.apkg`) yapmak. Telefonda Anki kullananlar için Android uygulaması bitene kadar köprü olur. | S |
| ✅ 1.7.0 — **Seviye etiketi (CEFR) sözlükten** | Masaüstünde sözlük modu `cefr: ''` döndürüyor; etiket sadece Claude'dan geliyor. `build_dict.py` zaten `wordfreq` kullanıyor: Zipf sıklığından kabaca A1–C2 tahmini `entries`'e yazılabilir. | S |
| **Kitabın zorluğu** | Kitap eklenince "kelimelerin %94'ü ilk 3000 kelimede → B1 için uygun" gibi bir özet; kitaplıkta kapak altında seviye rozeti. | M |
| **Bölüm öncesi kelime listesi** | Bölüme başlamadan önce, kullanıcının seviyesine göre bilinmesi muhtemel olmayan 10–15 kelimeyi gösterip tek dokunuşla deftere ekleme. | M |
| **Bilinen kelimeler** | "Bunu biliyorum" işareti: o kelime bir daha toplu listelerde ve zorluk hesabında bilinmeyen sayılmaz. | S |
| **Defterde sıralama ve filtre** | Kitaba göre, kutuya göre, ekleme tarihine göre; "sadece bu kitabın kelimeleri" ile tekrar. | S |

### İstatistik ve motivasyon

| Ne | Açıklama | Emek |
|---|---|---|
| **Günlük seri ve hedef** | "Bugün 20 dk okudun, 12 tekrar yaptın · 7 günlük seri". Kitaplık başındaki `vocab-strip`'e eklenebilir. | S |
| **İlerleme grafiği** | Haftalık okuma süresi, eklenen / öğrenilen kelime sayısı. Veriler zaten var (`last`, `reviews`, `box`), okuma süresinin kaydı eklenmeli. | M |

### Claude ile

| Ne | Açıklama | Emek |
|---|---|---|
| **Cümlenin dilbilgisi açıklaması** | Kelime kartında "Bu cümleyi açıkla": zaman, yapı, deyim, Türkçe açıklama. | S |
| **"Önceki bölümlerde ne olmuştu?"** | Kitaba uzun aradan sonra dönülünce kısa özet (sadece okunan kısımdan, sürprizi bozmadan). | M |
| **Bölüm hakkında sohbet** | Test yerine serbest soru-cevap ya da "bu bölümü 3 cümleyle İngilizce özetle, Claude düzeltsin" yazma alıştırması. | M |
| **Toplu çevirinin önbelleği** | Aynı cümlenin çevirisini / testi tekrar istememek için sonuçları kitap+bölüm bazında saklamak (API ücretini azaltır). | S |

### Ses

| Ne | Açıklama | Emek |
|---|---|---|
| **Doğal (neural) sesler** | WebView2'de Microsoft'un "Natural" sesleri çıkabiliyor; ses seçici eklenip en iyi ses varsayılan yapılmalı. | S |
| **Cümleyi tekrarla / gölgeleme** | Sesli okumada her cümleden sonra durup kullanıcının tekrar etmesini bekleyen mod. | M |

### Dağıtım (Windows)

| Ne | Açıklama | Emek |
|---|---|---|
| **Kurulum programı** | Inno Setup ile `Ayrac-Setup.exe`: Program Files / kullanıcı klasörüne kurulum, kısayollar, `.epub` dosya ilişkilendirmesi ("Birlikte aç" köprüsü `initial_file` hazır), kaldırma. Şu anki elle kopyalama adımı ortadan kalkar. | M |
| **Güncelleme kontrolü** | Açılışta yeni sürüm var mı diye bakıp (GitHub Releases vb.) indirip kuran küçük bir güncelleyici. | M |
| **İkinci açılışta dosyayı iletmek** | `focus_running_instance` ikinci açılışı kapatıyor ama ona verilen `.epub` kaybolur. Dosya yolu açık pencereye iletilmeli (adlandırılmış kanal ya da `WM_COPYDATA`). | S |
| **Kod imzalama** | İmzasız `.exe` SmartScreen uyarısı verir; başkalarına dağıtılacaksa gerekli. | S (ücretli) |

### Önerilen sıra

1. git + yedek + `variants()` hatası + sözlük testleri (bir oturumda biter, sonraki her şeyi güvenli kılar)
2. Kitap içinde arama, Anki/CSV dışa aktarma, sözlükten CEFR
3. Tekrar algoritması ve yeni kart türleri, günlük seri
4. Kurulum programı + dosya ilişkilendirme + güncelleyici
5. Android + eşitleme (yukarıdaki yol haritası)
