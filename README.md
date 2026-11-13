# ⚔ Event — Otomatik 1v1 Turnuva Sistemi

Paper/Spigot **1.20.1** tabanlı, **1.20.1 ve üstü tüm sürümlerde** (1.20.x, 1.21.x) çalışan, her gün belirlenen saatte otomatik açılan 1v1 eleme turnuvası eklentisi.

**Geliştirici:** Jestron · **Java:** 17+ · **Bağımlılık:** Yok (WorldEdit / FastAsyncWorldEdit isteğe bağlı)

---

## Nasıl çalışır?

1. Yönetici bir event saati oluşturur (örn. `20:00`). Event **her gün** o saatte (varsayılan **Europe/Istanbul**) otomatik açılır.
2. Açılınca herkese tıklanabilir duyuru gider. Oyuncular **katılım süresi** boyunca (varsayılan **5 dakika**) `/event` yazarak event alanına ışınlanır.
3. Katılırken oyuncunun **tüm envanteri, zırhı, sol eli, XP'si, canı, açlığı, oyun modu ve efektleri** SQLite veritabanına yedeklenir. Çökme, çıkış veya sunucu kapanmasında bile kaybolmaz.
4. Süre bitince alandaki oyuncular **rastgele eşleştirilir** ve o event saatine ait **boş odalara** gönderilir. Her maçta iki oyuncu da yöneticinin kaydettiği **kitle, slot slot birebir aynı** şekilde başlar.
5. **Kazanan** bekleme alanına döner ve sonraki turu bekler. **Kaybeden** eşyalarını geri alır ve `/event sethub` noktasına gönderilir.
6. Bir turda herkes maçını bitirince kalanlar arasında yeni tur başlar. **Tek sayıda oyuncu** varsa biri o turu **bay** geçer (aynı kişi üst üste bay geçmez).
7. Son kalan **şampiyon** olur. Sonuçlar herkese chat'te gösterilir:

```
🏆 1v1 EVENT SONUÇLARI (20:00)
  1. Jestron (6 kill)
  2. Ahmet (5 kill)
  3. Mehmet (3 kill)
```

8. Sıralamaya göre ödül komutları çalıştırılır, kalan herkesin eşyaları iade edilir.

## Kurulum (adım adım)

1. `Event-1.0.0.jar` dosyasını `plugins` klasörüne at, sunucuyu başlat.
2. `/event oluştur 20:00` — event saatini oluştur.
3. WorldEdit/FAWE wand ile (yoksa `/event wand`) bekleme alanının iki köşesini seç → `/event alan oluştur 20:00`
4. Alanın içinde dur, bakış yönünü ayarla → `/event setspawn 20:00`
5. Elenenlerin gideceği yerde dur → `/event sethub`
6. Her 1v1 odası için iki köşeyi seç → `/event oda oluştur oda1 20:00` (spawnlar otomatik, karşılıklı ayarlanır)
   - İstersen elle ayarla: `/event oda spawn oda1 20:00 1` ve `... 2`
7. Kit envanterini hazırla (zırh, sol el, hotbar dahil) → `/event kit ayarla 20:00`
8. `/event liste` ile her şeyin ✔ olduğunu kontrol et. Test için: `/event başlat 20:00`

Birden fazla event saati oluşturabilirsin. Her saatin **kendi alanı, spawnı, kiti ve odaları** olur.

## Komutlar

| Komut | Açıklama |
|---|---|
| `/event` | Açık evente katıl |
| `/event ayril` | Eventten ayrıl (eşyalar iade) |
| `/event bilgi` | Event durumu / sonraki event |
| `/event gecmis` | Son 5 event sonucu |
| `/event oluştur <saat>` | Event saati oluştur |
| `/event sil <saat>` | Event saatini ve odalarını sil |
| `/event liste` | Tüm event saatleri ve eksikleri |
| `/event alan oluştur\|sil <saat>` | Bekleme alanı |
| `/event setspawn [saat]` | `/event` yazınca gidilecek nokta (tam blok + bakış yönü) |
| `/event sethub` | Elenen / biten oyuncuların gideceği nokta |
| `/event oda oluştur <isim> <saat>` | Seçimi 1v1 odası yap |
| `/event oda sil <isim> <saat>` | Odayı sil |
| `/event oda spawn <isim> <saat> <1\|2>` | Oda spawnını elle ayarla |
| `/event oda liste [saat]` | Odaları listele |
| `/event kit ayarla\|al\|sil <saat>` | Kiti envanterinden kaydet / yükle / sil |
| `/event başlat <saat>` | Eventi beklemeden hemen aç |
| `/event durdur` | Aktif eventi durdur (herkese iade) |
| `/event wand` | WorldEdit yoksa seçim çubuğu |
| `/event reload` | Ayarları yenile |

Türkçe karakterli ve karaktersiz yazımın ikisi de çalışır (`oluştur` / `olustur`, `başlat` / `baslat`). Tüm argümanlarda tab tamamlama vardır.

**Yetkiler:** `event.use` (herkes), `event.admin` (op)

## Güvenlik ve koruma

- **Eşya güvenliği:** Yedek SQLite'ta tutulur. Oyuncu çıkarsa, sunucu çökerse veya `/reload` yapılırsa, oyuncu tekrar girince eşyaları otomatik iade edilir.
- **Kit sızıntısı yok:** Maç bitince kit silinir. Ölünce kit düşmez. Maçta sandık / ender sandığı açılamaz. Yere eşya atma varsayılan olarak kapalıdır.
- **Oda onarımı:** Maçta koyulan / kırılan bloklar, akan su / lav, oluşan obsidyen / taş, ateş kaydedilir ve maç bitince oda **birebir eski haline döner**. Yerdeki eşyalar, oklar ve XP temizlenir.
- **Hile koruması:** Geri sayımda hareket / vuruş / eşya kullanımı yoktur. Odadan ve alandan dışarı çıkılamaz, dışarı ışınlanılamaz. Event sırasında yalnızca izinli komutlar kullanılabilir. Dışarıdan biri katılımcılara vuramaz.
- **WorldGuard uyumu:** `izinleri-zorla: true` ise odalardaki PvP / blok koyma, başka eklentiler engellese bile maç içinde açılır.
- **Süre sınırı:** Maç `mac-max-sure` saniyeyi aşarsa canı yüksek olan kazanır.

## Yapılandırma

Tüm süreler, kurallar, ödüller, sesler ve **bütün mesajlar** `config.yml` üzerinden değiştirilebilir. Event tanımları `data.yml`, yedekler ve geçmiş `event.db` dosyasında tutulur.

## Derleme

```bash
mvn clean package
# target/Event-1.0.0.jar
```

---
© Jestron — AvenorCraft
