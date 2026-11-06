# Foto-Aufräumer

Android-App (ab Android 11), die deine Galerie aufräumt – komplett offline auf dem Handy.

## Funktionen
- **Duplikate:** findet Byte für Byte identische Kopien (MD5-Vergleich). Das älteste Original wird als „Beste“ markiert und behalten.
- **Ähnlich:** findet Serienbilder, bearbeitete Versionen und Kopien in anderer Größe (Bildvergleich per dHash). Empfindlichkeit: Streng / Normal / Locker.
- **Sortiert:** alle Fotos nach Monat (neueste/älteste), nach Dateigröße oder nach Album – ganze Monate/Alben mit einem Tipp auswählen.
- **Ein-Tipp-Auswahl:** „Alle Kopien auswählen (Beste behalten)“.
- **Sicheres Löschen:** wahlweise in den Papierkorb (30 Tage wiederherstellbar) oder endgültig. Android fragt vor dem Löschen immer noch einmal nach.
- Tippen = auswählen, lange drücken = Foto groß ansehen.

## APK bekommen (ohne Android Studio)
1. Auf github.com ein neues Repository anlegen (z. B. `foto-aufraeumer`).
2. Alle Dateien aus diesem Ordner hochladen – **inklusive** des Ordners `.github`.
3. GitHub baut die App automatisch (Reiter **Actions**, dauert ca. 5 Minuten).
4. Danach unter **Releases** die Datei `Foto-Aufraeumer.apk` am Handy herunterladen und öffnen.
5. Android fragt einmalig, ob du Apps aus dieser Quelle installieren willst → erlauben.

Bei jeder Änderung am Code entsteht automatisch eine neue Version, die sich über die alte installieren lässt.

## Selbst bauen
Android Studio öffnen → Ordner öffnen → ▶ Ausführen.
