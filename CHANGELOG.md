# Ayraç — sürüm geçmişi

Sürüm numarası `desktop/VERSION` dosyasında. Her derleme `desktop/releases/Ayrac-<sürüm>.exe` olarak saklanır.

## 2.1.0 — 2026-10-17
- Yer imleri: üst çubuktaki "Yer imi" düğmesi ya da B tuşu. Bir kitapta istediğin kadar yer imi.
- Vurgulama ve not: metni seç, dört renkten birini ya da "Not ekle"yi seç. Vurgulu bir kelimeye dokununca notu görür, düzenler ya da vurguyu kaldırırsın. Yazı boyu ya da sayfa düzeni değişse de vurgular yerinde kalır.
- İçindekiler panelinde "Notlar ve yer imleri" sekmesi: hepsi sırayla, dokununca o yere gider; "Notları dışa aktar" ile .txt dosyası.
- Sayfa modu (Ayarlar → Okuma → Sayfa): kaydırma yerine sayfa çevirme; geniş pencerede iki sütun. Ok tuşları, Boşluk, fare tekerleği ve kaydırma hareketiyle sayfa değişir; alt çubukta "sayfa 3/14".
- Kenara dokunarak sayfa çevirme (Ayarlar → Okuma; dokunmatik ekranlar için).
- Dipnotlar sayfadan ayrılmadan küçük bir kartta açılır; "Dipnota git" ile yerine de gidilebilir.
- Kalan süre artık senin okuma hızından hesaplanır (son 30 günde okuduğun kelime / süre).
- Kısayol listesi: ? tuşu ya da Ayarlar → "Klavye kısayolları".
- Masaüstü: ayarlar ve defter `data.json` yerine `data.sqlite`'ta (her kayıtta bütün dosya yeniden yazılmaz; eski dosya ilk açılışta taşınır, `.bak` olarak saklanır). Kitaplar ve kapaklar uygulamaya yerel bir adresten dosya olarak gelir; büyük kitaplar ve kalabalık kitaplık daha hızlı açılır.

