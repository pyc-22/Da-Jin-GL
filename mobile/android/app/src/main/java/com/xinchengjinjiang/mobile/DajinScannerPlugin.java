package com.xinchengjinjiang.mobile;

import android.Manifest;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

@CapacitorPlugin(name = "DajinScanner", permissions = {
    @Permission(alias = "camera", strings = {Manifest.permission.CAMERA})
})
public class DajinScannerPlugin extends Plugin {
    private boolean scanning;

    @PluginMethod public void scan(PluginCall call) {
        if (scanning) { call.reject("扫码已打开，请先完成当前扫码"); return; }
        scanning = true;
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraPermissionResult");
        } else openScanner(call);
    }

    @PermissionCallback private void cameraPermissionResult(PluginCall call) {
        if (getPermissionState("camera") == PermissionState.GRANTED) openScanner(call);
        else { scanning = false; call.reject("请在手机设置中允许相机权限后重试", "CAMERA_PERMISSION_DENIED"); }
    }

    private void openScanner(PluginCall call) {
        try {
            IntentIntegrator scanner = new IntentIntegrator(getActivity());
            scanner.setCaptureActivity(ScanActivity.class);
            scanner.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES);
            scanner.setPrompt("请将商品二维码或条形码放入框内\n识别后自动返回，按返回键取消");
            scanner.setBeepEnabled(true);
            scanner.setOrientationLocked(true);
            startActivityForResult(call, scanner.createScanIntent(), "scanResult");
        } catch (Exception ex) {
            scanning = false;
            call.reject("相机启动失败，请检查相机权限后重试", ex);
        }
    }

    @ActivityCallback private void scanResult(PluginCall call, ActivityResult result) {
        scanning = false;
        if (call == null) return;
        IntentResult scanned = IntentIntegrator.parseActivityResult(result.getResultCode(), result.getData());
        if (scanned == null || scanned.getContents() == null) {
            call.reject("已取消扫码", "SCAN_CANCELLED");
            return;
        }
        JSObject data = new JSObject();
        data.put("text", scanned.getContents());
        data.put("format", scanned.getFormatName());
        call.resolve(data);
    }
}
