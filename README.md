# ⚔ Event — Otomatik 1v1 Turnuva

Paper/Spigot **1.20.1+** (1.20.x, 1.21.x) · Java 17 · Bağımlılık yok (WorldEdit/FAWE isteğe bağlı) · **Jestron**

## Akış

1. Event saatinden 60, 30, 10, 5 ve 1 dakika önce bütün oyunculara title ile hatırlatma yapılır (`hatirlatma-dakikalari`).
   Event her gün ayarlanan saatte (Europe/Istanbul) açılır. Katılım süresi varsayılan 5 dakikadır.
2. Oyuncu `/event` yazar. **Envanteri tamamen boş olmalıdır** (zırh ve sol el dahil). Boş değilse chat'te uyarılır.
3. Bekleme alanında eşya alınamaz, atılamaz, craft yapılamaz. Sistem dışından gelen eşyalar anında silinir.
4. Süre bitince oyuncular rastgele eşleşir. Boş odaların hepsi aynı anda doldurulur (1. oyuncu **Spawn1**, 2. oyuncu **Spawn2**), herkesin ekranında 3-2-1 sayılır ve maçlar **aynı anda** başlar.
5. Oda yetmeyen eşleşmeler bekler. O dalganın bütün maçları bitince sıradakiler toplanır ve yine aynı anda başlatılır.
6. Kazananın kiti hemen silinir, sonraki eşleşmede kit yeniden eksiksiz verilir. Kazanan, o event saatinin `/event setspawn` noktasına (bekleme alanı) döner ve eventte kalır. Kaybedenin kiti silinir, `/event sethub` noktasına gönderilir ve eventten çıkarılır.
7. Tek sayı kalırsa biri o turu bekler (aynı kişi üst üste beklemez).
8. Kontenjan doluysa yeni katılım kabul edilmez.
9. Sonuncu kalan şampiyon olur, chat'te ilk 3 gösterilir: `1. Jestron (6 kill)`

Sadece `/event` ile katılanlar katılımcıdır. Alana başka yolla gelen biri (op dahil) eventte sayılmaz, katılımcılara vuramaz, onlardan hasar almaz.

## Kurulum

1. `/event oluştur 20:00`
2. Bekleme alanını seç (WorldEdit/FAWE wand ya da `/event wand`) → `/event alan oluştur 20:00 16` (16 = en fazla kişi, yazılmazsa sınırsız)
3. Alanın içinde dur → `/event setspawn 20:00`
4. Elenenlerin gideceği yerde dur → `/event sethub`
5. Odayı seç → `/event oda oluştur oda1 20:00`
6. Odanın içinde dur → `/event oda setspawn1 oda1`, sonra öbür uçta `/event oda setspawn2 oda1`
   (bloğun tam ortası kaydedilir, bakış yönü en yakın ana yöne hizalanır)
7. Kiti envanterine diz → `/event kit ayarla 20:00`
8. `/event liste` ile kontrol et, test için `/event başlat 20:00`

## Komutlar

| Komut | İş |
|---|---|
| `/event` · `/event ayril` | Katıl · ayrıl |
| `/event bilgi` · `/event gecmis` | Durum · son sonuçlar |
| `/event oluştur\|sil <saat>` | Event saati |
| `/event alan oluştur <saat> [maks]` · `/event alan sil <saat>` | Bekleme alanı + kontenjan |
| `/event maks <saat> <kişi>` | Kontenjanı değiştir (0 = sınırsız) |
| `/event setspawn [saat]` · `/event sethub` | Giriş noktası · çıkış noktası |
| `/event oda oluştur <isim> <saat>` | Oda (isimler benzersiz) |
| `/event oda setspawn1\|setspawn2 <oda>` | Oda spawnları (odanın içinde) |
| `/event oda sil <oda>` · `/event oda liste [saat]` | Sil · listele |
| `/event kit ayarla\|al\|sil <saat>` | Kit (slot slot) |
| `/event başlat <saat>` · `/event durdur` | Hemen aç · durdur |
| `/event wand` · `/event reload` | Seçim çubuğu · yenile |

Yetkiler: `event.use` (herkes), `event.admin` (op). Tüm argümanlarda tab tamamlama vardır, Türkçe karakterli ve karaktersiz yazım çalışır.

## Koruma

- Kit eşyaları görünmez bir etiket taşır. Eventten çıkan, sızan ya da sunucu çökmesinden sonra kalan kit eşyası girişte silinir.
- Maçta koyulan/kırılan bloklar, su, lav, ateş kaydedilir. Maç bitince oda birebir eski haline döner, yerdeki eşya/ok/xp temizlenir.
- Geri sayımda hareket, vuruş ve eşya kullanımı yoktur. Alandan ve odadan çıkılamaz, dışarı ışınlanılamaz.
- Eventte sandık açılamaz, sadece izinli komutlar kullanılabilir.
- Maç süre sınırı (`mac-max-sure`) dolunca canı yüksek olan kazanır.

## Performans

- Ayarlar ve mesajlar önbellekte tutulur; hareket, hasar ve blok olaylarında config okunmaz.
- Event yokken bütün dinleyiciler tek bir kontrolle çıkar.
- Zamanlama dakikada bir kontrol edilir, kit event başına bir kez hazırlanır.
- Dosyaya yazma arka planda tek bir thread'de yapılır, sunucu donmaz. Veritabanı yoktur.

## Derleme

```bash
mvn clean package   # target/Event-<sürüm>.jar
```
