package com.example.chatandroid;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Grants the system installer read access to one verified private APK only. */
public final class UpdateInstallProvider extends ContentProvider {
    static Uri uri(android.content.Context context) {
        return Uri.parse("content://" + context.getPackageName() + ".updates/update.apk");
    }

    private File approved(Uri uri) throws FileNotFoundException {
        if (getContext() == null || uri == null || !uri.equals(uri(getContext()))) throw new FileNotFoundException();
        File file = new File(new File(getContext().getFilesDir(), "updates"), "verified-update.apk");
        String expected = getContext().getSharedPreferences("verified_update", 0).getString("sha256", "");
        try {
            if (!file.isFile() || !expected.matches("[0-9a-f]{64}") || !UpdatePolicy.sha256(file).equals(expected))
                throw new FileNotFoundException();
        } catch (FileNotFoundException error) { throw error; }
        catch (Exception error) { throw new FileNotFoundException("安装包校验失败"); }
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(approved(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return uri.equals(uri(getContext())) ? "application/vnd.android.package-archive" : null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = approved(uri);
            String[] columns = projection == null ? new String[]{"_display_name", "_size"} : projection;
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if ("_display_name".equals(columns[i])) row[i] = "Chat-update.apk";
                else if ("_size".equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException error) { return null; }
    }
    @Override public boolean onCreate() { return true; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
