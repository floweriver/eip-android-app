# 保持藍牙相關回呼不被混淆
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }
-keep class * extends android.bluetooth.le.ScanCallback { *; }

# 保持您的數據模型不被混淆（對應您的 package）
-keep class com.example.eip.ble.** { *; }
-keep class com.example.eip.ui.** { *; }
