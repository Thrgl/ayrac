# Ayraç — sürüm geçmişi

Sürüm numarası `desktop/VERSION` dosyasında. Her derleme `desktop/releases/Ayrac-<sürüm>.exe` olarak saklanır.

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
