package az.autoproject.suret;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * GPS Sürət Analizatoru — AutoProject.
 * İnterfeys və ölçmə məntiqi assets/index.html-dədir (veb versiya ilə eyni fayl).
 * Bu sinif ona brauzerin verə bilmədiyi iki şeyi verir:
 *   1) nativ GNSS: peyk sayı, sistemlər üzrə bölgü, siqnal gücü (GnssStatus), sürət dəqiqliyi;
 *   2) Bluetooth LE: RaceBox Mini və ElmGPS (ESP32 + u-blox) — Android WebView-da Web Bluetooth yoxdur.
 * JS tərəfi: window.ElmNative (bu körpü) → window.elmNative.onFix/onSats/onBle/... (geri çağırışlar).
 */
public class MainActivity extends Activity {

    private static final UUID NUS = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQ_LOC = 1, REQ_BLE = 2;

    private WebView web;
    private LocationManager lm;
    private LocationListener locListener;
    private GnssStatus.Callback gnssCallback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String bleKind;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private String bleName = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF11151B);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true; // xarici linklər (Instagram, WhatsApp, tel:) öz tətbiqlərində açılır
            }
        });
        web.addJavascriptInterface(new Bridge(), "ElmNative");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        stopGnss();
        stopBle();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ===================== JS körpüsü =====================
    private class Bridge {
        @JavascriptInterface public void startGnss() { main.post(MainActivity.this::requestGnss); }
        @JavascriptInterface public void stopGnss() { main.post(MainActivity.this::stopGnss); }
        @JavascriptInterface public void startBle(String kind) { main.post(() -> requestBle(kind)); }
        @JavascriptInterface public void stopBle() { main.post(MainActivity.this::stopBle); }
        @JavascriptInterface public String info() {
            return "Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL;
        }
    }

    private void js(String fn, String arg) {
        final String code = "window.elmNative&&elmNative." + fn + "&&elmNative." + fn + "(" + arg + ")";
        main.post(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }
    private void jsMsg(String fn, String msg) { js(fn, JSONObject.quote(msg)); }

    private boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        boolean ok = res.length > 0;
        for (int r : res) ok &= (r == PackageManager.PERMISSION_GRANTED);
        if (code == REQ_LOC) {
            if (ok || granted(Manifest.permission.ACCESS_FINE_LOCATION)) beginGnss();
            else jsMsg("onError", "Yer (location) icazəsi verilmədi. Ayarlar → Tətbiqlər → Sürət Analizatoru → İcazələr.");
        } else if (code == REQ_BLE) {
            if (ok) beginBle();
            else jsMsg("onError", "Bluetooth icazəsi verilmədi.");
        }
    }

    // ===================== GNSS =====================
    private void requestGnss() {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) beginGnss();
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
    }

    @SuppressLint("MissingPermission")
    private void beginGnss() {
        stopGnss();
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            jsMsg("onError", "Telefonda Yer (Location) funksiyası söndürülüb — yandırın.");
        }
        // Anonim sinif (lambda yox): Android 8–10-da interfeysin digər metodları hələ abstraktdır
        locListener = new LocationListener() {
            @Override public void onLocationChanged(Location l) { sendFix(l); }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { jsMsg("onError", "GPS söndürüldü."); }
            @SuppressWarnings("deprecation")
            @Override public void onStatusChanged(String p, int s, Bundle e) { }
        };
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // Ən yüksək tezliyi istəyirik; çip nə qədər verə bilirsə (adətən 1 Hz, bəzi modellərdə 5–10 Hz)
                LocationRequest req = new LocationRequest.Builder(100)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                        .setMinUpdateIntervalMillis(0)
                        .build();
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, req, getMainExecutor(), locListener);
            } else {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, locListener, Looper.getMainLooper());
            }
            gnssCallback = new GnssStatus.Callback() {
                @Override public void onSatelliteStatusChanged(GnssStatus st) { sendSats(st); }
                @Override public void onStarted() { jsMsg("onStatus", "GNSS işə düşdü, peyklər axtarılır…"); }
            };
            lm.registerGnssStatusCallback(gnssCallback, main);
        } catch (Exception e) {
            jsMsg("onError", "GNSS başlamadı: " + e.getMessage());
        }
    }

    private void stopGnss() {
        try {
            if (locListener != null) lm.removeUpdates(locListener);
            if (gnssCallback != null) lm.unregisterGnssStatusCallback(gnssCallback);
        } catch (Exception ignored) { }
        locListener = null;
        gnssCallback = null;
    }

    private void sendFix(Location l) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", l.getTime());
            o.put("lat", l.getLatitude());
            o.put("lon", l.getLongitude());
            o.put("spd", l.hasSpeed() ? l.getSpeed() : -1);
            o.put("acc", l.hasAccuracy() ? l.getAccuracy() : -1);
            o.put("sacc", l.hasSpeedAccuracy() ? l.getSpeedAccuracyMetersPerSecond() : -1);
            if (l.hasAltitude()) o.put("alt", l.getAltitude());
            js("onFix", o.toString());
        } catch (JSONException ignored) { }
    }

    private void sendSats(GnssStatus st) {
        try {
            int n = st.getSatelliteCount(), used = 0;
            int[] usedBy = new int[8], seenBy = new int[8];
            List<Float> cn0Used = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int c = Math.max(0, Math.min(7, st.getConstellationType(i)));
                seenBy[c]++;
                if (st.usedInFix(i)) {
                    used++;
                    usedBy[c]++;
                    cn0Used.add(st.getCn0DbHz(i));
                }
            }
            // Ən güclü 4 peykin orta siqnalı — qəbul keyfiyyətinin yaxşı göstəricisi
            Collections.sort(cn0Used, Collections.reverseOrder());
            float sum = 0; int k = Math.min(4, cn0Used.size());
            for (int i = 0; i < k; i++) sum += cn0Used.get(i);

            JSONObject o = new JSONObject();
            o.put("total", n);
            o.put("used", used);
            o.put("cn0", k > 0 ? Math.round(sum / k) : 0);
            JSONObject by = new JSONObject();
            // GnssStatus sabitləri: 1 GPS, 2 SBAS, 3 GLONASS, 4 QZSS, 5 BeiDou, 6 Galileo, 7 IRNSS
            String[] names = {"?", "GPS", "SBAS", "GLO", "QZSS", "BDS", "GAL", "NavIC"};
            for (int c = 1; c < 8; c++) {
                if (seenBy[c] > 0) by.put(names[c], usedBy[c] + "/" + seenBy[c]);
            }
            o.put("by", by);
            js("onSats", o.toString());
        } catch (JSONException ignored) { }
    }

    // ===================== Bluetooth LE =====================
    private void requestBle(String kind) {
        bleKind = kind;
        String[] need = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        boolean all = true;
        for (String p : need) all &= granted(p);
        if (all) beginBle(); else requestPermissions(need, REQ_BLE);
    }

    private final Runnable scanTimeout = () -> {
        if (scanCallback != null) {
            stopScan();
            jsMsg("onError", "Cihaz tapılmadı. Cihazın yandığına və yaxında olduğuna əmin olun.");
        }
    };

    @SuppressLint("MissingPermission")
    private void beginBle() {
        stopBle();
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) { jsMsg("onError", "Bu telefonda Bluetooth yoxdur."); return; }
        if (!ad.isEnabled()) { jsMsg("onError", "Bluetooth söndürülüb — yandırın və yenidən seçin."); return; }
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { jsMsg("onError", "Bluetooth axtarışı mümkün olmadı."); return; }
        final String kind = bleKind;
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                ScanRecord rec = r.getScanRecord();
                String name = rec != null ? rec.getDeviceName() : null;
                if (name == null) name = r.getDevice().getName();
                List<ParcelUuid> su = rec != null ? rec.getServiceUuids() : null;
                boolean hasNus = su != null && su.contains(new ParcelUuid(NUS));
                boolean match = "racebox".equals(kind)
                        ? (name != null && name.startsWith("RaceBox"))
                        : ((name != null && name.startsWith("ElmGPS")) || hasNus);
                if (!match || scanCallback == null) return;
                stopScan();
                bleName = name != null ? name : r.getDevice().getAddress();
                jsMsg("onStatus", "Qoşulur: " + bleName);
                gatt = r.getDevice().connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            }
            @Override public void onScanFailed(int err) {
                scanCallback = null;
                jsMsg("onError", "Bluetooth axtarışı alınmadı (kod " + err + ").");
            }
        };
        ScanSettings ss = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(null, ss, scanCallback);
        jsMsg("onStatus", "racebox".equals(kind) ? "RaceBox axtarılır…" : "ElmGPS axtarılır…");
        main.postDelayed(scanTimeout, 15000);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        try { if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        scanCallback = null;
    }

    @SuppressLint("MissingPermission")
    private void stopBle() {
        stopScan();
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                if (!g.requestMtu(247)) g.discoverServices();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close(); } catch (Exception ignored) { }
                if (gatt == g) {
                    gatt = null;
                    js("onBleDisconnected", "");
                }
            }
        }

        @SuppressLint("MissingPermission")
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) { g.discoverServices(); }

        @SuppressLint("MissingPermission")
        @SuppressWarnings("deprecation")
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(NUS);
            BluetoothGattCharacteristic ch = svc == null ? null : svc.getCharacteristic(NUS_TX);
            if (ch == null) { jsMsg("onError", "Cihazda məlumat servisi tapılmadı (NUS)."); return; }
            g.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor d = ch.getDescriptor(CCCD);
            if (d != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                }
            }
            jsMsg("onStatus", "Qoşuldu: " + bleName);
        }

        // Android 13+
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch, byte[] value) {
            forward(value);
        }

        // Android 12 və aşağı
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            forward(ch.getValue());
        }
    };

    private void forward(byte[] v) {
        if (v == null || v.length == 0) return;
        js("onBle", "'" + Base64.encodeToString(v, Base64.NO_WRAP) + "'");
    }
}
