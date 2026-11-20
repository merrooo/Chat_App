package com.merrooo.Chat_APP;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.UUID;

@CapacitorPlugin(name = "FileSaver")
public class FileSaverPlugin extends Plugin {
    @PluginMethod
    public void saveFile(PluginCall call) {
        String fileName = sanitizeFileName(call.getString("fileName"));
        try {
            decodeAttachment(call.getString("dataUrl"));
        } catch (IOException exception) {
            call.reject("The attachment data is invalid.", exception);
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(resolveMimeType(call, fileName));
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        startActivityForResult(call, intent, "saveFileResult");
    }

    @ActivityCallback
    private void saveFileResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        if (result == null || result.getResultCode() != Activity.RESULT_OK ||
            result.getData() == null || result.getData().getData() == null) {
            JSObject cancelled = new JSObject();
            cancelled.put("cancelled", true);
            call.resolve(cancelled);
            return;
        }

        String fileName = sanitizeFileName(call.getString("fileName"));
        Uri destination = result.getData().getData();
        try {
            byte[] fileData = decodeAttachment(call.getString("dataUrl"));
            try (OutputStream output = getContext().getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) {
                    throw new IOException("Could not open the selected destination.");
                }
                output.write(fileData);
            }

            JSObject saved = new JSObject();
            saved.put("fileName", fileName);
            saved.put("location", "المجلد الذي اخترته");
            call.resolve(saved);
        } catch (IOException | RuntimeException exception) {
            call.reject("Could not save the file to the selected location.", exception);
        }
    }

    @PluginMethod
    public void openFile(PluginCall call) {
        String fileName = sanitizeFileName(call.getString("fileName"));
        try {
            byte[] fileData = decodeAttachment(call.getString("dataUrl"));
            File attachmentDirectory = new File(getContext().getCacheDir(), "chat-attachments");
            if ((!attachmentDirectory.exists() && !attachmentDirectory.mkdirs()) ||
                !attachmentDirectory.isDirectory()) {
                throw new IOException("Could not create the temporary attachment directory.");
            }

            File temporaryFile = new File(
                attachmentDirectory,
                UUID.randomUUID() + "-" + fileName
            );
            try (FileOutputStream output = new FileOutputStream(temporaryFile)) {
                output.write(fileData);
            }

            Uri contentUri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                temporaryFile
            );
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(contentUri, resolveOpenMimeType(call, fileName));
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getActivity().startActivity(Intent.createChooser(viewIntent, "فتح الملف باستخدام"));

            JSObject opened = new JSObject();
            opened.put("fileName", fileName);
            call.resolve(opened);
        } catch (ActivityNotFoundException exception) {
            call.reject("No application can open this file type.", exception);
        } catch (IOException | RuntimeException exception) {
            call.reject("Could not open the attachment.", exception);
        }
    }

    private byte[] decodeAttachment(String dataUrl) throws IOException {
        String marker = ";base64,";
        int separator = dataUrl == null ? -1 : dataUrl.indexOf(marker);
        if (dataUrl == null || !dataUrl.startsWith("data:") || separator < 0) {
            throw new IOException("The attachment data is not a Base64 data URL.");
        }
        try {
            return Base64.decode(dataUrl.substring(separator + marker.length()), Base64.DEFAULT);
        } catch (IllegalArgumentException exception) {
            throw new IOException("The attachment data could not be decoded.", exception);
        }
    }

    private String resolveMimeType(PluginCall call, String fileName) {
        String mimeType = call.getString("mimeType");
        if (mimeType != null && !mimeType.trim().isEmpty() &&
            !"application/octet-stream".equalsIgnoreCase(mimeType)) {
            return mimeType;
        }

        String dataUrl = call.getString("dataUrl");
        if (dataUrl != null && dataUrl.startsWith("data:")) {
            int separator = dataUrl.indexOf(';');
            if (separator > "data:".length()) {
                String dataMimeType = dataUrl.substring("data:".length(), separator);
                if (dataMimeType.contains("/") &&
                    !"application/octet-stream".equalsIgnoreCase(dataMimeType)) {
                    return dataMimeType;
                }
            }
        }

        String guessedMimeType = URLConnection.guessContentTypeFromName(fileName);
        return guessedMimeType == null ? "*/*" : guessedMimeType;
    }

    private String resolveOpenMimeType(PluginCall call, String fileName) {
        String mimeType = resolveMimeType(call, fileName);
        return "application/octet-stream".equalsIgnoreCase(mimeType) ? "*/*" : mimeType;
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
}
