# Ayraç

İngilizce öğrenmek için EPUB okuyucu. İngilizce kitap okursun; bilmediğin kelimeye dokununca Türkçe anlamı açılır, kelimeyi defterine eklersin, sonra aralıklı tekrarla çalışırsın.

Windows ve Android için. Sözlük çevrimdışı çalışır, internet ya da hesap gerekmez.

> **Claude ile yapılmıştır.** Bu uygulamanın kodu baştan sona [Claude](https://claude.ai) (Anthropic) ile birlikte yazıldı · Tahir Gül

## Neler var

**Okuma**
- EPUB ve TXT kitaplar; kitaplık, içindekiler, kaldığın yerden devam
- Kaydırarak ya da sayfa sayfa okuma, temalar (açık, sepya, gece), yazı tipi ve boyutu
- Kitapta arama, yer imleri, vurgulama ve not, dipnotlar
- Project Gutenberg'den ücretsiz kitap bulup ekleme

**Kelime öğrenme**
- Kelimeye dokununca çevrimdışı İngilizce-Türkçe sözlük: anlam grupları, okunuş (IPA), kök kelime, phrasal verb ve kalıplar (`took off`, `take it off`)
- Kelime defteri ve Anki tarzı aralıklı tekrar (Tekrar / Zor / İyi / Kolay)
- Kart türleri: İngilizce → Türkçe, Türkçe → İngilizce, cümlede boşluk doldurma, dinle-yaz
- Kitabın tahmini seviyesi (≈A1–C2) ve bölüm başında zor kelimeler
- Defteri CSV olarak dışa aktarma (Excel, Anki)

**Ses**
- Sesli okuma: Windows / Android sesleri ya da cihazda çalışan yapay zekâ sesleri (Piper)
- Gölgeleme: her cümleden sonra durur, sen tekrar edersin

**Takip**
- Günlük okuma hedefi, seri, ilerleme grafiği
- Telefonda günlük hatırlatma
- Yedek al / yedekten yükle (bilgisayar ve telefon arasında aynı biçim)

## Klasörler

| Klasör | İçinde |
|---|---|
| `ayrac.html` | Okuyucunun tamamı (HTML + CSS + JS); bilgisayar ve telefon aynı sayfayı kullanır |
| `desktop/` | Windows uygulaması (Python, pywebview), sözlük üretici, kurulum programı, testler |
| `android/` | Android uygulaması (Java, WebView) |

Derleme, test ve mimari: [GELISTIRME.md](GELISTIRME.md). Sürüm geçmişi: [CHANGELOG.md](CHANGELOG.md).

## Kaynaklar ve lisanslar

- Sözlük: [Wiktionary](https://www.wiktionary.org/) verisi ([kaikki.org](https://kaikki.org/) / wiktextract), **CC BY-SA 4.0**
- Kelime sıklığı: [wordfreq](https://github.com/rspeer/wordfreq)
- Yapay zekâ sesleri: [Piper](https://github.com/rhasspy/piper) modelleri, [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) ile; her sesin kendi lisansı vardır
- Ücretsiz kitaplar: [Project Gutenberg](https://www.gutenberg.org/)
