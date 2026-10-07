# Cheto Gallery — Google Play listing

Package: `ai.cheto.gallery` · Account: Lega Digital · Track: **Internal testing** (team only, for now)

## Store listing (Turkish)

**App name** (max 30): `Cheto Gallery`

**Short description** (max 80, now 71):
Instagram, Messenger ve WhatsApp'tan gelen videoları tek akışta incele.

**Full description** (max 4000):

Cheto Gallery, müşterilerinizin Instagram, Messenger ve WhatsApp mesajlarıyla gönderdiği videoları
tam ekran, tek bir akışta incelemenizi sağlar.

• Tek akış: yukarı kaydırın, sıradaki videoya ya da sıradaki müşteriye geçin.
• Kanal etiketi: Messenger ve WhatsApp'tan gelenler adın yanında işaretlenir.
• Yüz ve plaka bulanıklaştırma, filigran ve küfür filtresi (bip) ile dışa aktarın.
• Telefona kaydedin, Instagram Hikaye'ye gönderin ya da Reels için hazırlayın.
• Yapay zekâ ile video için haber metni (caption) üretin.

Uygulama, Cheto hesabınızla çalışır; giriş için size verilen kullanıcı adı ve şifre gerekir.

## Graphics (this folder)

| Play field | File |
| --- | --- |
| App icon, 512×512 | `icon-512.png` |
| Feature graphic, 1024×500 | `feature-graphic-1024x500.png` |
| Phone screenshots (2–8, 1080×2160) | `screenshots/*.png` |

The screenshots are real app screens taken on an emulator against a local stand-in server with
generated demo videos and made-up customer names. **No customer data is in them** — keep it that
way when they are redone.

## Play Console checklist (internal testing)

1. *Create app* → name `Cheto Gallery`, default language Turkish, App, Free.
2. *Testing → Internal testing → Testers*: add a list with the team's Google account e-mails.
3. *Create new release* → accept **Play App Signing**, upload `app/build/outputs/bundle/release/app-release.aab`
   (build it with `./gradlew :app:bundleRelease`; it is signed with the upload key, see README).
4. Fill the declarations Play asks for before it lets a release roll out:
   - *App access*: login is required. Give Play a test username/password **for a Cheto account that
     holds no real customer data** (only needed for review, not for internal testing).
   - *Data safety*: the app sends the user's login and the videos it shows to the Cheto server
     (dmrandevu.com); it keeps the session cookie on the device, not the password. Nothing is sold or
     shared for ads.
   - *Privacy policy URL*: `https://dmrandevu.com/privacy`
   - *Content rating*, *Target audience* (18+), *News app* (no), *Ads* (no).
5. Roll out. Testers install from the opt-in link shown under *Testers*.

Going to **production** later is a separate step: a new personal developer account needs a 14-day
closed test first; an organisation account does not. Check which one the Lega Digital account is.
