package org.totschnig.myexpenses.util;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.support.v4.content.ContextCompat;

import org.totschnig.myexpenses.R;
import org.totschnig.myexpenses.preference.PrefKey;

import java.io.File;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class PermissionHelper {
  public static final int PERMISSIONS_REQUEST_WRITE_CALENDAR = 1;
  public static final int PERMISSIONS_REQUEST_STORAGE = 2;

  private PermissionHelper() {}

  public enum PermissionGroup {
    STORAGE(externalReadPermissionCompat(), PrefKey.STORAGE_PERMISSION_REQUESTED, PERMISSIONS_REQUEST_STORAGE),
    CALENDAR(Manifest.permission.WRITE_CALENDAR, PrefKey.CALENDAR_PERMISSION_REQUESTED, PERMISSIONS_REQUEST_WRITE_CALENDAR);

    public final String androidPermission;
    public final PrefKey prefKey;
    public final int requestCode;

    PermissionGroup(String androidPermission, PrefKey prefKey, int requestCode) {
      this.androidPermission = androidPermission;
      this.prefKey = prefKey;
      this.requestCode = requestCode;
    }

    public static PermissionGroup fromRequestCode(int requestCode) {
      if (requestCode == STORAGE.requestCode) return STORAGE;
      if (requestCode == CALENDAR.requestCode) return CALENDAR;
      throw new IllegalArgumentException("Undefined requestCode " + requestCode);
    }
  }

  public static boolean hasPermission(Context context, String permission) {
    return ContextCompat.checkSelfPermission(context, permission) == PERMISSION_GRANTED;
  }

  public static boolean hasExternalReadPermission(Context context) {
    return hasPermission(context, PermissionGroup.STORAGE.androidPermission);
  }

  public static String externalReadPermissionCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      return Manifest.permission.READ_EXTERNAL_STORAGE;
    } else {
      return Manifest.permission.WRITE_EXTERNAL_STORAGE;
    }
  }

  public static boolean canReadUri(Uri uri, Context context) {
    switch(uri.getScheme()) {
      case "file":
        File file = new File(uri.getPath());
        return file.exists() && file.canRead();
      default:
        return hasExternalReadPermission(context) ||
            context.checkUriPermission(uri, Binder.getCallingPid(), Binder.getCallingUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) == PERMISSION_GRANTED;
    }
  }

  public static PrefKey permissionRequestedKey(int requestCode) {
    return PermissionGroup.fromRequestCode(requestCode).prefKey;
  }

  public static int permissionRequestRationaleResId(int requestCode) {
    switch (requestCode) {
      case PERMISSIONS_REQUEST_WRITE_CALENDAR:
        return R.string.calendar_permission_required;
      case PERMISSIONS_REQUEST_STORAGE:
        return R.string.storage_permission_required;
    }
    throw new IllegalArgumentException("Undefined requestCode " + requestCode);
  }
}
