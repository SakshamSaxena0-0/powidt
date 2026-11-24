package com.sparkle.pawidget;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.util.List;

/** Where audio is going right now: "Galaxy Buds2 Pro", "USB DAC", "Wired headphones" or "Phone speaker". */
final class OutputDevice {
    final int icon;
    final String label;

    private OutputDevice(int icon, String label) {
        this.icon = icon;
        this.label = label;
    }

    static OutputDevice current(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return speaker();
        AudioDeviceInfo best = null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // What media actually routes to.
                List<AudioDeviceInfo> routed = am.getAudioDevicesForAttributes(
                        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
                if (!routed.isEmpty()) best = routed.get(0);
            }
            if (best == null) {
                int bestRank = -1;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int r = rank(d.getType());
                    if (r > bestRank) {
                        bestRank = r;
                        best = d;
                    }
                }
            }
        } catch (Throwable th) {
            return speaker();
        }
        if (best == null) return speaker();
        String name = best.getProductName() != null ? best.getProductName().toString().trim() : "";
        switch (best.getType()) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return new OutputDevice(R.drawable.ic_out_bluetooth, name.isEmpty() ? "Bluetooth" : name);
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return new OutputDevice(R.drawable.ic_out_usb, name.isEmpty() || name.length() > 22 ? "USB DAC" : name);
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return new OutputDevice(R.drawable.ic_out_headphones, "Wired headphones");
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
                return new OutputDevice(R.drawable.ic_out_speaker, "HDMI");
            default:
                return speaker();
        }
    }

    private static OutputDevice speaker() {
        return new OutputDevice(R.drawable.ic_out_speaker, "Phone speaker");
    }

    /** Fallback ranking when the routed device can't be asked for (Android 12 and lower). */
    private static int rank(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return 4;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return 3;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return 2;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return 1;
            default:
                return 0;
        }
    }
}
