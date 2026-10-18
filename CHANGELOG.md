# Ayraç — sürüm geçmişi

Sürüm numarası `desktop/VERSION` dosyasında. Her derleme `desktop/releases/Ayrac-<sürüm>.exe` olarak saklanır.

## 2.4.1 — 2026-10-18
- Başlangıç ekranında "Ayarlar" (genel ayarlar): uygulama teması (Sistem / Açık / Koyu), açılışta ne açılsın (kitaplık ya da son okuduğun kitap), seviye, günlük okuma hedefi (5 dakikalık adımlarla 5–240 dk), günde yeni kelime, okuma hızı.
- Okuma hızı: son 30 günün okumasından ölçülen hız gösterilir; istersen elle ayarlarsın ya da "Hızımı ölç" ile 1 dakikalık testi yaparsın (kısa bir metni okuyup "Bitirdim"e basarsın, "Bu hızı kullan"). Bölümde kalan süre ("bölümde ~11 dk") bu hıza göre hesaplanır.
- Okuma ayarları ("Aa") sadeleşti: yalnız okumaya ait olanlar (görünüm, sesli okuma, sayfa çevirme); genel ayarlar başlangıç ekranına taşındı, aynı ayar iki yerde yok. Üç açılır bölüm: Görünüm, İngilizce çalışma, Okuma; başlığa dokununca aşağı doğru açılır, hangilerinin açık olduğu hatırlanır.
- Animasyonlar: ayarlar, defter, içindekiler ve arama panelleri kayarak; kelime kartı, pencereler ve arka plan karartması yumuşakça açılır. (Cihazda "hareketi azalt" açıksa kapalı.)
- Telefonda kelime kartı küçüldü: ekranın en fazla üçte biri kadar; ilk iki anlam grubu görünür, kalanı "Diğer anlamlar" altında; "Deftere ekle" düğmeleri kartın altında hep görünür.
- Okuma temasındaki "Sistem" seçeneğinin adı "Uygulama" oldu (uygulama temasını izler).
- Bilgisayar ve telefon için. (Parmakla kaydırarak sayfa çevirme yine yalnız telefonda.)

## 2.4.0 — 2026-10-18 (yalnız Android)
- Telefonda sayfa çevirme artık kaydırmayla: parmağını sağdan sola kaydır, sonraki sayfa; soldan sağa, önceki sayfa. Hem "Kaydırma" hem "Sayfa sayfa" düzeninde çalışır. Ayarlar → Okuma → "Parmakla sağa / sola kaydırınca sayfa çevir".
- Telefonda kenara dokunarak sayfa çevirme kaldırıldı: ekranın kenarına yakın bir kelimeye dokununca anlamı yerine sayfa değişiyordu. Dokunuş artık her zaman kelime içindir.
- Bilgisayar sürümü değişmedi (2.3.1'de kalır): kenar dokunuşu ayarı, sayfa modunda kaydırma ve fare tekerleği, ok tuşları aynı.

## 2.3.1 — 2026-10-18
- Düzeltme: telefonda (ve bilgisayarda) sayfanın üst ve yan kenarlarında açık renkli ince bir şerit kalıyordu; tema değişince o şerit değişmiyordu. Sebep sayfanın varsayılan 8 piksellik kenar boşluğuydu. Artık sayfa ekranı tam kaplar ve kenarlar okuma temasının rengini alır. Aynı boşluk alt çubuğun en altını da biraz kesiyordu, o da düzeldi.

## 2.3.0 — 2026-10-17
- Ses seçimi (Ayarlar → Ses): bilgisayardaki İngilizce sesler listelenir; Microsoft'un doğal ("Natural") sesleri başta ve "doğal" diye işaretli. "Otomatik" en iyi sesi seçer; "Dene" ile dinlenir. (Telefonda Android'in kendi sesi kullanılır.)
- Gölgeleme (Ayarlar → Dinlerken tekrar et): sesli okumada her cümleden sonra durur, sen tekrar edersin. "Bekle, devam et": cümlenin uzunluğu kadar bekleyip devam eder (çubuk kalan süreyi gösterir). "Ben geçeyim": "Devam"a basana kadar bekler. "Tekrar dinle" aynı cümleyi yeniden okur. Klavye: Enter devam, R tekrar.

## 2.2.0 — 2026-10-17
- Kitabın seviyesi: kitap açılınca sözlükteki kelime sıklıklarından tahmini seviye hesaplanır (kelimelerin %95'ini kapsayan seviye). Kitaplıkta kapakta "≈B1" rozeti; İçindekiler panelinde "seviyene uygun / biraz zorlayıcı / zor" ve senin seviyendeki kelimelerin oranı.
- Bölüm başında zor kelimeler: okunmamış bir bölüme başlarken, seviyenin üstündeki en sık 12 kelime anlamlarıyla gösterilir; "Deftere", "Biliyorum" ya da "Kalanların hepsini deftere ekle". Ayarlar → "Bölüm başında zor kelimeler" ile kapatılabilir.
- "Biliyorum": kelime kartında yeni düğme. Bilinen kelimeler zor kelime listelerinde bir daha çıkmaz.
- Defterde sıralama (en yeni, en eski, A→Z, tekrar zamanı, en zor önce) ve filtre (kitap, durum: yeni / öğreniliyor / iyi biliniyor / tekrarı gelmiş).
- Tekrarda "Sadece bu kitap": açık kitabın (ya da filtrede seçilen kitabın) kelimeleriyle tekrar.
- İlerleme grafiği (kitaplıkta "İlerleme"): 7 gün, 30 gün ya da 12 hafta için okuma süresi, eklenen kelime ve tekrar sayısı.
- Yazı rengi ayarı (Ayarlar → Yazı rengi): temaya göre ya da beyaz, krem, kehribar, gri, kahve, siyah, ya da istediğin renk.
- Android: ekranın kenarları, üstteki durum çubuğu ve alttaki gezinme çubuğu artık sayfanın rengini alır (önce gece temasında da açık gri kalıyordu).
- Arayüzün büyük harfli etiketlerinde "İ" doğru yazılır.

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
