package com.luatools.javasteam;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Universal, sandbox-safe CloudRedirect sync helper for ANY Android Steam emulator
 * (GameNative, Pluvia, BannerHub, GameHub, Winlator, etc.).
 *
 * Transfers flat save files over ContentProvider ParcelFileDescriptor streams so
 * no root access, SAF prompts, or cross-app storage permissions are needed.
 */
public final class LuaToolsCloudSync {

    private static final String TAG = "LuaToolsCloudSync";
    public static final String COMPANION_PKG = "com.luatools.companion";
    public static final Uri COMPANION_BRIDGE_URI =
            Uri.parse("content://com.luatools.companion.bridge");

    private LuaToolsCloudSync() {}

    public static boolean isCompanionInstalled(Context ctx) {
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getPackageInfo(COMPANION_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Call before launching a LuaTools game. Pulls the latest saves from Google Drive
     * via the LuaTools Companion App and unpacks them directly into {@code saveRemoteDir}
     * (e.g. {@code <winePrefix>/drive_c/users/steamuser/AppData/Roaming/GSE Saves/<appId>/remote}).
     */
    public static boolean onPreLaunch(Context ctx, int appId, File saveRemoteDir) {
        if (ctx == null || appId <= 0 || saveRemoteDir == null || !isCompanionInstalled(ctx)) {
            return false;
        }
        try {
            Bundle args = new Bundle();
            args.putInt("app_id", appId);
            args.putString("source_package", ctx.getPackageName());

            Bundle res = ctx.getContentResolver().call(
                    COMPANION_BRIDGE_URI, "sync_pre_launch", String.valueOf(appId), args);
            if (res == null || !res.getBoolean("ok", false)) return false;

            String zipUriStr = res.getString("zip_uri");
            if (res.getBoolean("has_files", false) && zipUriStr != null && !zipUriStr.isEmpty()) {
                if (!saveRemoteDir.exists()) saveRemoteDir.mkdirs();
                InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(zipUriStr));
                if (in != null) {
                    try {
                        int count = unzipToDir(in, saveRemoteDir);
                        Log.i(TAG, "Pre-launch cloud sync unpacked " + count + " file(s) for appId=" + appId);
                    } finally {
                        in.close();
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "onPreLaunch failed for appId=" + appId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Call after a LuaTools game exits. Packs all files from {@code saveRemoteDir}, streams
     * them to the Companion App, and uploads them to Google Drive via CloudRedirect.
     */
    public static boolean onPostExit(Context ctx, int appId, File saveRemoteDir) {
        if (ctx == null || appId <= 0 || saveRemoteDir == null || !saveRemoteDir.isDirectory()
                || !isCompanionInstalled(ctx)) {
            return false;
        }
        try {
            Uri uploadUri = Uri.parse("content://com.luatools.companion.bridge/cloud_in/" + appId);
            OutputStream out = ctx.getContentResolver().openOutputStream(uploadUri, "wt");
            if (out == null) return false;
            int count;
            try {
                count = zipDirToStream(saveRemoteDir, out);
            } finally {
                out.close();
            }
            if (count <= 0) return false;

            Bundle args = new Bundle();
            args.putInt("app_id", appId);
            args.putString("source_package", ctx.getPackageName());
            args.putBoolean("has_zip", true);
            Bundle res = ctx.getContentResolver().call(
                    COMPANION_BRIDGE_URI, "sync_post_exit", String.valueOf(appId), args);
            boolean ok = res != null && res.getBoolean("ok", false);
            Log.i(TAG, "Post-exit cloud upload appId=" + appId + " files=" + count + " ok=" + ok);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "onPostExit failed for appId=" + appId + ": " + e.getMessage());
            return false;
        }
    }

    private static int unzipToDir(InputStream in, File destDir) throws Exception {
        int count = 0;
        String canonicalDest = destDir.getCanonicalPath() + File.separator;
        ZipInputStream zis = new ZipInputStream(in);
        byte[] buf = new byte[8192];
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName().replace('\\', '/');
            if (name.isEmpty() || name.startsWith("/") || name.contains("..")) continue;
            File outFile = new File(destDir, name);
            if (!outFile.getCanonicalPath().startsWith(canonicalDest)) continue;
            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                if (outFile.isDirectory()) deleteRecursive(outFile);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(outFile);
                try {
                    int r;
                    while ((r = zis.read(buf)) != -1) fos.write(buf, 0, r);
                } finally {
                    fos.close();
                }
                count++;
            }
            zis.closeEntry();
        }
        return count;
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursive(c);
        }
        f.delete();
    }

    private static int zipDirToStream(File rootDir, OutputStream out) throws Exception {
        ZipOutputStream zos = new ZipOutputStream(out);
        int count = addDirToZip(rootDir, rootDir, zos);
        zos.finish();
        zos.flush();
        return count;
    }

    private static int addDirToZip(File root, File dir, ZipOutputStream zos) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        byte[] buf = new byte[8192];
        for (File f : files) {
            if (f.isDirectory()) {
                count += addDirToZip(root, f, zos);
            } else if (f.isFile()) {
                String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1)
                        .replace('\\', '/');
                zos.putNextEntry(new ZipEntry(rel));
                FileInputStream fis = new FileInputStream(f);
                try {
                    int r;
                    while ((r = fis.read(buf)) != -1) zos.write(buf, 0, r);
                } finally {
                    fis.close();
                }
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }
}
