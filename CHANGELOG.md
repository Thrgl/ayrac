# Ayraç — sürüm geçmişi

Sürüm numarası `desktop/VERSION` dosyasında. Her derleme `desktop/releases/Ayrac-<sürüm>.exe` olarak saklanır.

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
