package com.merrooo.Chat_APP;

import android.Manifest;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

@CapacitorPlugin(
    name = "FileSaver",
    permissions = {
        @Permission(alias = "storage", strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE })
    }
)
public class FileSaverPlugin extends Plugin {
    @PluginMethod
    public void saveFile(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            getPermissionState("storage") != PermissionState.GRANTED) {
            requestPermissionForAlias("storage", call, "saveFilePermissionCallback");
            return;
        }
        saveFileToDownloads(call);
    }

    @PermissionCallback
    private void saveFilePermissionCallback(PluginCall call) {
        if (getPermissionState("storage") != PermissionState.GRANTED) {
            call.reject("Storage permission is required to save this file.");
            return;
        }
        saveFileToDownloads(call);
    }

    private void saveFileToDownloads(PluginCall call) {
        String dataUrl = call.getString("dataUrl");
        String requestedName = call.getString("fileName");
        String mimeType = call.getString("mimeType");
        if (dataUrl == null || !dataUrl.startsWith("data:") || !dataUrl.contains(";base64,")) {
            call.reject("The attachment data is invalid.");
            return;
        }

        int separator = dataUrl.indexOf(',');
        String fileName = sanitizeFileName(requestedName);
        String encodedData = dataUrl.substring(separator + 1);
        String dataMimeType = dataUrl.substring("data:".length(), dataUrl.indexOf(";base64,"));
        byte[] fileData;
        try {
            fileData = Base64.decode(encodedData, Base64.DEFAULT);
        } catch (IllegalArgumentException exception) {
            call.reject("The attachment data could not be decoded.", exception);
            return;
        }

        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = dataMimeType.isEmpty() ? "application/octet-stream" : dataMimeType;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(call, fileName, mimeType, fileData);
        } else {
            saveWithLegacyStorage(call, fileName, mimeType, fileData);
        }
    }

    private void saveWithMediaStore(PluginCall call, String fileName, String mimeType, byte[] fileData) {
        Uri fileUri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            fileUri = getContext().getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            );
            if (fileUri == null) {
                call.reject("Android could not create the file in Downloads.");
                return;
            }

            try (OutputStream output = getContext().getContentResolver().openOutputStream(fileUri)) {
                if (output == null) {
                    throw new IOException("Could not open the Downloads file.");
                }
                output.write(fileData);
            }

            ContentValues completed = new ContentValues();
            completed.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContext().getContentResolver().update(fileUri, completed, null, null);
            resolveSavedFile(call, fileName);
        } catch (IOException | RuntimeException exception) {
            if (fileUri != null) {
                getContext().getContentResolver().delete(fileUri, null, null);
            }
            call.reject("Could not save the file to Downloads.", exception);
        }
    }

    @SuppressWarnings("deprecation")
    private void saveWithLegacyStorage(PluginCall call, String fileName, String mimeType, byte[] fileData) {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if ((!downloads.exists() && !downloads.mkdirs()) || !downloads.isDirectory()) {
            call.reject("Could not access the Downloads folder.");
            return;
        }

        File destination = uniqueFile(downloads, fileName);
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(fileData);
        } catch (IOException exception) {
            if (destination.exists()) {
                destination.delete();
            }
            call.reject("Could not save the file to Downloads.", exception);
            return;
        }

        MediaScannerConnection.scanFile(
            getContext(),
            new String[] { destination.getAbsolutePath() },
            new String[] { mimeType },
            null
        );
        resolveSavedFile(call, destination.getName());
    }

    private File uniqueFile(File directory, String fileName) {
        File destination = new File(directory, fileName);
        if (!destination.exists()) {
            return destination;
        }

        int extensionIndex = fileName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
        String extension = extensionIndex > 0 ? fileName.substring(extensionIndex) : "";
        int suffix = 1;
        do {
            destination = new File(directory, baseName + " (" + suffix++ + ")" + extension);
        } while (destination.exists());
        return destination;
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "file";
        }
        String sanitized = fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return sanitized.isEmpty() || ".".equals(sanitized) || "..".equals(sanitized)
            ? "file"
            : sanitized;
    }

    private void resolveSavedFile(PluginCall call, String fileName) {
        JSObject result = new JSObject();
        result.put("fileName", fileName);
        result.put("location", "Downloads");
        call.resolve(result);
    }
}
