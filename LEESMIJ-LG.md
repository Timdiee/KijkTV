# KijkTV op je LG-tv (webOS) installeren

Eenmalig ongeveer 20 minuten. Je hebt nodig: de LG-tv, een computer op hetzelfde wifi-netwerk, en een e-mailadres.

## Stap 1 · Gratis LG-ontwikkelaarsaccount maken (op de computer)

1. Ga naar **https://webostv.developer.lge.com** en klik rechtsboven op **Sign In**.
2. Maak een LG-account aan (of log in met een bestaand LG-account) en bevestig je e-mailadres.

## Stap 2 · Ontwikkelaarsmodus aanzetten (op de tv)

1. Open op de LG de **LG Content Store** (Apps) en zoek **Developer Mode**. Installeer de app.
2. Open **Developer Mode** en log in met het account uit stap 1.
3. Zet **Dev Mode Status** aan. De tv start opnieuw op.
4. Open **Developer Mode** opnieuw en zet **Key Server** aan.
5. Laat dit scherm openstaan. Je ziet er het **IP-adres** van de tv en een **passphrase** (6 tekens).

## Stap 3 · webOS Dev Manager installeren (op de computer)

1. Ga naar **https://github.com/webosbrew/dev-manager-desktop/releases** en download onder de nieuwste versie het bestand voor jouw computer (Windows: het `.msi`- of `.exe`-bestand, Mac: het `.dmg`-bestand).
2. Installeer en open **webOS Dev Manager**.
3. Kies **Add device** (of het **+**-teken) en vul in:
   - **IP-adres**: het adres dat op de tv staat
   - **Passphrase**: de 6 tekens die op de tv staan (let op hoofdletters)
4. Klik **Add**. De tv verschijnt in de lijst.

## Stap 4 · KijkTV installeren

1. Download het LG-bestand: **https://github.com/Timdiee/KijkTV/releases/latest/download/KijkTV-LG.ipk**
2. Ga in Dev Manager naar **Apps** en klik op **Install** (of het **+**-teken).
3. Kies het bestand **KijkTV-LG.ipk**. Na een halve minuut staat **KijkTV** tussen je apps op de tv.
4. Open KijkTV en log in met je **server-link, gebruikersnaam en wachtwoord**.

Tip: typen met de afstandsbediening is lastig. Met de **LG ThinQ**-app op je telefoon kun je tekst typen en plakken op de tv.

## Belangrijk: elke 41 dagen verlengen

De ontwikkelaarsmodus blijft **1000 uur (± 41 dagen)** geldig. Open daarna (of eerder) op de tv de app **Developer Mode** en klik op **Extend**, of klik in Dev Manager op de verlengknop. Je ziet daar ook hoeveel tijd er nog over is.

Verloopt hij toch, dan verdwijnt KijkTV van de tv. Je installeert hem dan opnieuw met stap 4, en logt opnieuw in.

## Bijwerken naar een nieuwe versie

Download het `.ipk`-bestand opnieuw via dezelfde link en installeer het met Dev Manager. Je login, favorieten en geschiedenis blijven bewaard.

## Bediening (LG-afstandsbediening)

| Knop | Wat |
|---|---|
| Pijltjes + OK | Navigeren en kiezen (de Magic Remote-aanwijzer werkt ook) |
| OK lang ingedrukt | Zender aan/uit favorieten · item uit geschiedenis verwijderen · pauze bij live tv |
| Terug | Vorig scherm |
| Live tv: omhoog/omlaag of CH +/- | Andere zender |
| Live tv: 2x snel links | Programma vanaf het begin (bij zenders met terugkijken) |
| Film/serie: links/rechts | Spoelen (ingedrukt houden = sneller) |
| Film/serie: OK | Pauze |

## Goed om te weten

- **Boven en beneden tegelijk kijken?** Dan moet je IPTV-abonnement minstens **2 verbindingen** toestaan.
- Films, series, terugkijken en de programmagids werken met inloggen via server, gebruikersnaam en wachtwoord.
- Je gegevens blijven op de tv en gaan alleen naar de server van je eigen aanbieder.
