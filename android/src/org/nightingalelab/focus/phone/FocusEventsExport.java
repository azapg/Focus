package org.nightingalelab.focus.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Read-only, temporary share-sheet access to the redacted events. No exported cache file. */
public final class FocusEventsExport extends ContentProvider {
  private static final String FILE = "focus-events.jsonl";

  static void share(Activity activity) {
    Uri uri = new Uri.Builder().scheme("content").authority(activity.getPackageName() + ".events")
        .appendPath(FILE).build();
    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    send.setClipData(ClipData.newRawUri(FILE, uri));
    activity.startActivity(Intent.createChooser(send, "Share diagnostics"));
  }

  @Override public boolean onCreate() { return true; }

  @Override public String getType(Uri uri) {
    return ("/" + FILE).equals(uri.getPath()) ? "text/plain" : null;
  }

  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (getType(uri) == null || !mode.equals("r")) throw new FileNotFoundException();
    return openPipeHelper(uri, "text/plain", null, getContext(), (pipe, ignoredUri, mime, opts, context) -> {
      try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe)) {
        out.write(FocusEvents.export(context).getBytes(StandardCharsets.UTF_8));
      } catch (IOException | RuntimeException ignored) {}
    });
  }

  @Override public Cursor query(Uri uri, String[] projection, String selection,
      String[] selectionArgs, String sortOrder) {
    if (getType(uri) == null) return null;
    String[] columns = projection == null
        ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
    MatrixCursor cursor = new MatrixCursor(columns, 1);
    MatrixCursor.RowBuilder row = cursor.newRow();
    for (String column : columns) row.add(OpenableColumns.DISPLAY_NAME.equals(column) ? FILE : null);
    return cursor;
  }

  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
    throw new UnsupportedOperationException();
  }
}
