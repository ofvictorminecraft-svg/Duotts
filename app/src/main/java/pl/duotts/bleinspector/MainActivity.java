package pl.duotts.bleinspector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int PICK_A = 201;
    private static final int PICK_B = 202;
    private static final int PERMISSIONS = 203;
    private static final int BG = Color.rgb(11, 18, 33);
    private static final int CARD = Color.rgb(25, 36, 57);
    private static final int WHITE = Color.rgb(231, 238, 249);
    private static final int MUTED = Color.rgb(158, 175, 200);
    private static final int BLUE = Color.rgb(56, 189, 248);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> signalStrength = new LinkedHashMap<>();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning = false;
    private TextView status, nearby, services, captureA, captureB, results;
    private BtsnoopParser.Capture first, second;
    private String report = "";
    private LinearLayout nearbyRows;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(22), dp(16), dp(35));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = label("DUOTTS", 29, WHITE, true);
        root.addView(title);
        root.addView(label("BLE INSPECTOR  •  v0.1.0", 12, BLUE, true));
        gap(root, 12);
        TextView intro = label("Sprawdź transmisję Bluetooth aplikacji producenta. Wszystko lokalnie, bez zmieniania ustawień roweru.", 14, MUTED, false);
        root.addView(intro);
        gap(root, 12);
        status = label("Gotowy do analizy", 13, MUTED, false);
        root.addView(status);
        gap(root, 14);

        LinearLayout scanCard = card(root, "01  Urządzenia Bluetooth LE");
        scanCard.addView(label("Wyszukaj rower, a następnie opcjonalnie odczytaj jego usługi i charakterystyki GATT. To nie jest podsłuch aplikacji.", 13, MUTED, false));
        gap(scanCard, 12);
        rowButton(scanCard, "Skanuj 12 sekund", this::scanDevices);
        nearby = label("Nie skanowano.", 13, MUTED, false);
        scanCard.addView(nearby);
        nearbyRows = new LinearLayout(this);
        nearbyRows.setOrientation(LinearLayout.VERTICAL);
        scanCard.addView(nearbyRows);
        gap(scanCard, 10);
        services = label("Wybierz urządzenie z listy, aby zobaczyć GATT (odczyt metadanych, bez WRITE).", 12, MUTED, false);
        services.setTextIsSelectable(true);
        scanCard.addView(services);

        gap(root, 13);
        LinearLayout logCard = card(root, "02  Import logów HCI");
        logCard.addView(label("Włącz Bluetooth HCI snoop log w opcjach programistycznych Androida. Zrób raport błędów przed i po pojedynczej zmianie w DUOTTS.", 13, MUTED, false));
        gap(logCard, 10);
        rowButton(logCard, "Wybierz plik A – punkt odniesienia", () -> pick(PICK_A));
        captureA = label("A: brak pliku", 12, MUTED, false);
        logCard.addView(captureA);
        gap(logCard, 10);
        rowButton(logCard, "Wybierz plik B – po zmianie", () -> pick(PICK_B));
        captureB = label("B: brak pliku", 12, MUTED, false);
        logCard.addView(captureB);
        gap(logCard, 12);
        rowButton(logCard, "Porównaj komendy A → B", this::compare);
        gap(logCard, 8);
        rowButton(logCard, "Udostępnij raport tekstowy", this::shareReport);
        gap(logCard, 10);
        logCard.addView(label("Obsługiwane: btsnoop_hci.log (HCI UART / 1002) oraz ZIP zawierający taki plik.", 12, MUTED, false));

        gap(root, 13);
        LinearLayout analysisCard = card(root, "03  Analiza pakietów");
        results = label("Zaimportuj pliki A i B. Nowe komendy WRITE zostaną wyróżnione w wynikach.", 12, MUTED, false);
        results.setTextIsSelectable(true);
        analysisCard.addView(results);

        gap(root, 13);
        TextView privacy = label("TRYB TYLKO DO ODCZYTU\nNie zapisujemy niczego w sterowniku. Surowe logi HCI mogą zawierać dane innych urządzeń – nie publikuj ich bez sprawdzenia.", 12, MUTED, false);
        root.addView(privacy);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        text.setLineSpacing(dp(3), 1f);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
    private void gap(LinearLayout p, int h) {
        View v = new View(this);
        p.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
    }
    private GradientDrawable bg(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private LinearLayout card(LinearLayout parent, String header) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.setBackground(bg(CARD, 16));
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
        box.addView(label(header, 16, WHITE, true));
        gap(box, 10);
        return box;
    }
    private void rowButton(LinearLayout parent, String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(BG);
        b.setBackground(bg(BLUE, 10));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(45));
        p.bottomMargin = dp(8);
        parent.addView(b, p);
        b.setOnClickListener(view -> action.run());
    }
    private void message(String title, String content) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(content).setPositiveButton("OK", null).show();
    }
    private void setStatus(String value) { status.setText(value); }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS);
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == PERMISSIONS && hasPermissions()) scanDevices();
        else if (requestCode == PERMISSIONS) setStatus("Brak uprawnień Bluetooth – sprawdź Ustawienia aplikacji.");
    }
    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning) return;
            BluetoothDevice device = result.getDevice();
            if (device == null) return;
            String address;
            try { address = device.getAddress(); }
            catch (SecurityException e) { return; }
            devices.put(address, device);
            signalStrength.put(address, result.getRssi());
            handler.post(MainActivity.this::refreshDevices);
        }
        @Override public void onScanFailed(int errorCode) {
            handler.post(() -> { stopScan(); setStatus("Błąd skanowania BLE: " + errorCode); });
        }
    };
    @SuppressLint("MissingPermission")
    private void scanDevices() {
        if (!hasPermissions()) { requestBtPermissions(); return; }
        if (adapter == null) { message("Bluetooth", "Telefon nie ma obsługi Bluetooth."); return; }
        if (!adapter.isEnabled()) {
            message("Bluetooth wyłączony", "Włącz Bluetooth w telefonie i spróbuj ponownie.");
            return;
        }
        if (scanning) stopScan();
        disconnectGatt();
        devices.clear(); signalStrength.clear();
        refreshDevices();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { setStatus("Nie udało się uruchomić BLE."); return; }
        try {
            scanning = true;
            scanner.startScan(callback);
            setStatus("Skanuję urządzenia BLE przez 12 sekund…");
            handler.postDelayed(this::stopScan, 12_000);
        } catch (SecurityException | IllegalStateException e) {
            scanning = false;
            setStatus("Nie można skanować: " + e.getMessage());
        }
    }
    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { if (scanner != null) scanner.stopScan(callback); }
        catch (SecurityException | IllegalStateException ignored) {}
        setStatus("Skanowanie zakończone: " + devices.size() + " urządzeń.");
    }
    @SuppressLint("MissingPermission")
    private void refreshDevices() {
        nearbyRows.removeAllViews();
        nearby.setText(devices.isEmpty() ? (scanning ? "Szukam urządzeń…" : "Brak znalezionych urządzeń.") : "Dotknij urządzenia, aby obejrzeć usługi GATT:");
        List<String> addresses = new ArrayList<>(devices.keySet());
        addresses.sort(Comparator.comparingInt(a -> -signalStrength.getOrDefault(a, -127)));
        int shown = 0;
        for (String address : addresses) {
            if (++shown > 30) break;
            BluetoothDevice d = devices.get(address);
            String name = "(bez nazwy)";
            try { if (d.getName() != null && !d.getName().trim().isEmpty()) name = d.getName(); }
            catch (SecurityException ignored) {}
            TextView item = label(name + "  •  " + signalStrength.getOrDefault(address, -127) + " dBm\n" + address + "   ·  GATT →", 12, WHITE, false);
            item.setPadding(dp(10), dp(11), dp(10), dp(11));
            GradientDrawable background = bg(0xFF344359, 9);
            item.setBackground(background);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = dp(6);
            nearbyRows.addView(item, p);
            item.setOnClickListener(v -> inspectGatt(d));
        }
    }
    @SuppressLint("MissingPermission")
    private void inspectGatt(BluetoothDevice device) {
        if (!hasPermissions()) { requestBtPermissions(); return; }
        stopScan();
        disconnectGatt();
        String name;
        try { name = device.getName(); } catch (SecurityException e) { name = null; }
        final String deviceLabel = (name == null ? "Urządzenie" : name) + " (" + device.getAddress() + ")";
        services.setText("Łączenie z " + deviceLabel + "…\nJeśli DUOTTS jest połączony, najpierw rozłącz oficjalną aplikację.");
        try {
            gatt = device.connectGatt(this, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int state) {
                    if (statusCode == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                        try { if (!g.discoverServices()) failGatt("Nie udało się rozpocząć discovery.", g); }
                        catch (SecurityException e) { failGatt("Brak uprawnień: " + e.getMessage(), g); }
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED || statusCode != BluetoothGatt.GATT_SUCCESS) {
                        failGatt("Rozłączono (GATT status " + statusCode + ").", g);
                    }
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
                    if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                        failGatt("Nie udało się pobrać usług GATT: " + statusCode, g); return;
                    }
                    StringBuilder b = new StringBuilder();
                    b.append(deviceLabel).append("\n");
                    for (BluetoothGattService s : g.getServices()) {
                        b.append("\nSERVICE ").append(s.getUuid()).append("\n");
                        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                            b.append("  CHAR ").append(c.getUuid()).append("  flags=0x")
                                .append(Integer.toHexString(c.getProperties()).toUpperCase(Locale.US)).append("\n");
                        }
                    }
                    runOnUiThread(() -> { services.setText(b.toString()); setStatus("Odczytano metadane GATT."); });
                    closeGatt(g);
                }
                private void failGatt(String text, BluetoothGatt g) {
                    if (gatt != g) return; // ignore the expected disconnect after discovery
                    runOnUiThread(() -> { services.setText(text); setStatus("GATT: " + text); });
                    closeGatt(g);
                }
            }, BluetoothDevice.TRANSPORT_LE);
            setStatus("Odczyt usług GATT (bez wysyłania komend WRITE)…");
        } catch (SecurityException | IllegalArgumentException e) {
            setStatus("Błąd połączenia: " + e.getMessage());
        }
    }
    @SuppressLint("MissingPermission")
    private void closeGatt(BluetoothGatt g) {
        try { g.disconnect(); } catch (Exception ignored) {}
        try { g.close(); } catch (Exception ignored) {}
        if (gatt == g) gatt = null;
    }
    private void disconnectGatt() {
        if (gatt != null) { BluetoothGatt old = gatt; gatt = null; closeGatt(old); }
    }

    private void pick(int code) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, code);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request != PICK_A && request != PICK_B) return;
        Uri uri = data.getData();
        final boolean isA = request == PICK_A;
        final String name = displayName(uri);
        setStatus("Analizuję " + name + "…");
        new Thread(() -> {
            try {
                BtsnoopParser.Capture capture = readCapture(uri, name);
                capture.sourceName = name;
                runOnUiThread(() -> {
                    if (isA) {
                        first = capture;
                        captureA.setText(summary("A", capture));
                    } else {
                        second = capture;
                        captureB.setText(summary("B", capture));
                    }
                    setStatus("Wczytano " + name);
                    showCapture(capture);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setStatus("Błąd importu: " + exception.getMessage());
                    message("Nie udało się wczytać logu", exception.getMessage());
                });
            }
        }, "hci-parser").start();
    }
    private String displayName(Uri uri) {
        String name = "log HCI";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {}
        return name == null ? "log HCI" : name;
    }
    private BtsnoopParser.Capture readCapture(Uri uri, String name) throws IOException {
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) throw new IOException("Brak dostępu do pliku.");
        try (BufferedInputStream input = new BufferedInputStream(stream)) {
            input.mark(5);
            byte[] magic = new byte[4];
            int n = input.read(magic);
            input.reset();
            if (n == 4 && magic[0] == 'P' && magic[1] == 'K') {
                ZipInputStream zip = new ZipInputStream(input);
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String entryName = entry.getName().toLowerCase(Locale.US);
                    if (!entry.isDirectory() && (entryName.contains("btsnoop") && (entryName.endsWith(".log") || entryName.endsWith(".cfa")))) {
                        return BtsnoopParser.parse(zip);
                    }
                    zip.closeEntry();
                }
                throw new IOException("ZIP nie zawiera btsnoop_hci.log. W tym raporcie nie ma logu Bluetooth HCI.");
            }
            return BtsnoopParser.parse(input);
        }
    }
    private String summary(String tag, BtsnoopParser.Capture c) {
        return tag + ": " + c.sourceName + "\n" + c.packets + " HCI, " + c.aclPackets
            + " ACL, " + c.writePackets + " WRITE, " + c.events.size() + " zdarzeń ATT.";
    }
    private String eventLine(BtsnoopParser.Event e) {
        String time = e.timeMs > 0 ? DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(e.timeMs)) : "--:--:--";
        String hex = e.dataHex.length() > 120 ? e.dataHex.substring(0, 120) + "…" : e.dataHex;
        return time + "  " + (e.outgoing ? "TX" : "RX") + "  " + e.operation
                + "  handle=0x" + Integer.toHexString(e.attributeHandle)
                + "  conn=0x" + Integer.toHexString(e.connectionHandle)
                + "\n" + (e.characteristicUuid.isEmpty() ? "" : "UUID: " + e.characteristicUuid + "\n")
                + "HEX: " + hex + "\n";
    }
    private void showCapture(BtsnoopParser.Capture c) {
        StringBuilder b = new StringBuilder();
        b.append("PODGLĄD: ").append(c.sourceName).append("\n")
            .append("HCI: ").append(c.packets).append(" | ATT: ").append(c.events.size())
            .append(" | zapisów: ").append(c.writePackets).append("\n\n");
        int writes = 0;
        for (BtsnoopParser.Event e : c.events) {
            if (!e.isWrite()) continue;
            b.append(eventLine(e)).append("\n");
            if (++writes >= 100) { b.append("… pokazano pierwsze 100 zapisów."); break; }
        }
        if (writes == 0) b.append("Nie znaleziono zapisów ATT WRITE. Sprawdź, czy log zawiera sesję BLE z DUOTTS.");
        report = b.toString();
        results.setText(report);
    }
    private void compare() {
        if (first == null || second == null) {
            message("Potrzebne dwa logi", "Wczytaj A (sesja bazowa) i B (sesja po jednej zmianie ustawienia).");
            return;
        }
        Map<String, Integer> aCounts = countWrites(first);
        Map<String, Integer> bCounts = countWrites(second);
        StringBuilder b = new StringBuilder();
        b.append("PORÓWNANIE A → B\nA: ").append(first.sourceName)
            .append("\nB: ").append(second.sourceName)
            .append("\nZapisów A: ").append(first.writePackets)
            .append("  |  B: ").append(second.writePackets).append("\n\n");
        b.append("NOWE WZORCE KOMEND / WZROSTY LICZBY WYSTĄPIEŃ:\n\n");
        int matches = 0;
        Map<String, Boolean> shown = new HashMap<>();
        for (BtsnoopParser.Event event : second.events) {
            if (!event.isWrite()) continue;
            String signature = event.signature();
            int aCount = aCounts.getOrDefault(signature, 0);
            int bCount = bCounts.getOrDefault(signature, 0);
            if (bCount <= aCount || shown.containsKey(signature)) continue;
            shown.put(signature, true);
            b.append("A: ").append(aCount).append("  →  B: ").append(bCount).append("\n");
            b.append(eventLine(event)).append("\n");
            if (++matches >= 120) { b.append("Ograniczono do 120 wyników.\n"); break; }
        }
        if (matches == 0) b.append("Brak nowych wzorców ATT WRITE. Sprawdź logi, połączenie i protokół.\n");
        b.append("\nUWAGA: wykryta różnica NIE oznacza automatycznie komendy zmiany limitu prędkości. Potwierdź ją powtarzając eksperyment z jedną zmianą.\n");
        report = b.toString();
        results.setText(report);
        setStatus("Porównanie: " + matches + " różniących się wzorców WRITE.");
    }
    private Map<String, Integer> countWrites(BtsnoopParser.Capture capture) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (BtsnoopParser.Event e : capture.events) {
            if (e.isWrite()) {
                String signature = e.signature();
                map.put(signature, map.getOrDefault(signature, 0) + 1);
            }
        }
        return map;
    }
    private void shareReport() {
        if (report.trim().isEmpty()) { message("Raport pusty", "Najpierw zaimportuj log."); return; }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "DUOTTS BLE Inspector – raport HCI");
        send.putExtra(Intent.EXTRA_TEXT, report);
        startActivity(Intent.createChooser(send, "Udostępnij raport"));
    }
    @Override protected void onStop() {
        super.onStop();
        stopScan();
    }
    @Override protected void onDestroy() {
        disconnectGatt();
        super.onDestroy();
    }
}
