package com.my.procty;

import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;

public class ShizukuChecker {
    
    public static boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasShizukuPermission() {
        try {
            if (!isShizukuRunning()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }
}
