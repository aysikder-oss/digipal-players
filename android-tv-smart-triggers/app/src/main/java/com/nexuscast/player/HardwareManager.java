package com.nexuscast.player;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@android.annotation.SuppressLint("MissingPermission")
public class HardwareManager {
    private static final String TAG = "HardwareManager";
    private static final String ACTION_USB_PERMISSION = "com.nexuscast.player.USB_PERMISSION";

    private final Context context;
    private final HardwareListener listener;
    private final UsbManager usbManager;
    private final Handler handler;

    private final Map<String, DeviceInfo> connectedDevices = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, UsbDeviceConnection> usbConnections = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Thread> usbReaderThreads = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, BluetoothGatt> bleGatts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> connectingBle = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> bleRetries = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean started;
    private volatile int generation;
    private volatile String learnDeviceFilter = "";

    private boolean learnMode = false;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private boolean bleScanning = false;
    private ScanCallback activeScanCallback = null;

    public interface HardwareListener {
        void onDeviceConnected(JSONObject device);
        void onDeviceDisconnected(JSONObject device);
        void onSignalCaptured(JSONObject signal);
        void onSignalEvent(JSONObject signal);
    }

    public static class DeviceInfo {
        public String deviceId;
        public String deviceName;
        public String deviceType;
        public String protocol;
        public int vendorId;
        public int productId;
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null) {
                        requestUsbPermission(device);
                    }
                    break;
                }
                case UsbManager.ACTION_USB_DEVICE_DETACHED: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null) {
                        String deviceId = generateDeviceId(device);
                        removeDevice(deviceId);
                    }
                    break;
                }
                case ACTION_USB_PERMISSION: {
                    synchronized (this) {
                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                            connectUsbDevice(device);
                        }
                    }
                    break;
                }
            }
        }
    };

    public HardwareManager(Context context, HardwareListener listener) {
        this.context = context;
        this.listener = listener;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (this.bluetoothAdapter != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { this.bleScanner = this.bluetoothAdapter.getBluetoothLeScanner(); }
            catch (SecurityException ignored) { /* requested only by explicit Scan */ }
        }
    }

    public void start() {
        if (started) return;
        started = true;
        generation++;
        // System USB broadcasts come from the OS and need RECEIVER_EXPORTED on API 33+.
        // Our private permission result must use RECEIVER_NOT_EXPORTED for security.
        IntentFilter systemFilter = new IntentFilter();
        systemFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        systemFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        IntentFilter permFilter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, systemFilter, Context.RECEIVER_EXPORTED);
            context.registerReceiver(usbReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, systemFilter);
            context.registerReceiver(usbReceiver, permFilter);
        }

        scanExistingUsbDevices();
        Log.i(TAG, "Hardware manager started");
    }

    public void stop() {
        if (!started) { stopBleScan(); return; }
        started = false;
        generation++;
        learnMode = false;
        connectingBle.clear();
        bleRetries.clear();
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Receiver already unregistered");
        }

        stopBleScan();

        for (Map.Entry<String, UsbDeviceConnection> entry : usbConnections.entrySet()) {
            try { entry.getValue().close(); } catch (Exception e) {}
        }
        usbConnections.clear();

        for (Map.Entry<String, Thread> entry : usbReaderThreads.entrySet()) {
            try { entry.getValue().interrupt(); } catch (Exception e) {}
        }
        usbReaderThreads.clear();

        for (Map.Entry<String, BluetoothGatt> entry : bleGatts.entrySet()) {
            try { entry.getValue().close(); } catch (Exception e) {}
        }
        bleGatts.clear();

        connectedDevices.clear();
        Log.i(TAG, "Hardware manager stopped");
    }

    public JSONArray getConnectedDevices() {
        JSONArray arr = new JSONArray();
        for (DeviceInfo device : connectedDevices.values()) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("deviceId", device.deviceId);
                obj.put("deviceName", device.deviceName);
                obj.put("deviceType", device.deviceType);
                obj.put("protocol", device.protocol);
                obj.put("vendorId", device.vendorId);
                obj.put("productId", device.productId);
                arr.put(obj);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating device JSON", e);
            }
        }
        return arr;
    }

    public void startLearnMode() {
        startLearnMode("");
    }

    public void startLearnMode(String deviceFilter) {
        learnDeviceFilter = deviceFilter == null ? "" : deviceFilter;
        learnMode = true;
        Log.i(TAG, "Learn mode activated");
    }

    public void stopLearnMode() {
        learnMode = false;
        Log.i(TAG, "Learn mode deactivated");
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean hasScan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
            boolean hasConnect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasScan || !hasConnect) {
                Log.w(TAG, "Missing runtime Bluetooth permissions (BLUETOOTH_SCAN=" + hasScan + ", BLUETOOTH_CONNECT=" + hasConnect + ")");
                return false;
            }
        }
        return true;
    }

    public void startBleScan() {
        if (bleScanner == null && bluetoothAdapter != null && hasBluetoothPermissions()) {
            try { bleScanner = bluetoothAdapter.getBluetoothLeScanner(); }
            catch (SecurityException ignored) {}
        }
        if (bleScanner == null || bleScanning) return;
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "BLE scan aborted: missing permissions");
            return;
        }
        bleScanning = true;

        ScanCallback scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (!hasBluetoothPermissions()) { stopBleScan(); return; }
                BluetoothDevice device = result.getDevice();
                if (device == null) return;
                String deviceId = "ble_" + device.getAddress().replace(":", "").toLowerCase();
                if (!connectedDevices.containsKey(deviceId)) {
                    connectBleDevice(device, deviceId);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed: " + errorCode);
                bleScanning = false;
            }
        };

        activeScanCallback = scanCallback;
        try {
            bleScanner.startScan(scanCallback);
            handler.postDelayed(() -> {
                stopBleScan();
            }, 10000);
        } catch (SecurityException e) {
            Log.e(TAG, "BLE scan permission denied", e);
            bleScanning = false;
            activeScanCallback = null;
        }
    }

    public void stopBleScan() {
        if (bleScanner != null && activeScanCallback != null) {
            try {
                bleScanner.stopScan(activeScanCallback);
            } catch (Exception e) {
                Log.w(TAG, "Error stopping BLE scan", e);
            }
        }
        activeScanCallback = null;
        bleScanning = false;
    }

    private void scanExistingUsbDevices() {
        if (usbManager == null) return;
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            requestUsbPermission(device);
        }
    }

    private void requestUsbPermission(UsbDevice device) {
        if (!started || usbManager == null) return;
        if (usbManager.hasPermission(device)) { connectUsbDevice(device); return; }
        // USB permission result requires OS fill-in extras. Constrain the mutable
        // PendingIntent to this package and one device (never an implicit intent).
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, device.getDeviceId(),
                new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()), flags);
        usbManager.requestPermission(device, pi);
    }

    private void connectUsbDevice(UsbDevice device) {
        if (!started) return;
        String deviceId = generateDeviceId(device);
        if (connectedDevices.containsKey(deviceId)) return;

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) return;

        DeviceInfo info = new DeviceInfo();
        info.deviceId = deviceId;
        info.deviceName = device.getProductName() != null ? device.getProductName() :
                "USB Device " + device.getVendorId() + ":" + device.getProductId();
        info.vendorId = device.getVendorId();
        info.productId = device.getProductId();

        UsbEndpoint inputEndpoint = findInputEndpoint(device);
        if (inputEndpoint != null) {
            if (inputEndpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                info.deviceType = "serial";
                info.protocol = "usb_serial";
            } else {
                info.deviceType = "hid";
                info.protocol = "usb_hid";
            }

            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                for (int j = 0; j < iface.getEndpointCount(); j++) {
                    if (iface.getEndpoint(j).equals(inputEndpoint)) {
                        connection.claimInterface(iface, true);
                        break;
                    }
                }
            }
        } else {
            info.deviceType = "serial";
            info.protocol = "usb_serial";
        }

        connectedDevices.put(deviceId, info);
        usbConnections.put(deviceId, connection);

        if (inputEndpoint != null) {
            startUsbReader(deviceId, connection, inputEndpoint, info);
        }

        notifyDeviceConnected(info);
    }

    private UsbEndpoint findInputEndpoint(UsbDevice device) {
        UsbEndpoint interruptEndpoint = null;
        UsbEndpoint bulkEndpoint = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT && interruptEndpoint == null) {
                        interruptEndpoint = ep;
                    } else if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && bulkEndpoint == null) {
                        bulkEndpoint = ep;
                    }
                }
            }
        }
        return interruptEndpoint != null ? interruptEndpoint : bulkEndpoint;
    }

    private void startUsbReader(String deviceId, UsbDeviceConnection connection,
                                UsbEndpoint endpoint, DeviceInfo info) {
        Thread readerThread = new Thread(() -> {
            byte[] buffer = new byte[endpoint.getMaxPacketSize()];
            while (started && !Thread.currentThread().isInterrupted()) {
                int bytesRead = connection.bulkTransfer(endpoint, buffer, buffer.length, 1000);
                if (bytesRead > 0) {
                    byte[] data = new byte[bytesRead];
                    System.arraycopy(buffer, 0, data, 0, bytesRead);
                    handleSignal(deviceId, info, data);
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
        usbReaderThreads.put(deviceId, readerThread);
    }

    private void connectBleDevice(BluetoothDevice device, String deviceId) {
        if (!hasBluetoothPermissions()) return;
        if (!started || connectedDevices.containsKey(deviceId) || !connectingBle.add(deviceId)) return;
        final int connectionGeneration = generation;
        try {
            DeviceInfo info = new DeviceInfo();
            info.deviceId = deviceId;
            info.deviceName = device.getName() != null ? device.getName() : "BLE Device " + device.getAddress();
            info.deviceType = "ble";
            info.protocol = "bluetooth_le";
            info.vendorId = 0;
            info.productId = 0;

            BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
                private final java.util.ArrayDeque<BluetoothGattDescriptor> notificationQueue = new java.util.ArrayDeque<>();
                private int writeGeneration;
                private synchronized void writeNext(BluetoothGatt gatt) {
                    final int write = ++writeGeneration;
                    if (connectionGeneration != generation) return;
                    BluetoothGattDescriptor descriptor = notificationQueue.poll();
                    if (descriptor == null) return;
                    boolean accepted = false;
                    try { accepted = gatt.writeDescriptor(descriptor); } catch (SecurityException ignored) {}
                    if (!accepted) {
                        handler.postDelayed(() -> writeNext(gatt), 250);
                    } else {
                        handler.postDelayed(() -> {
                            synchronized (this) {
                                if (write == writeGeneration) writeNext(gatt);
                            }
                        }, 5000);
                    }
                }
                @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                    writeNext(gatt);
                }
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                    if (connectionGeneration != generation) { gatt.close(); return; }
                    connectingBle.remove(deviceId);
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        bleRetries.remove(deviceId);
                        connectedDevices.put(deviceId, info);
                        bleGatts.put(deviceId, gatt);
                        notifyDeviceConnected(info);
                        try { gatt.discoverServices(); } catch (SecurityException e) {}
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        removeDevice(deviceId);
                        try { gatt.close(); } catch (Exception e) {}
                        int retries = bleRetries.getOrDefault(deviceId, 0) + 1;
                        bleRetries.put(deviceId, retries);
                        if (retries <= 6) handler.postDelayed(() -> {
                            if (started && connectionGeneration == generation) connectBleDevice(device, deviceId);
                        }, Math.min(30000L, 1000L << Math.min(retries, 5)));
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        for (BluetoothGattService service : gatt.getServices()) {
                            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                                int props = characteristic.getProperties();
                                boolean supportsNotify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                                boolean supportsIndicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                                if (supportsNotify || supportsIndicate) {
                                    try {
                                        gatt.setCharacteristicNotification(characteristic, true);
                                        BluetoothGattDescriptor cccd = characteristic.getDescriptor(
                                            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                                        if (cccd != null) {
                                            byte[] descriptorValue = supportsNotify
                                                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                                            cccd.setValue(descriptorValue);
                                            notificationQueue.add(cccd);
                                        }
                                    } catch (SecurityException e) {
                                        Log.w(TAG, "BLE notification setup permission denied", e);
                                    }
                                }
                            }
                        }
                        writeNext(gatt);
                    }
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt,
                                                    BluetoothGattCharacteristic characteristic) {
                    byte[] data = characteristic.getValue();
                    if (data != null && data.length > 0) {
                        handleSignal(deviceId, info, data);
                    }
                }
            };

            try {
                device.connectGatt(context, false, gattCallback);
            } catch (SecurityException e) {
                connectingBle.remove(deviceId);
                Log.e(TAG, "BLE connect permission denied", e);
            }
        } catch (Exception e) {
            connectingBle.remove(deviceId);
            Log.e(TAG, "Failed to connect BLE device", e);
        }
    }

    private void handleSignal(String deviceId, DeviceInfo info, byte[] rawData) {
        String signalKey = generateSignalKey(deviceId, rawData);
        try {
            JSONObject signal = new JSONObject();
            signal.put("deviceId", deviceId);
            signal.put("deviceName", info.deviceName);
            signal.put("deviceType", info.deviceType);
            signal.put("protocol", info.protocol);
            signal.put("signalKey", signalKey);
            signal.put("rawData", bytesToHex(rawData));
            signal.put("timestamp", System.currentTimeMillis());

            final int signalGeneration = generation;
            handler.post(() -> {
                if (signalGeneration != generation) return;
                if (learnMode && (learnDeviceFilter.isEmpty() || learnDeviceFilter.equals(deviceId))) {
                    learnMode = false;
                    listener.onSignalCaptured(signal);
                } else {
                    listener.onSignalEvent(signal);
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Error creating signal JSON", e);
        }
    }

    private void removeDevice(String deviceId) {
        DeviceInfo info = connectedDevices.remove(deviceId);
        if (info == null) return;

        UsbDeviceConnection conn = usbConnections.remove(deviceId);
        if (conn != null) {
            try { conn.close(); } catch (Exception e) {}
        }

        Thread reader = usbReaderThreads.remove(deviceId);
        if (reader != null) {
            try { reader.interrupt(); } catch (Exception e) {}
        }

        BluetoothGatt gatt = bleGatts.remove(deviceId);
        if (gatt != null) {
            try { gatt.close(); } catch (Exception e) {}
        }

        try {
            JSONObject obj = new JSONObject();
            obj.put("deviceId", info.deviceId);
            obj.put("deviceName", info.deviceName);
            obj.put("deviceType", info.deviceType);
            obj.put("protocol", info.protocol);
            handler.post(() -> listener.onDeviceDisconnected(obj));
        } catch (JSONException e) {
            Log.e(TAG, "Error creating disconnect JSON", e);
        }

        Log.i(TAG, "Device disconnected: " + info.deviceName + " (" + deviceId + ")");
    }

    private void notifyDeviceConnected(DeviceInfo info) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("deviceId", info.deviceId);
            obj.put("deviceName", info.deviceName);
            obj.put("deviceType", info.deviceType);
            obj.put("protocol", info.protocol);
            obj.put("vendorId", info.vendorId);
            obj.put("productId", info.productId);
            handler.post(() -> listener.onDeviceConnected(obj));
        } catch (JSONException e) {
            Log.e(TAG, "Error creating connect JSON", e);
        }
        Log.i(TAG, "Device connected: " + info.deviceName + " (" + info.deviceId + ")");
    }

    private String generateDeviceId(UsbDevice device) {
        String key = device.getVendorId() + ":" + device.getProductId() + ":" + device.getDeviceName();
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes());
            return bytesToHex(digest).substring(0, 16);
        } catch (Exception e) {
            return String.valueOf(key.hashCode());
        }
    }

    private String generateSignalKey(String deviceId, byte[] rawData) {
        String input = deviceId + ":" + bytesToHex(rawData);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes());
            return bytesToHex(digest).substring(0, 32);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
