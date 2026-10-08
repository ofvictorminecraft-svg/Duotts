# DUOTTS BLE Inspector

Niezależne narzędzie **diagnostyczne tylko do odczytu**, przeznaczone do analizy komunikacji aplikacji DUOTTS z rowerem przez Bluetooth (m.in. DUOTTS C29). Nie jest oficjalną aplikacją producenta.

## APK

[**Pobierz APK – GitHub Releases**](../../releases/latest).

Jeśli publikacja Releases jest zablokowana uprawnieniami repozytorium, APK znajdziesz w **Actions → Build and publish Android APK → Artifacts**, w archiwum DUOTTS-BLE-Inspector-APK. APK jest wersją debug, przeznaczoną do testów, a nie do produkcyjnej dystrybucji.

## Co działa

- Wyszukiwanie urządzeń Bluetooth LE z nazwą, adresem i siłą sygnału RSSI.
- Odczyt UUID usług i charakterystyk GATT (bez zapisu do sterownika).
- Import binarnych plików btsnoop_hci.log lub plików ZIP zawierających taki log.
- Dekodowanie pakietów ATT Write Request / Write Command i powiadomień BLE; identyfikacja HEX, attribute handle oraz UUID (jeśli w logu było discovery).
- Porównanie A (bazowy) → B (po zmianie ustawienia): nowe lub częstsze wzorce zapisów.
- Eksport raportu do innych aplikacji przez systemowy Share Sheet.
- Brak uprawnienia INTERNET. Nie ma telemetrii ani automatycznej wysyłki logów.

## Jak znaleźć komendę zmiany ustawienia

1. Włącz **Bluetooth HCI snoop log** w opcjach programistycznych Androida. W HyperOS ścieżka lub nazwa opcji może się różnić.
2. Włącz ponownie Bluetooth, jeśli telefon tego wymaga, i połącz rower przez oryginalną aplikację DUOTTS.
3. Uzyskaj log bazowy **A**, podczas sesji, w której nie zmieniasz ustawień. Najlepiej zacznij od nowego raportu po krótkiej sesji.
4. Zrób drugą, podobną sesję i wykonaj w DUOTTS **jedną konkretną zmianę** (np. trybu wspomagania). Zapisz plik **B**.
5. Pobierz logi z raportów błędów systemowych Androida. W opcjach programistycznych wybierz **Zgłoś błąd** / **Take bug report**; dołączony ZIP *może* zawierać plik BTSnoop. Nie każda wersja HyperOS pozwala na jego eksport. Standardowa aplikacja bez roota nie ma swobodnego dostępu do katalogu /data/misc/bluetooth/logs.
6. Uruchom DUOTTS BLE Inspector i wczytaj oba pliki. Kliknij **Porównaj komendy A → B**.
7. Powtórz pomiar z tą samą pojedynczą zmianą. Wystąpienie różnicy w danych HEX samo w sobie **nie dowodzi**, że to właściwa komenda.

Jeśli raport ZIP nie zawiera BTSnoop, pojawi się konkretny błąd. Skaner BLE jest osobną funkcją; do analizy komunikacji **między dwiema innymi aplikacjami/urządzeniami** nie wystarcza zwykłe skanowanie.

## Ograniczenia i ostrożność

- Android 16 nie pozwala aplikacjom bez uprzywilejowanego dostępu nasłuchiwać pakietów innych aplikacji. Wykorzystujemy systemowy **HCI snoop log**, a nie podsłuch sieci czy ekranu.
- Dekoder obsługuje format BTSnoop version 1 / datalink 1002 i standardowe pakiety ATT. Proprietary format lub zaszyfrowana warstwa aplikacyjna wymagają osobnej analizy.
- Połączenie GATT z narzędzia może kolidować z oficjalną aplikacją, więc przeglądaj UUID, kiedy aplikacja DUOTTS jest rozłączona.
- Narzędzie **nie zmienia limitów prędkości, nie wyłącza żadnych zabezpieczeń**. Dopiero po rozpoznaniu protokołu można ocenić, czy istnieje bezpieczny i trwały sposób blokowania konfiguracji.
- Logi mogą zawierać dane innych urządzeń Bluetooth; **nie umieszczaj ich publicznie** bez sprawdzenia zawartości.
- Dekoder może poprawnie odczytać wartości HEX, ale nie zna semantyki protokołu DUOTTS.

## Kompilacja

Android Gradle Plugin 8.9.2, Java 17, Gradle 8.11.1, compileSdk 35.

Gotowy workflow znajduje się w .github/workflows/android.yml.
Lokalnie uruchom gradle :app:assembleDebug; APK będzie w app/build/outputs/apk/debug/app-debug.apk.

Projekt jest open-source na użytek diagnostyczny. Żaden kod nie wysyła pakietów ATT Write ani nie wymaga dostępu do Internetu.
