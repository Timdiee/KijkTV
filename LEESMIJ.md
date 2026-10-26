# KijkTV 2.0: IPTV voor je Xiaomi TV Box

Gratis, zonder reclame, gemaakt voor de afstandsbediening.

**Wat erin zit**
- **Inloggen** met server-link + gebruikersnaam + wachtwoord van je aanbieder (of een M3U-link). Dit kan ook via je telefoon.
- **Live TV**: groepen, zenders met logo, een live voorbeeld, zoeken en favorieten (OK lang ingedrukt).
- **Programmagids**: in de lijst zie je wat er nu op is, en op het gidsscherm de dagplanning per zender.
- **Terugkijken (⟲)**: programma's van de afgelopen dagen, als je aanbieder dat aanbiedt.
- **Vanaf het begin**: ben je later ingeschakeld? Druk 2× snel op ◀ of kies het programma in de gids.
- **Films en Series**, met **verder kijken waar je was** en een volgende aflevering die vanzelf start.
- **Geschiedenis**: alles wat je keek, met filters, één item verwijderen (OK lang) of **alles wissen**.

---

## Zo krijg je de app op je Xiaomi (eenmalig, ±15 minuten)

Je hebt een computer of telefoon nodig, plus de TV-box.

### Deel A · De app laten maken op GitHub (gratis)

GitHub zet de code automatisch om in een installeerbare app (een APK).

1. Ga naar **github.com** en klik op **Sign up** om een gratis account te maken.
2. Klik rechtsboven op **+** en dan **New repository**.
   - Repository name: `KijkTV`
   - Kies **Public**. Dat is nodig zodat de box het bestand straks zonder inloggen kan downloaden. In de code staan geen gegevens van jou: je inlog zet je pas later op de TV.
   - Klik **Create repository**.
3. Klik op de blauwe link **uploading an existing file**.
4. Pak `KijkTV.zip` uit op je computer. Open de map `KijkTV` en sleep **alles wat erin zit** naar het uploadvak in je browser (de mappen `app` en `.github`, en de losse bestanden). Klik onderaan op **Commit changes**.
   - **Belangrijk:** de map `.github` moet erbij zitten. Op een Mac is die verborgen; druk in Finder op **Cmd + Shift + .** om hem te zien.
   - Lukt het niet? Klik dan in je repository op **Add file → Create new file**, typ als naam `.github/workflows/build.yml` en plak daar de inhoud van dat bestand in. Klik **Commit changes**.
5. Klik bovenaan op het tabblad **Actions**. Je ziet "Bouw KijkTV APK" draaien (een geel bolletje). Na ongeveer 5 minuten wordt dat een **groen vinkje ✅**.
6. Je app staat nu klaar op deze vaste link (vervang `JOUWNAAM` door je GitHub-gebruikersnaam):

   **`https://github.com/JOUWNAAM/KijkTV/releases/latest/download/KijkTV.apk`**

   Tip: controleer de link eerst op je computer. Als hij een download start, is hij goed.

### Deel B · Installeren op de Xiaomi TV Box

1. Open op de box de **Google Play Store**, zoek **Downloader** (het oranje icoon van AFTVnews) en installeer die app.
2. Geef Downloader toestemming om apps te installeren:
   **Instellingen → Apps → Beveiliging en beperkingen → Onbekende bronnen → Downloader aanzetten.**
   - Zie je dit niet? Ga naar **Instellingen → Systeem → Over** en klik 7× op **Android TV OS-build**. Daarna verschijnt de optie wel.
   - Downloader vraagt het soms ook zelf bij de eerste keer installeren. Kies dan **Instellingen** en zet de schakelaar aan.
3. Open **Downloader**. Typ in het vak de link uit stap A6 en druk op **Go**.
4. Na het downloaden kies je **Installeren** en daarna **Openen**.
5. Zet de app op je startscherm: ga naar **Apps**, houd OK ingedrukt op **KijkTV** en kies **Toevoegen aan favorieten**.

> Liever geen typwerk op de TV? Installeer **Send Files to TV** op je telefoon én op de box. Download op je telefoon de APK via de link uit A6 en stuur hem zo naar de box.

### Deel C · Inloggen

Bij de eerste keer openen zie je het inlogscherm.

- **Makkelijkst:** open op je telefoon (op hetzelfde wifi-netwerk) het adres dat rechts op de TV staat, bijvoorbeeld `http://192.168.1.23:8765`. Vul daar je **server-link, gebruikersnaam en wachtwoord** in en druk op **Inloggen op de TV**.
- Of typ ze met de afstandsbediening in op de TV.
- Heb je één lange link gekregen met `username=` en `password=` erin? Die mag je ook gewoon plakken; de app herkent hem.

De zenders worden geladen. De programmagids komt er op de achtergrond bij; de eerste keer kan dat een minuutje duren.

---

## Bediening

| Waar | Knop | Wat |
|---|---|---|
| Overzicht | OK / OK lang | Kijken / favoriet aan of uit |
| Overzicht | ▲ helemaal boven | Menubalk: Gids, Films, Series, Geschiedenis, Account |
| Live kijken | ▲ ▼ | Vorige / volgende zender |
| Live kijken | ◀ 2× snel | Huidig programma vanaf het begin (bij ⟲-zenders) |
| Live kijken | OK | Wat er nu en straks op is |
| Gids | OK op programma | Kijken, terugkijken of vanaf het begin |
| Film / serie / terugkijken | ◀ ▶ (ingedrukt = sneller) | Spoelen |
| Film / serie / terugkijken | OK | Pauze |
| Geschiedenis | OK lang | Eén item verwijderen |
| Geschiedenis | 🗑 knop | Alles wissen |

## Updates

Pas je iets aan in de bestanden op GitHub, dan bouwt GitHub automatisch een nieuwe versie op dezelfde link. Download hem opnieuw met Downloader; hij installeert over de oude heen en je login, favorieten en geschiedenis blijven bewaard.

## Goed om te weten

- Terugkijken, films en series werken alleen als je aanbieder dat levert en je inlogt met gebruikersnaam en wachtwoord.
- Of een zender werkt, hangt af van de stream bij je aanbieder. Speelt hij niet af? Druk OK om het opnieuw te proberen.
- Je gegevens blijven op de box. De app stuurt ze alleen naar de server van je eigen aanbieder.