## 2.0.0 — 2026-10-17
- Android uygulaması: `android\releases\Ayrac-2.0.0.apk` (Android 8.0+). Masaüstündeki her şey telefonda: çevrimdışı sözlük, kalıplar, toplu seçim, defter ve tekrar kartları, kitapta arama, sesli okuma (Android'in sesleriyle), okundu/okunmadı, hedef ve seri, yedek al/yükle, CSV dışa aktarma, isteğe bağlı Claude.
- .epub dosyaları telefonda "Birlikte aç → Ayraç" ve paylaş menüsüyle açılır; geri tuşu önce kartı/paneli kapatır, sonra kitaplığa döner.
- Yedekler masaüstü ve telefon arasında aynı biçimde: bir cihazda "Yedek al", ötekinde "Yedekten yükle".
- Masaüstü: metinler cihaza göre ("bu bilgisayarda" / "bu telefonda").

## 1.9.0 — 2026-10-17
- Kurulum programı: `desktop\releases\Ayrac-Setup-<sürüm>.exe` (Inno Setup, yönetici izni gerekmez). Başlat menüsü ve masaüstü kısayolu, "Program ekle/kaldır" kaydı, kaldırınca kitaplar ve defter silinmez.
- .epub dosyaları için "Birlikte aç → Ayraç".
- Ayraç açıkken bir .epub açılırsa kitap açık pencerede açılır (önceden kayboluyordu).
- Güncelleme denetimi: açılışta yeni sürüm varsa kitaplığın üstünde "Güncelle" çıkar; indirip kurar ve yeniden açar. Kaynak, kurulumun yazdığı `releases\latest.json` (geliştirici panelinden görülür).
- Düzeltme ("kitaplar gitti"): Ayraç, paketlenmiş bir uygulamanın içinden (örn. Claude'un terminali) başlatıldığında Windows veri klasörünü o uygulamanın gizli kopyasına yönlendiriyordu; iki ayrı kitaplık oluşuyordu. Ayraç artık bunu fark edip kendini paketin dışında yeniden başlatır. İki kitaplık birleştirildi (yedekler: `Belgeler\Ayrac-veri`).

## 1.8.0 — 2026-10-17
- Yeni tekrar sistemi (Anki tarzı): Tekrar / Zor / İyi / Kolay; her düğmede sıradaki tekrarın ne zaman olacağı yazar. Unutulan kelime en başa dönmez, aralığı yarıya iner ve 10 dk sonra yeniden sorulur. Eski kutu (Leitner) bilgisi otomatik çevrilir. Klavye: 1–4, ←/→, Enter.
- Günlük yeni kelime sınırı (Ayarlar → Günde yeni kelime: 10 / 20 / 40 / sınırsız).
- Yeni kart türleri: Türkçe → İngilizce, kitaptaki cümlede boşluk doldurma, dinle-yaz; "Karışık" modda yeni kelime önce İng → Tr sorulur, sonra türler dönüşümlü gelir. Yazmalı kartlarda cevap kontrol edilir ve uygun düğme önerilir.
- Günlük hedef ve seri: okuma süresi ölçülür (kitap açık, pencere görünür, son 2 dk içinde kaydırma/dokunma ya da sesli okuma). Kitaplıkta "Bugün X / 15 dk" çubuğu ve "N günlük seri" (5+ dk okuma ya da 10+ kart tekrarı olan günler). Hedef Ayarlar'dan seçilir.
- "İyi biliniyor" artık tekrar aralığı 21 günü geçen kelimeleri sayar.

## 1.7.0 — 2026-10-17
- Kitapta ara (üst çubuktaki "Ara" ya da Ctrl+F): bütün bölümlerde arama, "tam kelime" seçeneği, bölüme göre gruplu sonuçlar; sonuca tıklayınca o yere gider ve eşleşmeyi vurgular.
- Kelime kartında "Kitapta 15 kez geçiyor · Hepsini göster".
- Kelime defterini dışa aktar: Excel ve Anki için CSV (noktalı virgüllü, UTF-8): kelime, anlam, cümle, okunuş, tür, seviye, tanım, örnek, kitap, tarih.
- Sözlükten seviye tahmini (≈A1–C2): kelimenin ne kadar sık kullanıldığına göre (wordfreq). Sözlüğe `freq` tablosu eklendi; sözlük yeniden oluşturuldu.

## 1.6.0 — 2026-10-17
- Yedek al / Yedekten yükle (kitaplığın altında). Masaüstü: kitaplar, defter, okuma yerleri ve ayarlar tek ZIP dosyasında; yüklemeden önce mevcut hâl otomatik yedeklenir (`%APPDATA%\Ayrac\backups`). Web: defter, okuma yerleri ve ayarlar JSON olarak.
- Düzeltme: defterdeki kelimelerin altını çizerken `-er/-est/-ly` ekleri artık atılmıyor (önceden "on" kayıtlıyken "only", "ear" kayıtlıyken "early" de çiziliyordu); kısalan kök en az 3 harf olmalı.
- Otomatik testler: `desktop\tests` (sözlük araması ve yedekleme, pytest).
- Proje git deposu oldu.

## 1.5.0 — 2026-10-17
- Geliştirici modu: kitaplığın altındaki sürüm numarasına 5 kez dokun (ya da Ctrl+Shift+D). Sürüm, veri klasörü, günlük, kitap sayısı ve sözlük yolu görünür; inceleyici (sağ tık → İncele) açılır.
- Günlük dosyası: `%APPDATA%\Ayrac\logs\ayrac.log`. Her açılış, kitaplık okuma ve hatalar kaydedilir.
- Sürüm numarası uygulamada görünür.

## 1.4.0 — 2026-10-17
- Kitaplık açılırken "yükleniyor" yazısı; bağlantı geç kurulursa 30 sn bekler, kurulamazsa açık hata gösterir.
- Phrasal verb'ler geçmiş zamanda ("took off"), ikinci kelimeden ("off") ve araya kelime girince ("take it off") bulunur.

## 1.3.0 — 2026-10-17
- Çevrimdışı İngilizce-Türkçe sözlük (Wiktionary, CC BY-SA 4.0): anlam grupları, Türkçe tanımlar, IPA, kök kelime, kalıplar.
- "Cambridge'de aç": Cambridge Dictionary sayfası uygulama içinde.
- Toplu kelime seçimi ("Kelime seç").
- Okundu / okunmadı: kitaplık bölümleri, İçindekiler filtresi.

## 1.2.0 — 2026-10-16
- Windows masaüstü uygulaması (pywebview); kitaplar ve defter `%APPDATA%\Ayrac` içinde.
- Claude için API anahtarı ayarı; tek pencere koruması.

## 1.1.0 — 2026-10-16
- İngilizce çalışma: kelimeye dokununca anlam, cümle çevirisi, kelime defteri, aralıklı tekrar, sesli okuma, bölüm sonu testi.

## 1.0.0 — 2026-10-16
- EPUB okuyucu: kitaplık, içindekiler, temalar, kaldığın yerden devam.
